package com.brazilmr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.brazilmr.core.performance.XrSettings
import com.brazilmr.bridge.AccessibilitySession
import com.brazilmr.core.permission.Capability
import com.brazilmr.core.window.WindowContent
import com.brazilmr.core.window.AppType
import com.brazilmr.platform.*
import com.brazilmr.ui.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiSceneTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun bitmap() = Bitmap.createBitmap(1600,900,Bitmap.Config.ARGB_8888)
    @Test fun allPagesHaveInteractiveNativeLayoutAndCanRenderWithoutCamera() {
        val state=PlatformState(context);val scene=XrUiScene(state,Actions());val bitmap=bitmap();val canvas=Canvas(bitmap)
        for(page in Page.entries) {
            state.page=page;scene.draw(canvas)
            assertTrue(scene.visibleTargets.any { it.label==page.title })
            for(target in scene.visibleTargets) {
                assertTrue(target.label.isNotBlank())
                assertTrue(target.rect.width()>0 && target.rect.height()>0)
                assertTrue(target.rect.left.isFinite() && target.rect.top.isFinite())
            }
            val output=File("build/reports/ui/${page.name.lowercase()}.png");output.parentFile.mkdirs()
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        }
        bitmap.recycle()
    }
    @Test fun hiddenUiHasAccessibleRecoveryAndNoClickTargetsFromPreviousPage() {
        val state=PlatformState(context);val scene=XrUiScene(state,Actions());val bitmap=bitmap()
        scene.draw(Canvas(bitmap));state.session.setUiVisible(false);scene.draw(Canvas(bitmap))
        assertEquals(1,scene.visibleTargets.size)
        assertTrue(scene.activate(scene.visibleTargets.single().id));assertTrue(state.session.uiVisible)
        bitmap.recycle()
    }
    @Test fun externalSurfaceApertureIsOccludedByFrontNativeWindow() {
        val state=PlatformState(context)
        for(window in state.windows.windows.toList()) state.windows.close(window.id)
        val external=state.windows.open("external","External",content=WindowContent.ANDROID)
        external.displayId=8;state.windows.move(external.id,.02f,.02f);state.windows.resize(external.id,.8f,.9f)
        val front=state.windows.open("notes","Notes",content=WindowContent.NOTES)
        state.windows.move(front.id,.52f,.2f);state.windows.resize(front.id,.42f,.7f)
        val scene=XrUiScene(state,Actions());val bitmap=bitmap();scene.draw(Canvas(bitmap))
        val backRect=XrUiScene.contentBounds(external);val frontRect=XrUiScene.contentBounds(front)
        assertEquals(0,Color.alpha(bitmap.getPixel((backRect.left+20).toInt(),(backRect.top+30).toInt())))
        assertEquals(255,Color.alpha(bitmap.getPixel((frontRect.left+25).toInt(),(frontRect.top+30).toInt())))
        val target=scene.hitTest(frontRect.centerX(),frontRect.centerY())
        assertEquals(front.id,target?.windowId)
        bitmap.recycle()
    }
    @Test fun quickSettingsBlocksUnderlyingWindowInput() {
        val state=PlatformState(context);state.quickSettings=true
        val scene=XrUiScene(state,Actions());val bitmap=bitmap();scene.draw(Canvas(bitmap))
        assertTrue(scene.hitTest(950f,240f)?.key?.startsWith("quick.")==true)
        assertTrue(scene.visibleTargets.all { it.key.startsWith("quick.") })
        bitmap.recycle()
    }
    @Test fun settingsRoundTripPreservesFilterAndRendererParameters() {
        val store=SettingsStore(context);val previous=store.read()
        val next=previous.copy(sbs=true,ipdMm=67f,uiDistance=2.3f,renderScale=.65f,trackingWidth=320)
        store.write(next);assertEquals(next,store.read());store.write(previous)
    }
    @Test fun accessibilitySessionRequiresConsentAndExactAppDisplayPair() {
        AccessibilitySession.clear();AccessibilitySession.authorize(7,"app.example")
        assertFalse(AccessibilitySession.permits(7,"app.example"))
        AccessibilitySession.userConsented=true
        assertTrue(AccessibilitySession.permits(7,"app.example"))
        assertFalse(AccessibilitySession.permits(0,"app.example"))
        assertFalse(AccessibilitySession.permits(7,"other.app"))
        AccessibilitySession.remove(7);assertFalse(AccessibilitySession.permits(7,"app.example"))
        AccessibilitySession.clear();assertFalse(AccessibilitySession.userConsented)
    }
    @Test fun developerDraftPersistsWithoutExecutionOrImplicitGrants() {
        val state=PlatformState(context)
        state.saveDeveloperDraft("print('saved draft')",AppType.GAME,setOf(Capability.SCENARIO))
        val restored=PlatformState(context)
        val draft=restored.scripts[restored.selectedScript]
        assertEquals("print('saved draft')",draft.source)
        assertEquals(AppType.GAME,draft.type)
        assertFalse(restored.permissions.has(draft.principal,Capability.SCENARIO))
        assertTrue(restored.windows.windows.none { it.content==WindowContent.LUA })
        restored.sessionPrefs.edit().remove("draft.source").remove("draft.type").remove("draft.capabilities").commit()
    }
    private class Actions : UiActions {
        override fun requestCamera()=Unit
        override fun updateSettings(settings: XrSettings)=Unit
        override fun toggleMode()=Unit
        override fun recenter()=Unit
        override fun hideUi()=Unit
        override fun openBuiltin(content: WindowContent)=Unit
        override fun openAndroid(app: LauncherApp)=Unit
        override fun runScript(app: ScriptApp)=Unit
        override fun stopScripts()=Unit
        override fun editScript()=Unit
        override fun selectScript(index: Int)=Unit
        override fun showDocumentation()=Unit
        override fun changeCapability(app: ScriptApp,capability: Capability)=Unit
        override fun requestAccessibility()=Unit
        override fun requestCapture()=Unit
        override fun closeWindow(id: Int)=Unit
        override fun minimizeWindow(id: Int)=Unit
        override fun windowResized(id: Int)=Unit
        override fun editNotes(id: Int)=Unit
        override fun clickLuaElement(id: Int)=Unit
        override fun windowPointer(id: Int,x: Float,y: Float,action: String,source: String)=Unit
        override fun externalGesture(id: Int,x0: Float,y0: Float,x1: Float,y1: Float,duration: Long)=Unit
    }
}
