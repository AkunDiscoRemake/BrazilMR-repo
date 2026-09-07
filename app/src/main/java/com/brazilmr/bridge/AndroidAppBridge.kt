package com.brazilmr.bridge

import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.view.Surface
import com.brazilmr.platform.LauncherApp

sealed class AppLaunchResult {
    data class Display(val displayId: Int) : AppLaunchResult()
    data class Unsupported(val reason: String) : AppLaunchResult()
}

/** Official, private OWN_CONTENT_ONLY displays. Never asks for root, shell or hidden APIs. */
class AndroidAppBridge(private val context: Context) : AutoCloseable {
    private data class Entry(val app: LauncherApp, val display: VirtualDisplay, val surface: Surface, var width: Int, var height: Int)
    private val entries = HashMap<Int, Entry>()
    fun installedApps(): List<LauncherApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        return context.packageManager.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != context.packageName && it.activityInfo.exported && it.activityInfo.enabled }
            .map { LauncherApp(it.activityInfo.packageName, it.activityInfo.name, it.loadLabel(context.packageManager).toString().take(80)) }
            .distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
    }
    fun launchOnSurface(windowId: Int, app: LauncherApp, surface: Surface, width: Int = 1280, height: Int = 720): AppLaunchResult {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS)) return AppLaunchResult.Unsupported("Este Android não anuncia suporte a apps em displays secundários.")
        if (entries.size >= 3) return AppLaunchResult.Unsupported("Limite de três apps Android simultâneos para preservar memória e temperatura.")
        var display: VirtualDisplay? = null
        return try {
            display = context.getSystemService(DisplayManager::class.java).createVirtualDisplay(
                "Brazil MR · ${app.label}", width, height, 200, surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION)
                ?: return AppLaunchResult.Unsupported("O sistema recusou o display virtual.")
            val intent = launchIntent(app).addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            val displayId = display.display.displayId
            if (Build.VERSION.SDK_INT >= 29 && !context.getSystemService(ActivityManager::class.java).isActivityStartAllowedOnDisplay(context, displayId, intent)) {
                display.release(); return AppLaunchResult.Unsupported("Este app não pode ser iniciado neste display segundo o Android.")
            }
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
            context.startActivity(intent, options.toBundle())
            entries[windowId] = Entry(app, display, surface, width, height)
            AccessibilitySession.authorize(displayId, app.packageName)
            AppLaunchResult.Display(displayId)
        } catch (error: Exception) {
            display?.release()
            AppLaunchResult.Unsupported(error.message ?: "App/OEM não permite execução em display virtual.")
        }
    }
    fun launchOutside(app: LauncherApp): Boolean = runCatching { context.startActivity(launchIntent(app)); true }.getOrDefault(false)
    private fun launchIntent(app: LauncherApp) = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        .setComponent(ComponentName(app.packageName, app.className)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    fun setMinimized(windowId: Int, minimized: Boolean) { entries[windowId]?.let { it.display.surface = if (minimized) null else it.surface } }
    fun resize(windowId: Int, width: Int, height: Int) {
        entries[windowId]?.let {
            it.width=width.coerceIn(320,1920);it.height=height.coerceIn(240,1080)
            it.display.resize(it.width,it.height,200)
        }
    }
    fun gesture(windowId: Int, startX: Float, startY: Float, endX: Float, endY: Float, durationMillis: Long): String? {
        val entry = entries[windowId] ?: return "Esta janela não possui um display interativo. Compartilhamento é somente leitura."
        return AccessibilityBridgeService.connected?.gesture(entry.app.packageName, entry.display.display.displayId, startX, startY, endX, endY, durationMillis, entry.width, entry.height)
            ?: if (AccessibilityBridgeService.connected == null) "Ative a ponte de acessibilidade na Central de permissões." else null
    }
    fun close(windowId: Int) {
        entries.remove(windowId)?.let { AccessibilitySession.remove(it.display.display.displayId); it.display.release() }
    }
    override fun close() { for (id in entries.keys.toList()) close(id) }
}
