package com.brazilmr.render

import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20.*
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.camera.core.SurfaceRequest
import com.brazilmr.core.spatial.SpatialProjection
import com.brazilmr.core.spatial.PanelSnapshot
import com.brazilmr.core.spatial.PanelPose
import com.brazilmr.spatial.ArCoreEnvironment
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.min

/** GLES 2 renderer: one UI texture, at most four app surfaces, two eye viewports, no scene-engine overhead. */
class SBSRenderer(
    private val view: GLSurfaceView,
    private val ui: UiTextureExchange,
    private val ar: ArCoreEnvironment,
    private val onReady: () -> Unit,
    private val onLost: () -> Unit,
    private val onError: (String) -> Unit,
) : GLSurfaceView.Renderer {
    private val main = Handler(Looper.getMainLooper())
    private val mailbox = RenderFrame()
    private val frame = RenderFrame()
    private val lastProjection = SpatialProjection()
    private val lastPanels=PanelSnapshot()
    private val world=FloatArray(3)
    private val panelTextures=HashMap<Int,PanelTexture>()
    private var depthBuffer=0
    private var panelShader: Shader?=null
    private var lensShader: Shader?=null
    @Volatile private var sensorTanX=.65f
    @Volatile private var sensorTanY=.49f
    private val projectionLock = Any()
    private val clip = FloatArray(4)
    private val vertices = floats(24)
    private val point = floats(4)
    private val lineVertices = floats(96)
    private val arUv = floats(8)
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private var textureShader: Shader? = null
    private var externalShader: Shader? = null
    private var colorShader: Shader? = null
    private var pointShader: Shader? = null
    private var cameraTexture: ExternalTexture? = null
    private var arTexture = 0
    private val appTextures = LinkedHashMap<Int, ExternalTexture>()
    private var uiTexture = 0
    private var hasUi = false
    private var targetTexture = 0
    private var framebuffer = 0
    private var targetWidth = 0; private var targetHeight = 0
    private var width = 1; private var height = 1
    private var initialized = false
    private var count = 0; private var fpsTime = 0L
    @Volatile var fps = 0; private set
    @Volatile var lastFrameMillis = 0f; private set
    @Volatile private var cameraGeometry = CameraGeometry()
    private data class CameraGeometry(val width: Int = 640, val height: Int = 480, val rotation: Int = 0, val crop: Rect = Rect(0, 0, 640, 480))

    fun publish(next: RenderFrame) { synchronized(mailbox) { mailbox.copyFrom(next) } }
    fun readProjection(into: SpatialProjection) { synchronized(projectionLock) { into.copyFrom(lastProjection) } }
    fun readPanels(into: PanelSnapshot) { synchronized(projectionLock) { into.copyFrom(lastPanels) } }
    fun setCameraTangents(x: Float,y: Float) { if(x>0 && y>0) { sensorTanX=x;sensorTanY=y } }
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            if (initialized) { cameraTexture?.release(false); for (texture in appTextures.values) texture.release(false); appTextures.clear(); main.post(onLost) }
            panelTextures.clear()
            textureShader = Shader(TEXTURE_VERTEX, TEXTURE_FRAGMENT)
            panelShader=Shader(TEXTURE_VERTEX,PANEL_FRAGMENT)
            lensShader=Shader(TEXTURE_VERTEX,LENS_FRAGMENT)
            externalShader = Shader(TEXTURE_VERTEX, EXTERNAL_FRAGMENT)
            colorShader = Shader(COLOR_VERTEX, COLOR_FRAGMENT)
            pointShader = Shader(POINT_VERTEX, POINT_FRAGMENT)
            cameraTexture = ExternalTexture()
            arTexture = texture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
            uiTexture = texture(GL_TEXTURE_2D); hasUi = false
            framebuffer = 0; depthBuffer=0; targetTexture = 0; targetWidth = 0; targetHeight = 0
            initialized = true; glDisable(GL_DEPTH_TEST); glDisable(GL_CULL_FACE)
            main.post(onReady)
        } catch (error: Exception) { initialized = false; main.post { onError(error.message ?: "OpenGL indisponível") } }
    }
    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) { this.width = width.coerceAtLeast(1); this.height = height.coerceAtLeast(1) }
    override fun onDrawFrame(gl: GL10?) {
        if (!initialized) return
        val started=System.nanoTime()
        try {
            synchronized(mailbox) { frame.copyFrom(mailbox) }
            val sbs=frame.projection.sbs
            val ratio=min(1f,frame.maxWidth.toFloat()/width)*frame.scale
            ensureTarget(((width*ratio).toInt()/2*2).coerceAtLeast(2),(height*ratio).toInt().coerceAtLeast(2))
            if(frame.immersive) uploadPanels() else uploadUi()
            cameraTexture?.update()
            val eyeWidth=if(sbs)targetWidth/2 else targetWidth
            frame.projection.eyeAspect=eyeWidth.toFloat()/targetHeight
            if(frame.projection.cameraAligned) cameraProjection(frame.projection)
            val arCamera=ar.update(arTexture,eyeWidth,targetHeight,frame.displayRotation,arUv,frame.projection)
            frame.panels.projection.copyFrom(frame.projection)
            synchronized(projectionLock) { lastProjection.copyFrom(frame.projection);lastPanels.copyFrom(frame.panels) }
            glBindFramebuffer(GL_FRAMEBUFFER,framebuffer);glDisable(GL_BLEND);glDepthMask(true)
            glClearColor(.014f,.010f,.025f,1f);glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
            if(frame.immersive) {
                for(i in 0 until frame.panels.count) if(frame.panelVideo[i]>=0)appTextures[frame.panelVideo[i]]?.update()
            } else for(i in 0 until frame.externalCount)appTextures[frame.externalIds[i]]?.update()
            for(eye in 0 until if(sbs)2 else 1) {
                glViewport(eye*eyeWidth,0,eyeWidth,targetHeight);glDisable(GL_DEPTH_TEST);glDisable(GL_BLEND)
                if(frame.camera && !frame.vr) {
                    if(arCamera) { fullscreenVertices(arUv);drawTextured(externalShader!!,arTexture,GLES11Ext.GL_TEXTURE_EXTERNAL_OES,identity,1f) }
                    else cameraTexture?.takeIf { it.hasImage }?.let {
                        cameraVertices(eyeWidth.toFloat()/targetHeight);drawTextured(externalShader!!,it.texture,GLES11Ext.GL_TEXTURE_EXTERNAL_OES,it.matrix,1f)
                    }
                }
                if(frame.immersive) {
                    glEnable(GL_DEPTH_TEST);glDepthFunc(GL_LEQUAL);glDepthMask(true)
                    drawObjects(eye);drawPanels(eye)
                } else {
                    drawObjects(eye)
                    for(i in 0 until frame.externalCount) {
                        val texture=appTextures[frame.externalIds[i]] ?: continue
                        if(!texture.hasImage)continue
                        val o=i*4;planeVertices(frame.externalRects[o],frame.externalRects[o+1],frame.externalRects[o+2],frame.externalRects[o+3],eye,true)
                        drawTextured(externalShader!!,texture.texture,GLES11Ext.GL_TEXTURE_EXTERNAL_OES,texture.matrix,1f)
                    }
                    glEnable(GL_BLEND);glBlendFunc(GL_ONE,GL_ONE_MINUS_SRC_ALPHA)
                    if(hasUi) { planeVertices(0f,0f,1f,1f,eye,false);drawTextured(textureShader!!,uiTexture,GL_TEXTURE_2D,identity,frame.opacity) }
                }
                glDisable(GL_BLEND);glDisable(GL_DEPTH_TEST)
                if(frame.cursorVisible)drawCursor(eye)
            }
            glBindFramebuffer(GL_FRAMEBUFFER,0);glDisable(GL_DEPTH_TEST);glDisable(GL_BLEND)
            if(sbs && frame.immersive) {
                for(eye in 0..1) {
                    glViewport(eye*(width/2),0,width/2,height);fullscreenVertices(null)
                    val shader=lensShader!!;glUseProgram(shader.program)
                    glUniform4f(shader.lens,eye.toFloat(),.5f+if(eye==0)frame.projection.lensShift else -frame.projection.lensShift,.5f+frame.projection.lensVertical,frame.projection.lensDistortion)
                    drawTextured(shader,targetTexture,GL_TEXTURE_2D,identity,1f)
                }
            } else { glViewport(0,0,width,height);fullscreenVertices(null);drawTextured(textureShader!!,targetTexture,GL_TEXTURE_2D,identity,1f) }
            count++;val now=System.nanoTime()
            if(now-fpsTime>=1_000_000_000L) { if(fpsTime!=0L)fps=(count*1_000_000_000L/(now-fpsTime)).toInt();count=0;fpsTime=now }
            lastFrameMillis=(now-started)/1_000_000f
        } catch(error: Exception) { initialized=false;main.post { onError(error.message ?: "Falha no renderer") } }
    }
    private class PanelTexture(val id: Int,var uploaded: Boolean=false)
    private fun uploadPanels() {
        val iterator=panelTextures.entries.iterator()
        while(iterator.hasNext()) {
            val entry=iterator.next()
            if(frame.panels.indexOf(entry.key)<0) { glDeleteTextures(1,intArrayOf(entry.value.id),0);iterator.remove() }
        }
        for(i in 0 until frame.panels.count) {
            val exchange=frame.panelPixels[i] ?: continue
            val texture=panelTextures.getOrPut(frame.panels.ids[i]) { PanelTexture(texture(GL_TEXTURE_2D)) }
            val slot=exchange.beginRead();if(slot<0)continue
            try {
                glBindTexture(GL_TEXTURE_2D,texture.id)
                if(texture.uploaded)GLUtils.texSubImage2D(GL_TEXTURE_2D,0,0,0,exchange.bitmaps[slot])
                else { GLUtils.texImage2D(GL_TEXTURE_2D,0,exchange.bitmaps[slot],0);texture.uploaded=true }
            } finally { exchange.endRead(slot) }
        }
    }
    private fun drawPanels(eye: Int) {
        for(i in 0 until frame.panels.count) {
            val texture=panelTextures[frame.panels.ids[i]] ?: continue
            if(!texture.uploaded)continue
            val pose=frame.panels.poses[i]
            val video=appTextures[frame.panelVideo[i]]
            if(video?.hasImage==true) {
                glDisable(GL_BLEND)
                surfaceVertices(pose,eye,16f/512,64f/384,496f/512,334f/384,true,0f)
                drawTextured(externalShader!!,video.texture,GLES11Ext.GL_TEXTURE_EXTERNAL_OES,video.matrix,1f)
            }
            glEnable(GL_BLEND);glBlendFunc(GL_ONE,GL_ONE_MINUS_SRC_ALPHA)
            surfaceVertices(pose,eye,0f,0f,1f,1f,false,.001f)
            drawTextured(panelShader!!,texture.id,GL_TEXTURE_2D,identity,frame.opacity)
        }
        glDisable(GL_BLEND)
    }
    private fun surfaceVertices(pose: PanelPose,eye: Int,left: Float,top: Float,right: Float,bottom: Float,flip: Boolean,offset: Float) {
        vertices.clear()
        for(i in 0..3) {
            pose.world(if(i%2==0)left else right,if(i<2)top else bottom,world,offset)
            frame.projection.projectWorld(world[0],world[1],world[2],eye,clip);vertices.put(clip)
            vertices.put(if(i%2==0)0f else 1f);vertices.put(if(flip) (if(i<2)1f else 0f) else (if(i<2)0f else 1f))
        }
        vertices.position(0)
    }
    /** Match virtual angular motion to the actual camera crop instead of inventing a 75-degree lens. */
    private fun cameraProjection(p: SpatialProjection) {
        val g=cameraGeometry;val sensorAspect=sensorTanX/sensorTanY;val bufferAspect=g.width.toFloat()/g.height
        var tx=sensorTanX*min(1f,bufferAspect/sensorAspect)*g.crop.width()/g.width
        var ty=sensorTanY*min(1f,sensorAspect/bufferAspect)*g.crop.height()/g.height
        if(g.rotation%180!=0) { val swap=tx;tx=ty;ty=swap }
        val aspect=tx/ty
        tx*=min(1f,p.eyeAspect/aspect);ty*=min(1f,aspect/p.eyeAspect)
        p.focalX=1f/tx.coerceAtLeast(.1f);p.focalY=1f/ty.coerceAtLeast(.1f);p.opticalX=0f;p.opticalY=0f
    }
    fun provideCameraSurface(request: SurfaceRequest) {
        val resolution = request.resolution
        request.setTransformationInfoListener({ task -> main.post(task) }) { info ->
            cameraGeometry = CameraGeometry(resolution.width, resolution.height, info.rotationDegrees, Rect(info.cropRect))
        }
        view.queueEvent {
            val camera = cameraTexture
            if (camera == null || !initialized) { request.willNotProvideSurface(); return@queueEvent }
            camera.surfaceTexture.setDefaultBufferSize(resolution.width, resolution.height)
            val surface = Surface(camera.surfaceTexture)
            request.provideSurface(surface, { task -> main.post(task) }) { surface.release() }
        }
    }
    fun createAppSurface(id: Int, width: Int, height: Int, callback: (Surface?) -> Unit) {
        view.queueEvent {
            if (!initialized || appTextures.size >= 4) { main.post { callback(null) }; return@queueEvent }
            appTextures.remove(id)?.release()
            val next = ExternalTexture(); next.surfaceTexture.setDefaultBufferSize(width, height); appTextures[id] = next
            main.post { callback(next.surface) }
        }
    }
    fun removeAppSurface(id: Int) { view.queueEvent { appTextures.remove(id)?.release() } }
    fun resizeAppSurface(id: Int, width: Int, height: Int) { view.queueEvent { appTextures[id]?.surfaceTexture?.setDefaultBufferSize(width, height) } }
    fun release() {
        view.queueEvent {
            cameraTexture?.release(); cameraTexture = null
            for (value in appTextures.values) value.release(); appTextures.clear()
            initialized = false
        }
    }
    private fun uploadUi() {
        val index = ui.beginRead()
        if (index < 0) return
        try {
            glBindTexture(GL_TEXTURE_2D, uiTexture)
            if (!hasUi) { GLUtils.texImage2D(GL_TEXTURE_2D, 0, ui.bitmaps[index], 0); hasUi = true }
            else GLUtils.texSubImage2D(GL_TEXTURE_2D, 0, 0, 0, ui.bitmaps[index])
        } finally { ui.endRead(index) }
    }
    private fun ensureTarget(w: Int, h: Int) {
        if (w == targetWidth && h == targetHeight && framebuffer != 0) return
        if (targetTexture != 0) glDeleteTextures(1, intArrayOf(targetTexture), 0)
        if (framebuffer != 0) glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        if(depthBuffer!=0)glDeleteRenderbuffers(1,intArrayOf(depthBuffer),0)
        targetTexture = texture(GL_TEXTURE_2D)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, null)
        val ids = IntArray(1); glGenFramebuffers(1, ids, 0); framebuffer = ids[0]
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, targetTexture, 0)
        glGenRenderbuffers(1,ids,0);depthBuffer=ids[0];glBindRenderbuffer(GL_RENDERBUFFER,depthBuffer)
        glRenderbufferStorage(GL_RENDERBUFFER,GL_DEPTH_COMPONENT16,w,h)
        glFramebufferRenderbuffer(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_RENDERBUFFER,depthBuffer)
        check(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE) { "Framebuffer não suportado" }
        targetWidth = w; targetHeight = h
    }
    private fun planeVertices(left: Float, top: Float, right: Float, bottom: Float, eye: Int, flipV: Boolean) {
        vertices.clear()
        for (i in 0..3) {
            val u = if (i % 2 == 0) left else right; val v = if (i < 2) top else bottom
            frame.projection.project(u, v, eye, clip); vertices.put(clip)
            vertices.put(if (i % 2 == 0) 0f else 1f)
            vertices.put(if (flipV) (if (i < 2) 1f else 0f) else (if (i < 2) 0f else 1f))
        }
        vertices.position(0)
    }
    private fun fullscreenVertices(uv: FloatBuffer?) {
        vertices.clear()
        for (i in 0..3) {
            vertices.put(if (i % 2 == 0) -1f else 1f); vertices.put(if (i < 2) 1f else -1f); vertices.put(0f); vertices.put(1f)
            vertices.put(uv?.get(i * 2) ?: if (i % 2 == 0) 0f else 1f)
            vertices.put(uv?.get(i * 2 + 1) ?: if (i < 2) 1f else 0f)
        }
        vertices.position(0)
    }
    private fun cameraVertices(destinationAspect: Float) {
        val geometry = cameraGeometry
        val crop = geometry.crop
        val rotated = geometry.rotation % 180 != 0
        val sourceAspect = if (rotated) crop.height().toFloat() / crop.width() else crop.width().toFloat() / crop.height()
        val sx = min(1f, destinationAspect / sourceAspect); val sy = min(1f, sourceAspect / destinationAspect)
        vertices.clear()
        for (i in 0..3) {
            val screenU = if (i % 2 == 0) 0f else 1f; val screenV = if (i < 2) 0f else 1f
            vertices.put(screenU * 2 - 1); vertices.put(1 - screenV * 2); vertices.put(0f); vertices.put(1f)
            var u = (screenU - .5f) * sx + .5f; val v = (screenV - .5f) * sy + .5f
            if (frame.mirror) u = 1 - u
            val rx: Float; val ry: Float
            when (geometry.rotation) { 90 -> { rx = v; ry = 1-u }; 180 -> { rx = 1-u; ry = 1-v }; 270 -> { rx = 1-v; ry = u }; else -> { rx = u; ry = v } }
            vertices.put((crop.left + rx * crop.width()) / geometry.width)
            vertices.put(1 - (crop.top + ry * crop.height()) / geometry.height)
        }
        vertices.position(0)
    }
    private fun drawTextured(shader: Shader, texture: Int, target: Int, matrix: FloatArray, alpha: Float) {
        glUseProgram(shader.program)
        vertices.position(0); glEnableVertexAttribArray(shader.position); glVertexAttribPointer(shader.position, 4, GL_FLOAT, false, 24, vertices)
        vertices.position(4); glEnableVertexAttribArray(shader.uv); glVertexAttribPointer(shader.uv, 2, GL_FLOAT, false, 24, vertices)
        glUniformMatrix4fv(shader.matrix, 1, false, matrix, 0); glUniform1f(shader.alpha, alpha)
        glActiveTexture(GL_TEXTURE0); glBindTexture(target, texture); glUniform1i(shader.sampler, 0)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        glDisableVertexAttribArray(shader.position); glDisableVertexAttribArray(shader.uv)
    }
    private fun drawCursor(eye: Int) {
        val shader = pointShader!!
        if(frame.immersive) frame.projection.projectWorld(frame.cursorWorldX,frame.cursorWorldY,frame.cursorWorldZ,eye,clip) else frame.projection.project(frame.cursorX, frame.cursorY, eye, clip)
        if (clip[3] <= 0) return
        point.clear(); point.put(clip); point.position(0)
        glUseProgram(shader.program); glEnableVertexAttribArray(shader.position)
        glVertexAttribPointer(shader.position, 4, GL_FLOAT, false, 0, point)
        val pressed = frame.cursorState == 2
        glUniform4f(shader.color, if (pressed) 1f else .72f, if (pressed) .95f else .52f, 1f, 1f)
        glUniform1f(shader.size, (if(frame.dwellProgress>0)16f else if(frame.cursorState==1)10f else 7f)*frame.scale)
        glUniform1f(shader.progress,frame.dwellProgress)
        glDrawArrays(GL_POINTS, 0, 1); glDisableVertexAttribArray(shader.position)
    }
    private fun drawObjects(eye: Int) {
        if (!frame.projection.spatial || frame.objectCount == 0) return
        val shader = colorShader!!; glUseProgram(shader.program); glLineWidth(1.5f)
        for (index in 0 until frame.objectCount) {
            val offset = index * 4; val x = frame.objects[offset]; val y = frame.objects[offset+1]; val z = frame.objects[offset+2]; val size = frame.objects[offset+3] / 2
            lineVertices.clear()
            for (edge in EDGES) {
                frame.projection.projectWorld(x + if (edge and 1 == 0) -size else size, y + if (edge and 2 == 0) -size else size, z + if (edge and 4 == 0) -size else size, eye, clip)
                lineVertices.put(clip)
            }
            lineVertices.position(0); glEnableVertexAttribArray(shader.position); glVertexAttribPointer(shader.position, 4, GL_FLOAT, false, 0, lineVertices)
            val color = frame.objectColors[index]
            glUniform4f(shader.color, ((color shr 16) and 255)/255f, ((color shr 8) and 255)/255f, (color and 255)/255f, 1f)
            glDrawArrays(GL_LINES, 0, EDGES.size)
        }
        glDisableVertexAttribArray(shader.position)
    }
    private inner class ExternalTexture {
        val texture = texture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        val surfaceTexture = SurfaceTexture(texture)
        val surface = Surface(surfaceTexture)
        val matrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
        private val pending = AtomicBoolean(false)
        var hasImage = false; private set
        init { surfaceTexture.setOnFrameAvailableListener({ pending.set(true) }, main) }
        fun update() {
            if (pending.getAndSet(false)) {
                surfaceTexture.updateTexImage(); surfaceTexture.getTransformMatrix(matrix); hasImage = true
            }
        }
        fun release(deleteTexture: Boolean = true) {
            surfaceTexture.setOnFrameAvailableListener(null); surface.release(); surfaceTexture.release()
            if (deleteTexture) glDeleteTextures(1, intArrayOf(texture), 0)
        }
    }
    private class Shader(vertex: String, fragment: String) {
        val program: Int
        val position: Int; val uv: Int; val matrix: Int; val alpha: Int; val sampler: Int; val color: Int; val size: Int;val lens: Int;val progress: Int
        init {
            val v = compile(GL_VERTEX_SHADER, vertex); val f = compile(GL_FRAGMENT_SHADER, fragment)
            program = glCreateProgram(); glAttachShader(program, v); glAttachShader(program, f); glLinkProgram(program)
            val success = IntArray(1); glGetProgramiv(program, GL_LINK_STATUS, success, 0)
            check(success[0] != 0) { glGetProgramInfoLog(program) }
            glDeleteShader(v); glDeleteShader(f)
            position = glGetAttribLocation(program, "aPosition"); uv = glGetAttribLocation(program, "aUv")
            matrix = glGetUniformLocation(program, "uMatrix"); alpha = glGetUniformLocation(program, "uAlpha"); sampler = glGetUniformLocation(program, "uTexture")
            color = glGetUniformLocation(program, "uColor"); size = glGetUniformLocation(program, "uSize");lens=glGetUniformLocation(program,"uLens");progress=glGetUniformLocation(program,"uProgress")
        }
        private fun compile(type: Int, source: String): Int {
            val shader = glCreateShader(type); glShaderSource(shader, source); glCompileShader(shader)
            val success = IntArray(1); glGetShaderiv(shader, GL_COMPILE_STATUS, success, 0)
            check(success[0] != 0) { glGetShaderInfoLog(shader) }; return shader
        }
    }
    companion object {
        private val EDGES = intArrayOf(0,1,1,3,3,2,2,0,4,5,5,7,7,6,6,4,0,4,1,5,2,6,3,7)
        private fun floats(size: Int) = ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        private fun texture(target: Int): Int {
            val ids = IntArray(1); glGenTextures(1, ids, 0); glBindTexture(target, ids[0])
            glTexParameteri(target, GL_TEXTURE_MIN_FILTER, GL_LINEAR); glTexParameteri(target, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            glTexParameteri(target, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE); glTexParameteri(target, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
            return ids[0]
        }
        private const val TEXTURE_VERTEX = "attribute vec4 aPosition; attribute vec2 aUv; uniform mat4 uMatrix; varying vec2 vUv; void main(){gl_Position=aPosition; vUv=(uMatrix*vec4(aUv,0.,1.)).xy;}"
        private const val TEXTURE_FRAGMENT = "precision mediump float; uniform sampler2D uTexture; uniform float uAlpha; varying vec2 vUv; void main(){gl_FragColor=texture2D(uTexture,vUv)*uAlpha;}"
        private const val EXTERNAL_FRAGMENT = "#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES uTexture; uniform float uAlpha; varying vec2 vUv; void main(){gl_FragColor=texture2D(uTexture,vUv)*uAlpha;}"
        private const val COLOR_VERTEX = "attribute vec4 aPosition; void main(){gl_Position=aPosition;}"
        private const val COLOR_FRAGMENT = "precision mediump float; uniform vec4 uColor; void main(){gl_FragColor=uColor;}"
        private const val POINT_VERTEX = "attribute vec4 aPosition; uniform float uSize; void main(){gl_Position=aPosition; gl_PointSize=uSize;}"
        private const val PANEL_FRAGMENT = "precision mediump float; uniform sampler2D uTexture; uniform float uAlpha; varying vec2 vUv; void main(){vec4 c=texture2D(uTexture,vUv)*uAlpha; if(c.a<.01)discard; gl_FragColor=c;}"
        private const val LENS_FRAGMENT = "precision mediump float; uniform sampler2D uTexture; uniform vec4 uLens; varying vec2 vUv; void main(){vec2 p=(vec2(vUv.x,1.-vUv.y)-uLens.yz)*2.; vec2 s=.5+p*(1.+uLens.w*dot(p,p))*.5; if(s.x<0.||s.x>1.||s.y<0.||s.y>1.){gl_FragColor=vec4(0.,0.,0.,1.);}else{gl_FragColor=texture2D(uTexture,vec2((uLens.x+s.x)*.5,1.-s.y));}}"
        private const val POINT_FRAGMENT = "precision mediump float; uniform vec4 uColor; uniform float uProgress; void main(){vec2 p=gl_PointCoord-vec2(.5);float r=length(p);if(r>.5)discard;if(uProgress>0.){float a=mod(atan(p.y,p.x)+1.5707963+6.2831853,6.2831853)/6.2831853;if(r>.34&&r<.46&&a<uProgress){gl_FragColor=uColor;return;}if(r>.14)discard;}gl_FragColor=r<.24?vec4(1.):uColor;}"
    }
}
