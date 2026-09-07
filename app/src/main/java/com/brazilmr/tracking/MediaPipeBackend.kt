package com.brazilmr.tracking

import android.content.Context
import java.nio.ByteBuffer
import com.brazilmr.core.tracking.HandFrame
import com.google.mediapipe.framework.image.ByteBufferImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker

/** MediaPipe is confined to this adapter. VIDEO mode is synchronous on our worker: explicit buffer ownership. */
class MediaPipeBackend(context: Context) : AutoCloseable {
    private val landmarker = HandLandmarker.createFromOptions(context,
        HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("models/hand_landmarker.task").setDelegate(Delegate.CPU).build())
            .setRunningMode(RunningMode.VIDEO).setNumHands(2)
            .setMinHandDetectionConfidence(.55f).setMinHandPresenceConfidence(.55f).setMinTrackingConfidence(.55f).build())
    private val rotations = Array(4) { ImageProcessingOptions.builder().setRotationDegrees(it * 90).build() }
    private var lastTimestamp = -1L
    fun detect(buffer: ByteBuffer, width: Int, height: Int, rotation: Int, timestampNanos: Long, swapHands: Boolean, output: HandFrame) {
        output.clear(); output.timestampNanos = timestampNanos; output.sequence++
        val start = System.nanoTime()
        val timestampMillis = maxOf(timestampNanos / 1_000_000, lastTimestamp + 1)
        lastTimestamp = timestampMillis
        val image = ByteBufferImageBuilder(buffer, width, height, MPImage.IMAGE_FORMAT_RGBA).build()
        try {
            val result = landmarker.detectForVideo(image, rotations[((rotation % 360 + 360) % 360) / 90], timestampMillis)
            val landmarks = result.landmarks(); val world = result.worldLandmarks(); val sides = result.handedness()
            val aspect = if (rotation % 180 == 0) width.toFloat() / height else height.toFloat() / width
            for (index in landmarks.indices) {
                val points = landmarks[index]
                if (points.size != 21 || index >= sides.size || sides[index].isEmpty()) continue
                val category = sides[index][0]
                var isRight = category.categoryName().equals("Right", true)
                if (swapHands) isRight = !isRight
                val hand = if (isRight) output.right else output.left
                if (hand.present && hand.confidence >= category.score()) continue
                hand.present = true; hand.confidence = category.score()
                for (i in 0..20) {
                    val point = points[i]
                    hand.landmarks[i * 3] = point.x(); hand.landmarks[i * 3 + 1] = point.y(); hand.landmarks[i * 3 + 2] = point.z()
                    val metric = if (index < world.size && world[index].size == 21) world[index][i] else null
                    hand.geometry[i * 3] = metric?.x() ?: (point.x() * aspect)
                    hand.geometry[i * 3 + 1] = metric?.y() ?: point.y()
                    hand.geometry[i * 3 + 2] = metric?.z() ?: (point.z() * aspect)
                }
            }
        } finally { image.close() }
        output.inferenceMillis = (System.nanoTime() - start) / 1_000_000f
    }
    override fun close() { landmarker.close() }
}
