package io.github.georgexie2333.usque

/** Minimal non-Flutter identity-store bridge required by the upstream VPN runtime. */
internal class AndroidEngineMethodHandler {
    interface IdentityStore {
        fun <T> withProxyLock(action: () -> T): T = action()
        fun put(profileId: String, record: SecureIdentityStore.Record, value: ByteArray)
        fun get(profileId: String, record: SecureIdentityStore.Record): ByteArray?
        fun delete(profileId: String, record: SecureIdentityStore.Record)
        fun deleteIdentity(profileId: String)
        fun clearAll()
    }
    internal class SecureIdentityStoreAdapter(private val store: SecureIdentityStore) : IdentityStore {
        override fun <T> withProxyLock(action: () -> T): T = store.withProxyLock(action)
        override fun put(profileId: String, record: SecureIdentityStore.Record, value: ByteArray) = store.put(profileId, record, value)
        override fun get(profileId: String, record: SecureIdentityStore.Record): ByteArray? = store.get(profileId, record)
        override fun delete(profileId: String, record: SecureIdentityStore.Record) = store.delete(profileId, record)
        override fun deleteIdentity(profileId: String) = store.deleteIdentity(profileId)
        override fun clearAll() = store.withProxyLock { store.clearAll() }
    }
}
