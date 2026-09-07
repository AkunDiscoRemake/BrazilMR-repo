package com.brazilmr.platform

import android.content.Context
import com.brazilmr.core.gesture.GestureSystem
import com.brazilmr.core.performance.XrSettings
import com.brazilmr.core.permission.*
import com.brazilmr.core.plugin.PluginSystem
import com.brazilmr.core.session.XrSession
import com.brazilmr.core.spatial.Scenario
import com.brazilmr.core.tracking.HandFrame
import com.brazilmr.core.window.*
import com.brazilmr.lua.UiElementStore
import org.json.JSONArray

enum class Page(val title: String, val icon: String) {
    HOME("Home", "home"), APPS("Apps", "grid"), WINDOWS("Janelas", "windows"), TRACKING("Tracking", "hand"),
    SETTINGS("Configurações", "settings"), PERMISSIONS("Permissões", "shield"), DEVELOPER("Developer", "code"), NOTIFICATIONS("Notificações", "bell")
}
data class Notice(val title: String, val message: String, val time: Long = System.currentTimeMillis())
data class LauncherApp(val packageName: String, val className: String, val label: String)
data class ScriptApp(val id: String, val title: String, val type: AppType, val source: String, val requested: Set<Capability>) {
    val principal = Principal.script(id, source)
}

/** Owner: main thread. Renderer and inference receive snapshots, not this mutable state. */
class PlatformState(context: Context) {
    val settingsStore = SettingsStore(context)
    var settings: XrSettings = settingsStore.read()
    val session = XrSession()
    val windows = WindowManagerXR()
    val permissions = PermissionManager(AndroidGrantStore(context))
    val plugins = PluginSystem(permissions)
    val hands = HandFrame()
    val gestures = GestureSystem()
    val elements = UiElementStore()
    val scenario = Scenario()
    val notices = ArrayList<Notice>()
    val installedApps = ArrayList<LauncherApp>()
    val scripts = ArrayList<ScriptApp>()
    val logs = ArrayList<String>()
    var phoneTools = false
    var page = Page.HOME
    var appPage = 0
    var settingsTab = 0
    var selectedScript = 0
    var quickSettings = false
    var cameraGranted = false
    var cameraActive = false
    var trackingStatus = "Parado"
    var spatialStatus = "3DoF · sensores"
    var sensorAvailable = false
    var accessibilityEnabled = false
    var accessibilityConsent = false
    var captureActive = false
    var fps = 0
    var thermalStatus = 0
    var effectiveTrackingFps = 0
    var effectiveRenderScale = .85f
    var batteryPercent = -1
    var dirty = true
    var developerSource = ""
    var developerType = AppType.WINDOW
    val sessionPrefs = context.getSharedPreferences("brazilmr.session.v1", Context.MODE_PRIVATE)
    init {
        val clock = windows.open("brazilmr.clock", "Agora", content = WindowContent.CLOCK)
        com.brazilmr.core.spatial.HeadsetLayout.clock(clock.pose)
        val manifests = JSONArray(context.assets.open("examples/manifests.json").bufferedReader().use { it.readText() })
        for (i in 0 until manifests.length()) {
            val j = manifests.getJSONObject(i); val declarations = j.getJSONArray("permissions")
            val requested = (0 until declarations.length()).mapNotNull { n -> Capability.entries.firstOrNull { it.wireName == declarations.getString(n) } }.toSet()
            val script = ScriptApp(j.getString("id"), j.getString("title"), AppType.valueOf(j.getString("type")), context.assets.open(j.getString("entry")).bufferedReader().use { it.readText() }, requested)
            scripts.add(script); permissions.register(script.principal, requested)
        }
        runCatching {
            sessionPrefs.getString("draft.source", null)?.let { source ->
                require(source.toByteArray(Charsets.UTF_8).size <= 65536)
                val type = AppType.valueOf(sessionPrefs.getString("draft.type", "WINDOW")!!)
                val requested = sessionPrefs.getString("draft.capabilities", "")!!.split(',')
                    .mapNotNull { name -> Capability.entries.firstOrNull { it.name == name } }.toSet()
                val draft = ScriptApp("developer.local", "Meu experimento", type, source, requested)
                scripts.add(draft); permissions.register(draft.principal, requested)
                selectedScript = scripts.lastIndex
            }
        }.onFailure { notice("Rascunho indisponível", "O rascunho salvo não pôde ser restaurado. Nenhum código foi executado.") }
        developerSource = scripts[selectedScript].source
        developerType = scripts[selectedScript].type
        notice("Bem-vindo ao seu espaço", "Câmera e integrações são opcionais. Você decide o que o Brazil MR pode acessar.")
    }
    fun saveDeveloperDraft(source: String, type: AppType, requested: Set<Capability>) {
        require(source.toByteArray(Charsets.UTF_8).size <= 65536) { "Limite de 64 KiB de código" }
        val draft = ScriptApp("developer.local", "Meu experimento", type, source, requested.toSet())
        val existing = scripts.indexOfFirst { it.id == draft.id }
        if (existing < 0) { scripts.add(draft); selectedScript = scripts.lastIndex }
        else { scripts[existing] = draft; selectedScript = existing }
        permissions.register(draft.principal, draft.requested)
        developerSource = source; developerType = type
        sessionPrefs.edit().putString("draft.source", source).putString("draft.type", type.name)
            .putString("draft.capabilities", requested.joinToString(",") { it.name }).apply()
        dirty = true
    }
    fun notice(title: String, message: String) {
        if (notices.size >= 50) notices.removeAt(notices.lastIndex)
        notices.add(0, Notice(title, message)); dirty = true
    }
    fun log(message: String) {
        if (logs.size >= 80) logs.removeAt(0)
        logs.add(message.take(512)); dirty = true
    }
    fun saveSettings(value: XrSettings) { settings = value; settingsStore.write(value); dirty = true }
    fun navigate(value: Page) { page = value; quickSettings = false; dirty = true }
}
