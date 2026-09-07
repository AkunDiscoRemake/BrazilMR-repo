package com.brazilmr.spatial

import android.app.Activity
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.view.Surface
import com.brazilmr.core.spatial.Quaternion
import com.brazilmr.core.spatial.SpatialProjection
import com.brazilmr.tracking.CameraHandTrackingManager
import com.google.ar.core.*
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.*

/** Exclusive camera provider: CameraX must be unbound before resume(). No simultaneous camera pipelines. */
class ArCoreEnvironment(
    private val context: Context,
    private val hands: CameraHandTrackingManager,
    private val onStatus: (String) -> Unit,
    private val onFailure: (String) -> Unit,
) : AutoCloseable {
    private var session: Session? = null
    private var installRequested = false
    private var lastTexture = -1
    private var geometryWidth = 0
    private var geometryHeight = 0
    private var geometryRotation = -1
    private var lastStatus = ""
    private var sensorOrientation = 90
    private val ndc = buffer(floatArrayOf(-1f, 1f, 1f, 1f, -1f, -1f, 1f, -1f))
    private val rotation = FloatArray(4)
    private val cameraProjection=FloatArray(16)
    private val translation = FloatArray(3)
    private val originPosition = FloatArray(3)
    private val transformed = FloatArray(3)
    private val origin = Quaternion()
    private var originAnchor: Anchor?=null
    private val anchorRotation=FloatArray(4)
    private var centered = false
    @Volatile var active = false; private set
    @Synchronized fun start(activity: Activity): Boolean {
        if (active) return true
        return try {
            val apk = ArCoreApk.getInstance()
            val availability = apk.checkAvailability(context)
            if (!availability.isSupported) { status(if (availability.isTransient) "Verificando ARCore · tente novamente" else "ARCore não disponível · fallback 3DoF"); return false }
            if (apk.requestInstall(activity, !installRequested) == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                installRequested = true; status("Instalação do ARCore solicitada"); return false
            }
            val next = Session(context)
            try {
                val config = Config(next).apply {
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    focusMode = Config.FocusMode.AUTO
                    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    // Depth is deliberately off: spatial pose does not require a second expensive pipeline.
                    depthMode = Config.DepthMode.DISABLED
                }
                next.configure(config)
                sensorOrientation = context.getSystemService(CameraManager::class.java)
                    .getCameraCharacteristics(next.cameraConfig.cameraId).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                next.resume(); session = next; lastTexture = -1; geometryRotation = -1; centered = false; active = true
                status("ARCore · inicializando tracking espacial"); true
            } catch (error: Exception) { next.close(); throw error }
        } catch (error: LinkageError) { status("ARCore sem biblioteca compatível · fallback 3DoF"); false }
        catch (error: Exception) { status("ARCore indisponível · ${error.message ?: "fallback 3DoF"}"); false }
    }
    @Synchronized fun recenter() { centered = false }
    /** Runs on GL thread; returns transformed passthrough UVs and a genuine camera pose when tracked. */
    @Synchronized fun update(texture: Int, width: Int, height: Int, displayRotation: Int, uv: FloatBuffer, projection: SpatialProjection): Boolean {
        val current = session ?: return false
        if (!active) return false
        return try {
            if (lastTexture != texture) { current.setCameraTextureName(texture); lastTexture = texture }
            if (displayRotation != geometryRotation || width != geometryWidth || height != geometryHeight) {
                current.setDisplayGeometry(displayRotation, width, height)
                geometryWidth = width; geometryHeight = height; geometryRotation = displayRotation
            }
            val frame = current.update()
            ndc.position(0); uv.position(0)
            frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, ndc, Coordinates2d.TEXTURE_NORMALIZED, uv)
            uv.position(0)
            val camera = frame.camera
            if(projection.cameraAligned) {
                camera.getProjectionMatrix(cameraProjection,0,.05f,100f)
                projection.focalX=cameraProjection[0];projection.focalY=cameraProjection[5]
                projection.opticalX=cameraProjection[8];projection.opticalY=cameraProjection[9]
            }
            if (camera.trackingState == TrackingState.TRACKING) {
                val pose = camera.displayOrientedPose
                pose.getRotationQuaternion(rotation, 0); pose.getTranslation(translation, 0)
                if (!centered) {
                    val yaw=atan2(2*(rotation[0]*rotation[2]+rotation[3]*rotation[1]),1-2*(rotation[0]*rotation[0]+rotation[1]*rotation[1]))
                    origin.set(0f,sin(yaw/2),0f,cos(yaw/2));translation.copyInto(originPosition)
                    anchorRotation[0]=origin.x;anchorRotation[1]=origin.y;anchorRotation[2]=origin.z;anchorRotation[3]=origin.w
                    originAnchor?.detach();originAnchor=current.createAnchor(Pose(originPosition,anchorRotation));centered=true
                }
                originAnchor?.takeIf { it.trackingState==TrackingState.TRACKING }?.pose?.let { anchored ->
                    anchored.getTranslation(originPosition,0);anchored.getRotationQuaternion(anchorRotation,0)
                    origin.set(anchorRotation[0],anchorRotation[1],anchorRotation[2],anchorRotation[3])
                }
                val x = rotation[0]; val y = rotation[1]; val z = rotation[2]; val w = rotation[3]
                projection.orientation.set(origin.w*x-origin.x*w-origin.y*z+origin.z*y, origin.w*y+origin.x*z-origin.y*w-origin.z*x, origin.w*z-origin.x*y+origin.y*x-origin.z*w, origin.w*w+origin.x*x+origin.y*y+origin.z*z)
                origin.rotate(translation[0]-originPosition[0], translation[1]-originPosition[1], translation[2]-originPosition[2], transformed, true)
                projection.positionX = transformed[0]; projection.positionY = transformed[1]; projection.positionZ = transformed[2]
                status("ARCore · 6DoF / âncora local")
            } else status("ARCore · relocalizando / ${camera.trackingFailureReason}")
            val now = System.nanoTime()
            if (hands.wantsArImage(now)) {
                try {
                    val image = frame.acquireCameraImage()
                    val degrees = when (displayRotation) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
                    hands.offerArImage(image, (sensorOrientation - degrees + 360) % 360, now)
                } catch (_: NotYetAvailableException) { /* Camera producer has no CPU image yet; skip, never block. */ }
            }
            true
        } catch (error: Exception) {
            active = false; onFailure(error.message ?: "Sessão ARCore interrompida"); false
        }
    }
    private fun status(message: String) { if (lastStatus != message) { lastStatus = message; onStatus(message) } }
    @Synchronized override fun close() {
        active = false
        originAnchor?.detach();originAnchor=null
        session?.let { runCatching { it.pause() }; runCatching { it.close() } }
        session = null; lastTexture = -1; centered = false
    }
    private fun buffer(data: FloatArray): FloatBuffer = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(data); position(0) }
}
