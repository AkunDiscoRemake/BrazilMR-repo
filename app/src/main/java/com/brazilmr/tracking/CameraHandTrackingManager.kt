package com.brazilmr.tracking

import android.content.Context
import android.media.Image
import androidx.camera.core.ImageProxy
import com.brazilmr.core.performance.XrSettings
import com.brazilmr.core.tracking.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class CameraHandTrackingManager(
    private val context: Context,
    private val onStatus: (TrackingStatus, String) -> Unit,
) : HandTrackingManager {
    val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "BrazilMR-Inference").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val mailbox = HandFrameMailbox()
    private val raw = HandFrame()
    private val pipeline = HandTrackingPipeline()
    private val buffers = ReusableImageBuffer()
    @Volatile private var backend: MediaPipeBackend? = null
    @Volatile private var enabled = false
    @Volatile private var closed = false
    @Volatile override var status = TrackingStatus.STOPPED; private set
    @Volatile override var detail = "Tracking parado"; private set
    @Volatile var configuration = XrSettings()
    @Volatile var rateCap = 24
    @Volatile var thermalPaused = false
    @Volatile var sourceAspect = 4f / 3f; private set
    private var applied: XrSettings? = null
    @Volatile private var lastFrame = 0L
    private val arBusy = AtomicBoolean(false)
    val modelAvailable = runCatching { context.assets.open("models/hand_landmarker.task").use { it.read() >= 0 } }.getOrDefault(false)
    override fun start() {
        if (closed) return
        enabled = true
        if (!modelAvailable) { updateStatus(TrackingStatus.NO_MODEL, "Instale hand_landmarker.task · consulte Developer"); return }
        updateStatus(TrackingStatus.STARTING, "Inicializando MediaPipe · CPU")
        executor.execute {
            if (!enabled || closed) return@execute
            try {
                if (backend == null) backend = MediaPipeBackend(context)
                lastFrame = 0L
                updateStatus(TrackingStatus.RUNNING, "MediaPipe · pronto")
            } catch (error: Exception) { updateStatus(TrackingStatus.ERROR, error.message ?: "MediaPipe indisponível") }
        }
    }
    override fun stop() { enabled = false; mailbox.clear(); updateStatus(TrackingStatus.STOPPED, "Tracking pausado") }
    private fun allowed(now: Long): Boolean = enabled && !closed && !thermalPaused && configuration.trackingEnabled && backend != null && now - lastFrame >= 1_000_000_000L / rateCap.coerceIn(5, 60)
    /** Called by CameraX on executor. Every frame is closed, including throttled and error paths. */
    fun analyze(image: ImageProxy) {
        try {
            val now = System.nanoTime()
            if (!allowed(now)) return
            lastFrame = now
            val rotation = image.imageInfo.rotationDegrees
            sourceAspect = if (rotation % 180 == 0) image.width.toFloat() / image.height else image.height.toFloat() / image.width
            detect(buffers.rgba(image), image.width, image.height, rotation, now)
        } catch (error: Exception) { mailbox.clear(); updateStatus(TrackingStatus.ERROR, error.message ?: "Falha no frame") }
        finally { image.close() }
    }
    fun wantsArImage(now: Long) = !arBusy.get() && allowed(now)
    /** Takes ownership of an ARCore CPU image. No ARCore + CameraX camera sessions run together. */
    fun offerArImage(image: Image, rotation: Int, now: Long) {
        if (closed || !arBusy.compareAndSet(false, true)) { image.close(); return }
        try {
            executor.execute {
                try {
                    if (allowed(now)) {
                        lastFrame = now
                        sourceAspect = if (rotation % 180 == 0) image.width.toFloat() / image.height else image.height.toFloat() / image.width
                        detect(buffers.yuv(image), image.width, image.height, rotation, now)
                    }
                } catch (error: Exception) { mailbox.clear(); updateStatus(TrackingStatus.ERROR, error.message ?: "Falha na imagem ARCore") }
                finally { image.close(); arBusy.set(false) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { image.close(); arBusy.set(false) }
    }
    private fun detect(buffer: java.nio.ByteBuffer, width: Int, height: Int, rotation: Int, now: Long) {
        val config = configuration
        if (applied !== config) { pipeline.configure(config.filter); pipeline.leftEnabled = config.leftHand; pipeline.rightEnabled = config.rightHand; applied = config }
        backend?.detect(buffer, width, height, rotation, now, config.swapHands, raw) ?: return
        if (enabled) { mailbox.publish(pipeline.process(raw)); if (status != TrackingStatus.RUNNING) updateStatus(TrackingStatus.RUNNING, "MediaPipe · ativo") }
    }
    override fun readInto(destination: HandFrame) {
        mailbox.readInto(destination)
        if (!enabled || thermalPaused || System.nanoTime() - destination.timestampNanos > 350_000_000L) destination.clear()
    }
    private fun updateStatus(value: TrackingStatus, message: String) {
        status = value; detail = message; onStatus(value, message)
    }
    override fun close() {
        if (closed) return
        stop(); closed = true
        executor.execute { backend?.close(); backend = null; buffers.close() }
        executor.shutdown()
    }
}
