package com.brazilmr.core.plugin

import com.brazilmr.core.permission.*

enum class PluginKind { TRACKING, FILTER, RENDERER, LAYOUT, API, DEVELOPER_TOOL }
data class PluginManifest(val id: String, val version: String, val kind: PluginKind, val permissions: Set<Capability>)
interface XrPlugin {
    val manifest: PluginManifest
    fun start(context: PluginContext)
    fun stop()
}
class PluginContext internal constructor(val principal: Principal, private val permissions: PermissionManager) {
    fun require(capability: Capability) = permissions.require(principal, capability)
}

/** Statically linked, reviewed plugins only. No loading arbitrary DEX/native code from storage. */
class PluginSystem(private val permissions: PermissionManager) : AutoCloseable {
    private data class Entry(val plugin: XrPlugin, val principal: Principal, var active: Boolean = false)
    private val entries = LinkedHashMap<String, Entry>()
    fun register(plugin: XrPlugin): Principal {
        val manifest = plugin.manifest
        require(manifest.id.matches(Regex("[A-Za-z0-9._-]{1,64}")))
        require(manifest.version.matches(Regex("[A-Za-z0-9._-]{1,32}")))
        check(manifest.id !in entries) { "Plugin duplicado" }
        val principal = Principal("plugin:${manifest.id}@${manifest.version}")
        permissions.register(principal, manifest.permissions)
        entries[manifest.id] = Entry(plugin, principal)
        return principal
    }
    fun enable(id: String) {
        val entry = entries[id] ?: error("Plugin não registrado")
        if (entry.active) return
        for (capability in entry.plugin.manifest.permissions) permissions.require(entry.principal, capability)
        try { entry.plugin.start(PluginContext(entry.principal, permissions)); entry.active = true }
        catch (error: Exception) { runCatching { entry.plugin.stop() }; throw error }
    }
    fun disable(id: String) {
        val entry = entries[id] ?: return
        if (entry.active) { entry.active = false; entry.plugin.stop() }
    }
    /** Call after a grant changes. Active plugins lose execution as well as host API access. */
    fun enforceRevocations() {
        for ((id, entry) in entries) if (entry.active && entry.plugin.manifest.permissions.any { !permissions.has(entry.principal, it) }) disable(id)
    }
    override fun close() { for (id in entries.keys) runCatching { disable(id) } }
}
