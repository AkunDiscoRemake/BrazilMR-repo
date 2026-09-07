package com.brazilmr.tracking

import android.media.Image
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

/** Exactly one owner (inference executor). MPImage wrappers are closed before these buffers can be reused. */
class ReusableImageBuffer : AutoCloseable {
    private var packed: ByteBuffer? = null
    private fun buffer(required: Int): ByteBuffer {
        var current = packed
        if (current == null || current.capacity() != required) { current = ByteBuffer.allocateDirect(required); packed = current }
        current.clear(); return current
    }
    fun rgba(image: ImageProxy): ByteBuffer {
        val plane = image.planes[0]; val source = plane.buffer
        val rowBytes = image.width * 4
        require(plane.pixelStride == 4) { "Formato RGBA não suportado" }
        source.rewind()
        // No copy for tightly packed CameraX RGBA; ImageProxy remains open until synchronous inference returns.
        if (plane.rowStride == rowBytes) { source.limit(rowBytes * image.height); return source }
        val output = buffer(rowBytes * image.height)
        val limit = source.limit()
        for (row in 0 until image.height) {
            source.limit(minOf(limit, row * plane.rowStride + rowBytes)); source.position(row * plane.rowStride)
            output.put(source); source.limit(limit)
        }
        output.flip(); return output
    }
    /** ARCore CPU images are YUV_420_888. Convert once into a reusable direct RGBA buffer. */
    fun yuv(image: Image): ByteBuffer {
        val width = image.width; val height = image.height
        val target = buffer(width * height * 4)
        val y = image.planes[0]; val u = image.planes[1]; val v = image.planes[2]
        val yb = y.buffer; val ub = u.buffer; val vb = v.buffer
        val yo = yb.position(); val uo = ub.position(); val vo = vb.position()
        for (row in 0 until height) {
            val yr = yo + row * y.rowStride
            val ur = uo + (row / 2) * u.rowStride; val vr = vo + (row / 2) * v.rowStride
            for (col in 0 until width) {
                val yy = (yb.get(yr + col * y.pixelStride).toInt() and 255) - 16
                val uu = (ub.get(ur + col / 2 * u.pixelStride).toInt() and 255) - 128
                val vv = (vb.get(vr + col / 2 * v.pixelStride).toInt() and 255) - 128
                val luminance = 1192 * yy.coerceAtLeast(0)
                val r = ((luminance + 1634 * vv) shr 10).coerceIn(0, 255)
                val g = ((luminance - 833 * vv - 400 * uu) shr 10).coerceIn(0, 255)
                val b = ((luminance + 2066 * uu) shr 10).coerceIn(0, 255)
                target.put(r.toByte()).put(g.toByte()).put(b.toByte()).put(255.toByte())
            }
        }
        target.flip(); return target
    }
    override fun close() { packed = null }
}
