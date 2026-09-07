package com.brazilmr.platform

import android.content.Context
import com.brazilmr.core.filter.OneEuroConfig
import com.brazilmr.core.performance.*
import com.brazilmr.core.permission.*
import org.json.JSONObject

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("brazilmr.settings.v1", Context.MODE_PRIVATE)
    fun read(): XrSettings = runCatching {
        val j = JSONObject(prefs.getString("settings", "{}")!!); val d = XrSettings()
        val migrated=j.optInt("spatialProfile",0)>=2
        XrSettings(
            trackingEnabled = j.optBoolean("trackingEnabled", d.trackingEnabled),
            trackingFps = j.optInt("trackingFps", d.trackingFps), trackingWidth = j.optInt("trackingWidth", d.trackingWidth),
            rightHand = j.optBoolean("rightHand", true), leftHand = j.optBoolean("leftHand", true), swapHands = j.optBoolean("swapHands", false),
            filter = OneEuroConfig(j.optDouble("cutoff", d.filter.minimumCutoff.toDouble()).toFloat(), j.optDouble("beta", d.filter.beta.toDouble()).toFloat(), j.optDouble("derivative", 1.0).toFloat()),
            sbs = if(migrated) j.optBoolean("sbs", true) else true,
            gazeEnabled=j.optBoolean("gaze",true),lensShift=j.optDouble("lensShift",0.0).toFloat(),lensVertical=j.optDouble("lensVertical",0.0).toFloat(),lensDistortion=j.optDouble("lensDistortion",0.0).toFloat(), ipdMm = j.optDouble("ipd", 63.0).toFloat(), fovDegrees = j.optDouble("fov", 75.0).toFloat(),
            renderScale = j.optDouble("renderScale", .85).toFloat(), renderWidth = j.optInt("renderWidth", 1920), targetFps = j.optInt("fps", 60),
            frontCamera = if(migrated) j.optBoolean("frontCamera", false) else false, spatialTracking = if(migrated) j.optBoolean("spatialTracking", true) else true, passthrough = if(migrated) j.optBoolean("passthrough", true) else true,
            uiScale = j.optDouble("uiScale", 1.0).toFloat(), uiDistance = j.optDouble("uiDistance", 1.7).toFloat(), uiOpacity = j.optDouble("uiOpacity", .96).toFloat(),
            uiOffsetX = j.optDouble("uiX", 0.0).toFloat(), uiOffsetY = j.optDouble("uiY", 0.0).toFloat(),
            performanceMode = PerformanceMode.valueOf(j.optString("performance", "BALANCED")), dynamicResolution = j.optBoolean("dynamicResolution", true),
        )
    }.getOrElse { XrSettings() }
    fun write(s: XrSettings) {
        val j = JSONObject().put("spatialProfile",2).put("gaze",s.gazeEnabled).put("lensShift",s.lensShift).put("lensVertical",s.lensVertical).put("lensDistortion",s.lensDistortion)
            .put("trackingEnabled", s.trackingEnabled).put("trackingFps", s.trackingFps).put("trackingWidth", s.trackingWidth)
            .put("rightHand", s.rightHand).put("leftHand", s.leftHand).put("swapHands", s.swapHands)
            .put("cutoff", s.filter.minimumCutoff).put("beta", s.filter.beta).put("derivative", s.filter.derivativeCutoff)
            .put("sbs", s.sbs).put("ipd", s.ipdMm).put("fov", s.fovDegrees).put("renderScale", s.renderScale).put("renderWidth", s.renderWidth).put("fps", s.targetFps)
            .put("frontCamera", s.frontCamera).put("spatialTracking", s.spatialTracking).put("passthrough", s.passthrough)
            .put("uiScale", s.uiScale).put("uiDistance", s.uiDistance).put("uiOpacity", s.uiOpacity).put("uiX", s.uiOffsetX).put("uiY", s.uiOffsetY)
            .put("performance", s.performanceMode.name).put("dynamicResolution", s.dynamicResolution)
        prefs.edit().putString("settings", j.toString()).apply()
    }
}
class AndroidGrantStore(context: Context) : GrantStore {
    private val prefs = context.getSharedPreferences("brazilmr.capabilities.v1", Context.MODE_PRIVATE)
    private fun key(principal: Principal, capability: Capability) = "${principal.id}:${capability.wireName}"
    override fun granted(principal: Principal, capability: Capability) = prefs.getBoolean(key(principal, capability), false)
    override fun write(principal: Principal, capability: Capability, granted: Boolean) {
        if (granted) prefs.edit().putBoolean(key(principal, capability), true).apply()
        else prefs.edit().remove(key(principal, capability)).apply()
    }
}
