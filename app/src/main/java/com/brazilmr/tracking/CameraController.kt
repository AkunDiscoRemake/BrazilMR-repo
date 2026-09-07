package com.brazilmr.tracking

import android.content.Context
import android.util.Size
import androidx.camera.core.*
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import android.hardware.camera2.CameraCharacteristics
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.brazilmr.core.performance.XrSettings

class CameraController(
    private val context: Context,
    private val lifecycle: LifecycleOwner,
    private val hands: CameraHandTrackingManager,
    private val surfaceProvider: Preview.SurfaceProvider,
    private val onState: (Boolean, String) -> Unit,
) {
    var onLens: ((Float,Float)->Unit)?=null
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var generation = 0
    @androidx.annotation.OptIn(markerClass=[ExperimentalCamera2Interop::class])
    fun start(settings: XrSettings, rotation: Int) {
        val request = ++generation
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (request != generation) return@addListener
            try {
                val cameraProvider = future.get(); provider = cameraProvider
                analysis?.clearAnalyzer(); cameraProvider.unbindAll()
                val selector = if (settings.frontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                if (!cameraProvider.hasCamera(selector)) { onState(false, "Câmera selecionada não disponível"); return@addListener }
                val preview = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3).setTargetRotation(rotation).build()
                val bound: Camera
                preview.setSurfaceProvider(surfaceProvider)
                if (settings.trackingEnabled && hands.modelAvailable) {
                    val imageAnalysis = ImageAnalysis.Builder()
                        .setTargetRotation(rotation)
                        .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy(Size(settings.trackingWidth, settings.trackingWidth * 3 / 4), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build())
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build()
                    imageAnalysis.setAnalyzer(hands.executor, hands::analyze); analysis = imageAnalysis
                    bound=cameraProvider.bindToLifecycle(lifecycle, selector, preview, imageAnalysis)
                } else {
                    analysis = null; bound=cameraProvider.bindToLifecycle(lifecycle, selector, preview)
                }
                runCatching {
                    val info=Camera2CameraInfo.from(bound.cameraInfo)
                    val sensor=info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                    val focal=info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
                    if(sensor!=null && focal!=null && focal>0)onLens?.invoke(sensor.width/(2*focal),sensor.height/(2*focal))
                }
                onState(true, "CameraX · passthrough")
            } catch (error: Exception) { onState(false, error.message ?: "Não foi possível abrir a câmera") }
        }, ContextCompat.getMainExecutor(context))
    }
    fun stop() { generation++; analysis?.clearAnalyzer(); analysis = null; provider?.unbindAll(); onState(false, "Câmera pausada") }
}
