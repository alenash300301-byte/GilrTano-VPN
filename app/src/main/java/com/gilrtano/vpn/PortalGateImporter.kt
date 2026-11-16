package com.gilrtano.vpn

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Importer for PORTAL GATE AmneziaWG quick-config archives.
 * Private keys are kept only in the imported profile/config and are never logged.
 */
data class PortalGateProfile(
    val name: String,
    val country: String,
    val endpoint: String,
    val config: String
)

object PortalGateImporter {
    private val sectionPattern = Regex("^\\s*\\[([^]]+)]\\s*$")
    private val pairPattern = Regex("^\\s*([A-Za-z0-9_]+)\\s*=\\s*(.*?)\\s*$")

    fun parseZip(input: InputStream): List<PortalGateProfile> {
        val result = mutableListOf<PortalGateProfile>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    val entryName = entry.name.substringAfterLast('/')
                    when {
                        entryName.endsWith(".conf", ignoreCase = true) -> {
                            val text = BufferedReader(InputStreamReader(zip, Charsets.UTF_8)).readText()
                            runCatching { parseConf(text, entryName.substringBeforeLast('.')) }
                                .onSuccess { result += it }
                        }
                        entryName.endsWith(".yaml", ignoreCase = true) ||
                            entryName.endsWith(".yml", ignoreCase = true) -> {
                            val bytes = zip.readBytes()
                            runCatching {
                                parseYamlArchive(ByteArrayInputStream(bytes), entryName.substringBeforeLast('.'))
                            }.onSuccess { result += it }
                        }
                    }
                    zip.closeEntry()
                }
            }
        }
        require(result.isNotEmpty()) { "В архиве не найдены поддерживаемые профили PORTAL GATE (.conf / AmneziaWG YAML / MASQUE YAML)" }
        return result.distinctBy { it.config }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseYamlArchive(input: InputStream, fileName: String): List<PortalGateProfile> {
        val bytes = input.readBytes()
        val root = org.yaml.snakeyaml.Yaml(
            org.yaml.snakeyaml.constructor.SafeConstructor(org.yaml.snakeyaml.LoaderOptions())
        ).load<Any?>(ByteArrayInputStream(bytes)) as? Map<String, Any?>
            ?: error("$fileName: некорректный YAML")
        if (root["portal"] is Map<*, *>) {
            val proxies = root["proxies"] as? List<*> ?: error("$fileName: отсутствует proxies")
            val proxy = proxies.firstOrNull() as? Map<String, Any?> ?: error("$fileName: отсутствует профиль")
            val merged = LinkedHashMap<String, Any?>().apply {
                putAll(root["portal"] as Map<String, Any?>)
                putAll(proxy)
            }
            fun field(vararg keys: String): String? =
                keys.firstNotNullOfOrNull { key -> merged[key]?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            val privateKey = field("private-key", "private_key") ?: error("$fileName: отсутствует private-key")
            val publicKey = field("public-key", "public_key") ?: error("$fileName: отсутствует public-key")
            val server = field("server") ?: error("$fileName: отсутствует server")
            val port = field("port")?.toIntOrNull()?.takeIf { it in 1..65535 }
                ?: error("$fileName: некорректный port")
            val address = listOfNotNull(field("ip"), field("ipv6")).joinToString(", ")
                .ifBlank { field("address") ?: error("$fileName: отсутствует IP") }
            val lines = mutableListOf("[Interface]", "PrivateKey = $privateKey", "Address = $address")
            (merged["dns"] as? List<*>)?.mapNotNull { it?.toString()?.trim() }
                ?.filter(String::isNotEmpty)?.takeIf { it.isNotEmpty() }
                ?.let { dns -> lines += "DNS = " + dns.joinToString(", ") }
            field("mtu")?.let { lines += "MTU = $it" }
            (merged["amnezia-wg-option"] as? Map<*, *>)?.forEach { (key, value) ->
                if (key != null && value != null) lines += "${key.toString().replace('-', '_')} = $value"
            }
            lines += "[Peer]"
            lines += "PublicKey = $publicKey"
            lines += "Endpoint = $server:$port"
            field("allowed-ips", "allowed_ips")?.let { lines += "AllowedIPs = $it" }
            field("persistent-keepalive", "persistent_keepalive")?.let { lines += "PersistentKeepalive = $it" }
            return listOf(parseConf(lines.joinToString("\n"), fileName))
        }
        return PortalGateMasqueImporter.parseYaml(ByteArrayInputStream(bytes))
    }

    fun parseConf(text: String, fileName: String): PortalGateProfile {
        val sections = linkedMapOf<String, MutableMap<String, String>>()
        var section = ""
        text.removePrefix("\uFEFF").lineSequence().forEach { raw ->
            // WARP generators sometimes export the Cloudflare client ID as a commented
            // "Reserved = a,b,c" line because generic WireGuard apps ignore it.
            val rawTrimmed = raw.trim()
            val reservedComment = rawTrimmed.removePrefix("#").trim()
                .takeIf { it.startsWith("reserved", ignoreCase = true) && it.contains('=') }
            val line = (reservedComment ?: raw.substringBefore('#'))
                .substringBefore(';').trim()
            if (line.isEmpty()) return@forEach
            val header = sectionPattern.matchEntire(line)
            if (header != null) {
                section = header.groupValues[1].trim().lowercase(Locale.ROOT)
                sections.getOrPut(section) { linkedMapOf() }
            } else {
                val pair = pairPattern.matchEntire(line)
                if (pair != null && section.isNotEmpty()) {
                    sections.getOrPut(section) { linkedMapOf() }[pair.groupValues[1].lowercase(Locale.ROOT)] = pair.groupValues[2].trim()
                }
            }
        }
        val iface = sections["interface"] ?: error("В $fileName отсутствует [Interface]")
        val peer = sections["peer"] ?: error("В $fileName отсутствует [Peer]")
        fun required(map: Map<String, String>, key: String): String =
            map[key]?.takeIf { it.isNotBlank() } ?: error("В $fileName отсутствует $key")
        val privateKey = required(iface, "privatekey")
        val publicKey = required(peer, "publickey")
        val endpoint = required(peer, "endpoint")
        val host = endpoint.substringAfterLast('@').let {
            if (it.startsWith("[")) it.substringAfter('[').substringBefore(']') else it.substringBeforeLast(':')
        }
        val port = endpoint.substringAfterLast(':').substringBefore(']').toIntOrNull()
            ?: error("Некорректный Endpoint в $fileName")
        require(host.isNotBlank() && port in 1..65535) { "Некорректный Endpoint в $fileName" }
        val addresses = required(iface, "address").split(',').map { raw ->
            val value = raw.trim()
            require(value.isNotEmpty()) { "Пустой Address в $fileName" }
            // WireGuard .conf files may omit prefix lengths; sing-box requires CIDR prefixes.
            if ('/' in value) value else if (':' in value) "$value/128" else "$value/32"
        }
        require(addresses.isNotEmpty()) { "В $fileName отсутствует Address" }
        val allowed = (peer["allowedips"] ?: "0.0.0.0/0, ::/0")
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }
        require(allowed.isNotEmpty()) { "В $fileName отсутствует AllowedIPs" }
        val dnsServers = iface["dns"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        dnsServers.forEach { dns ->
            require(dns.contains(':') || dns.substringBefore('%').split('.').size == 4) {
                "Некорректный DNS в $fileName"
            }
        }
        val amnezia = JSONObject()
        val intKeys = setOf("jc", "jmin", "jmax", "s1", "s2", "s3", "s4")
        val stringKeys = setOf("h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5",
            "header_protection_key", "content_padding_addition", "rekey_after_time", "rekey_timeout",
            "reject_after_time", "keepalive_timeout", "max_handshake_attempts")
        val boolKeys = setOf("random_trailers", "disable_cookies")
        (iface + peer).forEach { (key, value) ->
            when {
                key in intKeys -> value.toIntOrNull()?.let { amnezia.put(key, it) }
                key in stringKeys && value.isNotBlank() -> amnezia.put(key, value)
                key in boolKeys -> amnezia.put(key, value.equals("true", true) || value.equals("on", true) || value == "1" || value.equals("yes", true))
            }
        }
        val peerJson = JSONObject()
            .put("address", host)
            .put("port", port)
            .put("public_key", publicKey)
            .put("allowed_ips", JSONArray(allowed))
        peer["presharedkey"]?.takeIf { it.isNotBlank() }?.let { peerJson.put("pre_shared_key", it) }
        peer["persistentkeepalive"]?.toIntOrNull()?.let { peerJson.put("persistent_keepalive_interval", it) }
        peer["reserved"]?.takeIf { it.isNotBlank() }?.let { rawReserved ->
            val reserved = rawReserved.split(',').map { value ->
                value.trim().toIntOrNull()?.also { require(it in 0..255) {
                    "Некорректный байт Reserved в $fileName"
                } } ?: error("Некорректный байт Reserved в $fileName")
            }
            require(reserved.size == 3) { "В $fileName поле Reserved должно содержать ровно 3 числа" }
            peerJson.put("reserved", JSONArray(reserved))
        }
        val endpointJson = JSONObject()
            .put("type", "awg")
            .put("tag", "proxy")
            .put("system", false)
            .put("private_key", privateKey)
            .put("address", JSONArray(addresses))
            .put("peers", JSONArray().put(peerJson))
            .put("awg", amnezia)
        iface["mtu"]?.toIntOrNull()?.let { endpointJson.put("mtu", it) }
        val dnsJson = JSONObject()
        if (dnsServers.isNotEmpty()) {
            val servers = JSONArray()
            dnsServers.forEachIndexed { index, address ->
                servers.put(JSONObject()
                    .put("type", "udp")
                    .put("tag", "portal-dns-$index")
                    .put("server", address))
            }
            dnsJson.put("servers", servers).put("final", "portal-dns-0")
        }
        val config = JSONObject()
            .put("log", JSONObject().put("level", "warn"))
            .put("dns", dnsJson)
            .put("inbounds", JSONArray().put(JSONObject()
                .put("type", "socks").put("tag", "socks-in")
                .put("listen", "127.0.0.1").put("listen_port", 10808).put("udp", true)))
            .put("endpoints", JSONArray().put(endpointJson))
            .put("route", JSONObject().put("final", "proxy").put("auto_detect_interface", true))
            .toString()
        val countryCode = fileName.substringAfter("PORTAL-", "").substringBefore('_').uppercase(Locale.ROOT)
        val country = mapOf("CA" to "Канада", "US" to "США", "NL" to "Нидерланды", "JP" to "Япония", "SG" to "Сингапур", "MX" to "Мексика", "RO" to "Румыния", "CH" to "Швейцария", "NO" to "Норвегия", "PL" to "Польша")[countryCode] ?: "PORTAL GATE"
        return PortalGateProfile(fileName, country, "$host:$port", config)
    }
}
