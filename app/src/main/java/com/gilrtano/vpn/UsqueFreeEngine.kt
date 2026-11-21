package com.gilrtano.vpn

import android.content.Context
import io.github.georgexie2333.usque.AndroidVpnProfile
import io.github.georgexie2333.usque.NativeEngine
import io.github.georgexie2333.usque.SecureIdentityStore
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID

/** First-run bootstrap for the FREE Usque v0.3.4 engine. */
internal object UsqueFreeEngine {
    private const val DEFAULT_PROFILE_ID = "8c30b771-9ebd-457a-b67b-bbc74a1ddba6"

    fun prepare(context: Context): String {
        check(NativeEngine.isLinked() && NativeEngine.isReady()) { "Движок Usque не загрузился" }
        val configPath = File(context.noBackupFilesDir, "usque_config/profiles-v2.json").absolutePath
        val store = SecureIdentityStore(context)
        var catalog = command(configPath, JSONObject().put("command", "list_profiles"))
        var profileId = catalog.optString("active_profile_id")
            .takeIf { it.isNotBlank() && it != "null" }
            ?: DEFAULT_PROFILE_ID

        // begin_initial_identity is intentionally restricted to the active account.
        // Selecting a fallback ID locally is not enough: persist the selection first.
        if (catalog.optString("active_profile_id") != profileId) {
            catalog = command(
                configPath,
                JSONObject()
                    .put("command", "set_active_profile")
                    .put("profile_id", profileId),
            )
            check(catalog.optString("active_profile_id") == profileId) {
                "Не удалось активировать профиль FREE для регистрации WARP"
            }
        }
        var profile = findProfile(catalog, profileId)

        // A previous process may have died after reserving the initial-identity
        // operation. Usque rejects a second begin while that journal is pending.
        val pending = catalog.optJSONObject("initial_identity_operation")
        if (pending != null &&
            pending.optString("profile_id") == profileId &&
            pending.optString("phase") == "pending"
        ) {
            command(
                configPath,
                JSONObject()
                    .put("command", "finish_initial_identity")
                    .put("operation_id", pending.optString("operation_id"))
                    .put("profile_id", profileId)
                    .put("phase", "interrupted")
                    .put("error_code", "INITIAL_IDENTITY_INTERRUPTED"),
            )
            catalog = command(configPath, JSONObject().put("command", "list_profiles"))
            check(catalog.optString("active_profile_id") == profileId) {
                "Профиль FREE изменился во время восстановления регистрации"
            }
            profile = findProfile(catalog, profileId)
        }

        var secret = store.get(profileId, SecureIdentityStore.Record.WARP_SECRET)
        try {
            val valid = secret != null && NativeEngine.validateWarpSecret(secret) == NativeEngine.OK
            if (!valid) {
                check(secret == null) { "Сохранённый WARP-ключ повреждён. Нужна безопасная переинициализация FREE." }
                registerInitialIdentity(configPath, profileId, store)
                catalog = command(configPath, JSONObject().put("command", "list_profiles"))
                check(catalog.optString("active_profile_id") == profileId) {
                    "Профиль FREE изменился после регистрации WARP"
                }
                profile = findProfile(catalog, profileId)
                secret = store.get(profileId, SecureIdentityStore.Record.WARP_SECRET)
                check(secret != null && NativeEngine.validateWarpSecret(secret) == NativeEngine.OK) {
                    "Cloudflare не подтвердил WARP-идентичность"
                }
            }
            val frontends = profile.optJSONObject("frontends") ?: JSONObject()
            frontends.put("tunnel", true)
            profile.put("frontends", frontends)
            profile.put("mode", "vpn")
            val json = profile.toString()
            AndroidVpnProfile.parse(json)
            return json
        } finally {
            secret?.fill(0)
        }
    }

    private fun registerInitialIdentity(configPath: String, profileId: String, store: SecureIdentityStore) {
        val operationId = UUID.randomUUID().toString()
        val begin = JSONObject()
            .put("command", "begin_initial_identity")
            .put("operation_id", operationId)
            .put("profile_id", profileId)
            .put("method", "register")
            .put("organization", JSONObject.NULL)
            .put("owner_epoch", UUID.randomUUID().toString())
        command(configPath, begin)

        var identity: ByteArray? = null
        var metadata: ByteArray? = null
        var stored = false
        try {
            identity = NativeEngine.registerConsumerWarp(Locale.getDefault().toString())
                ?: error("Cloudflare не вернул WARP-идентичность")
            check(NativeEngine.validateWarpSecret(identity) == NativeEngine.OK) {
                "Cloudflare вернул недействительный WARP-ключ"
            }
            val entitlement = runCatching {
                JSONObject(identity.toString(Charsets.UTF_8)).optString("entitlement")
            }.getOrNull()
            metadata = when (entitlement) {
                "warp_plus" -> """{"version":1,"provider":"consumer","entitlement":"warp_plus"}""".toByteArray()
                "free" -> """{"version":1,"provider":"consumer","entitlement":"free"}""".toByteArray()
                else -> """{"version":1,"provider":"consumer"}""".toByteArray()
            }
            store.put(profileId, SecureIdentityStore.Record.WARP_SECRET, identity)
            store.put(profileId, SecureIdentityStore.Record.IDENTITY_METADATA, metadata)
            stored = true
            command(configPath, JSONObject()
                .put("command", "finish_initial_identity")
                .put("operation_id", operationId)
                .put("profile_id", profileId)
                .put("phase", "completed"))
        } catch (error: Exception) {
            if (stored) {
                runCatching { store.delete(profileId, SecureIdentityStore.Record.WARP_SECRET) }
                runCatching { store.delete(profileId, SecureIdentityStore.Record.IDENTITY_METADATA) }
            }
            runCatching {
                command(configPath, JSONObject()
                    .put("command", "finish_initial_identity")
                    .put("operation_id", operationId)
                    .put("profile_id", profileId)
                    .put("phase", "failed")
                    .put("error_code", "REGISTRATION_FAILED"))
            }
            throw error
        } finally {
            identity?.fill(0)
            metadata?.fill(0)
        }
    }

    private fun findProfile(catalog: JSONObject, profileId: String): JSONObject {
        val profiles = catalog.optJSONArray("profiles") ?: error("Usque не вернул список профилей")
        for (index in 0 until profiles.length()) {
            val candidate = profiles.getJSONObject(index)
            if (candidate.optString("id") == profileId) return JSONObject(candidate.toString())
        }
        error("Профиль FREE не найден в хранилище Usque")
    }

    private fun command(path: String, request: JSONObject): JSONObject {
        val result = NativeEngine.applyProfileCommand(path, request.toString())
            ?: error("Usque не выполнил команду " + request.optString("command"))
        return JSONObject(result)
    }
}
