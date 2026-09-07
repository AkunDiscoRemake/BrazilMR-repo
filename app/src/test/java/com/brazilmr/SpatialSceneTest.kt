package com.brazilmr

import android.content.Context
import android.graphics.*
import androidx.test.core.app.ApplicationProvider
import com.brazilmr.core.spatial.*
import com.brazilmr.core.window.*
import com.brazilmr.platform.*
import com.brazilmr.render.RenderFrame
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
class SpatialSceneTest {
    private val context: Context get()=ApplicationProvider.getApplicationContext()
    @Test fun startupIsSbsAndCameraDominatesNotDesktop() {
        val state=PlatformState(context);val ui=SpatialUiScene(state,UiSceneTest.Actions());val frame=RenderFrame()
        ui.prepare(frame)
        assertTrue(state.settings.sbs);assertFalse(state.phoneTools)
        assertEquals(2,frame.panels.count)
        assertTrue(frame.panels.ids.take(frame.panels.count).contains(HeadsetLayout.DOCK))
        assertEquals(-1,frame.panels.hit(SpatialRay(),FloatArray(3)))
        assertTrue(ui.targets.none { it.key.contains("nav.") })
    }
    @Test fun windowsAreSeparateTexturesWithIndependentPositionsAndDepth() {
        val state=PlatformState(context)
        val a=state.windows.open("notes","Notas",content=WindowContent.NOTES)
        val b=state.windows.open("diagnostics","Diagnóstico",content=WindowContent.DIAGNOSTICS)
        state.windows.place(a.id,-.65f,0f,-1.6f,18f);state.windows.place(b.id,.7f,0f,-2.2f,-18f)
        val ui=SpatialUiScene(state,UiSceneTest.Actions());val frame=RenderFrame();ui.prepare(frame)
        val ia=frame.panels.indexOf(a.id);val ib=frame.panels.indexOf(b.id)
        assertNotSame(frame.panelPixels[ia],frame.panelPixels[ib])
        assertNotEquals(frame.panels.poses[ia].z,frame.panels.poses[ib].z)
        assertTrue(frame.panels.poses[ia].yaw>0);assertTrue(frame.panels.poses[ib].yaw<0)
    }
    @Test fun menuIsOneRequestedWorldSurfaceAndNeverAFullscreenShell() {
        val state=PlatformState(context);val ui=SpatialUiScene(state,UiSceneTest.Actions());val frame=RenderFrame()
        ui.openMenu(SpatialMenu.APPS);ui.prepare(frame)
        assertEquals(3,frame.panels.count)
        val index=frame.panels.indexOf(HeadsetLayout.MENU)
        assertTrue(index>=0);assertTrue(frame.panels.poses[index].width<1f)
        ui.openMenu(SpatialMenu.CLOSED);ui.prepare(frame);assertEquals(-1,frame.panels.indexOf(HeadsetLayout.MENU))
    }
    @Test fun captureAndReprojectionSnapshots() {
        val state=PlatformState(context);val ui=SpatialUiScene(state,UiSceneTest.Actions());val frame=RenderFrame()
        ui.prepare(frame);reference("headset-home",frame)
        val notes=state.windows.open("notes","Notas no ambiente",content=WindowContent.NOTES)
        notes.text="Suas ideias, no ambiente.\n\nMova esta janela pela alça."
        val diagnostics=state.windows.open("diag","Diagnóstico",content=WindowContent.DIAGNOSTICS)
        state.windows.place(diagnostics.id,.72f,-.02f,-2.05f,-20f)
        state.fps=60;state.effectiveTrackingFps=24;state.spatialStatus="Referência de geometria · sem câmera"
        state.dirty=true;ui.prepare(frame);reference("headset-windows",frame)
        ui.openMenu(SpatialMenu.SETTINGS);ui.prepare(frame);reference("headset-menu",frame)
    }
    /** Uses the real Canvas textures and core per-eye perspective. Test-room background is NOT camera footage. */
    private fun reference(name: String,frame: RenderFrame) {
        val image=Bitmap.createBitmap(1920,1080,Bitmap.Config.ARGB_8888);val canvas=Canvas(image)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val p=frame.projection.apply { spatial=true;sbs=true;eyeAspect=960f/1080;fovDegrees=75f }
        val world=FloatArray(3);val clip=FloatArray(4);val source=FloatArray(8);val destination=FloatArray(8)
        val acquired=ArrayList<Pair<Int,Int>>()
        for(i in 0 until frame.panels.count) { val slot=frame.panelPixels[i]!!.beginRead();if(slot>=0)acquired.add(i to slot) }
        for(eye in 0..1) {
            canvas.save();canvas.translate(eye*960f,0f);canvas.clipRect(0,0,960,1080)
            paint.color=0xff919995.toInt();canvas.drawRect(0f,0f,960f,680f,paint)
            paint.color=0xff727975.toInt();canvas.drawRect(0f,680f,960f,1080f,paint)
            paint.color=0xffaeb7b2.toInt();canvas.drawRect(240f,130f,710f,556f,paint)
            paint.color=0xff666f68.toInt();paint.strokeWidth=3f
            canvas.drawLine(480f,130f,480f,556f,paint);canvas.drawLine(240f,350f,710f,350f,paint)
            for(i in -4..4)canvas.drawLine(480f+i*40,680f,480f+i*220,1080f,paint)
            for(y in listOf(730f,820f,960f))canvas.drawLine(0f,y,960f,y,paint)
            val ordered=acquired.sortedBy { frame.panels.poses[it.first].z }
            for((i,slot) in ordered) {
                val bitmap=frame.panelPixels[i]!!.bitmaps[slot]
                for(c in 0..3) {
                    val u=if(c%2==0)0f else 1f;val v=if(c<2)0f else 1f
                    source[c*2]=u*bitmap.width;source[c*2+1]=v*bitmap.height
                    frame.panels.poses[i].world(u,v,world);p.projectWorld(world[0],world[1],world[2],eye,clip)
                    destination[c*2]=(clip[0]/clip[3]+1)*480;destination[c*2+1]=(1-clip[1]/clip[3])*540
                }
                val matrix=Matrix();assertTrue(matrix.setPolyToPoly(source,0,destination,0,4))
                paint.color=Color.WHITE;canvas.drawBitmap(bitmap,matrix,paint)
            }
            paint.color=Color.WHITE;paint.textSize=16f
            canvas.drawText("PROJEÇÃO DE REFERÊNCIA · FUNDO DE TESTE, NÃO CÂMERA",24f,1047f,paint)
            canvas.restore()
        }
        for((i,slot) in acquired)frame.panelPixels[i]!!.endRead(slot)
        val file=File("build/reports/ui/$name.png");file.parentFile.mkdirs();file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
        image.recycle()
    }
}
