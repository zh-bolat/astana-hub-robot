package kz.astanahub.robot.fake

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kz.astanahub.robot.camera.CameraGateway

/** Камера-заглушка: синтетический кадр 640×480 с подписью и временем. */
class FakeCameraGateway : CameraGateway {

    override suspend fun captureJpeg(quality: Int): ByteArray = withContext(Dispatchers.Default) {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(31, 79, 255))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 40f }
            canvas.drawText("FAKE CAMERA", 40f, 120f, paint)
            canvas.drawText(System.currentTimeMillis().toString(), 40f, 200f, paint)
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                out.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val WIDTH = 640
        const val HEIGHT = 480
    }
}
