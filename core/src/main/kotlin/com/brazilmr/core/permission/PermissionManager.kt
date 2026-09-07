package com.brazilmr.core.permission

import java.security.MessageDigest

enum class Capability(val wireName: String) {
    UNSAFE_EXECUTION("unsafe_execution"), SCENARIO("scenario"),
    HAND_TRACKING("hand_tracking"), INPUT("input"),
}

data class Principal(val id: String) {
    init { require(id.length in 1..180 && id.none { it.isWhitespace() || it == '/' || it == '\\' }) }
    companion object {
        /** Grants cannot silently survive changed source code or a different plugin version. */
        fun script(appId: String, source: String): Principal {
            require(appId.matches(Regex("[A-Za-z0-9._-]{1,64}")))
            val digest = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
            return Principal("$appId@" + digest.joinToString("") { "%02x".format(it) })
        }
    }
}
interface GrantStore {
    fun granted(principal: Principal, capability: Capability): Boolean
    fun write(principal: Principal, capability: Capability, granted: Boolean)
}
class MemoryGrantStore : GrantStore {
    private val grants = HashSet<Pair<Principal, Capability>>()
    @Synchronized override fun granted(principal: Principal, capability: Capability) = (principal to capability) in grants
    @Synchronized override fun write(principal: Principal, capability: Capability, granted: Boolean) {
        if (granted) grants.add(principal to capability) else grants.remove(principal to capability)
    }
}
class PermissionDenied(capability: Capability) : SecurityException("Permissão negada: ${capability.wireName}")

class PermissionManager(private val store: GrantStore = MemoryGrantStore()) {
    private val requested = HashMap<Principal, Set<Capability>>()
    @Synchronized fun register(principal: Principal, manifest: Set<Capability>) { requested[principal] = manifest.toSet() }
    @Synchronized fun requested(principal: Principal): Set<Capability> = requested[principal] ?: emptySet()
    @Synchronized fun has(principal: Principal, capability: Capability) = capability in requested(principal) && store.granted(principal, capability)
    fun require(principal: Principal, capability: Capability) { if (!has(principal, capability)) throw PermissionDenied(capability) }
    /** Host only. A manifest, Lua argument or plugin callback is never a user decision. */
    @Synchronized fun decideFromUser(principal: Principal, capability: Capability, grant: Boolean) {
        require(capability in requested(principal)) { "Capability não declarada no manifesto" }
        store.write(principal, capability, grant)
    }
    @Synchronized fun revokeAll(principal: Principal) {
        for (capability in Capability.entries) store.write(principal, capability, false)
    }
}
