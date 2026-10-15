package kz.astanahub.robot.camera

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import java.io.ByteArrayOutputStream

/** YUV_420_888 → NV21 → JPEG через YuvImage (без сторонних библиотек, см. docs/stack.md). */
object YuvJpeg {

    fun fromYuv420(image: Image, quality: Int): ByteArray {
        val nv21 = toNv21(image)
        val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        return ByteArrayOutputStream().use { out ->
            yuv.compressToJpeg(Rect(0, 0, image.width, image.height), quality, out)
            out.toByteArray()
        }
    }

    /** Учитывает rowStride/pixelStride каждой плоскости. */
    fun toNv21(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val out = ByteArray(width * height * 3 / 2)
        val (yPlane, uPlane, vPlane) = image.planes

        var pos = 0
        val yBuf = yPlane.buffer
        for (row in 0 until height) {
            val base = row * yPlane.rowStride
            for (col in 0 until width) {
                out[pos++] = yBuf.get(base + col * yPlane.pixelStride)
            }
        }

        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                val vIndex = row * vPlane.rowStride + col * vPlane.pixelStride
                val uIndex = row * uPlane.rowStride + col * uPlane.pixelStride
                out[pos++] = vBuf.get(vIndex)
                out[pos++] = uBuf.get(uIndex)
            }
        }
        return out
    }
}
