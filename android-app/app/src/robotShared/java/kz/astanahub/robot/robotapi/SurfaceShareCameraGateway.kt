package kz.astanahub.robot.robotapi

import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import com.ainirobot.coreservice.client.surfaceshare.SurfaceShareApi
import com.ainirobot.coreservice.client.surfaceshare.SurfaceShareBean
import com.ainirobot.coreservice.client.surfaceshare.SurfaceShareError
import com.ainirobot.coreservice.client.surfaceshare.SurfaceShareListener
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kz.astanahub.robot.camera.CameraGateway
import kz.astanahub.robot.camera.YuvJpeg
import timber.log.Timber

/**
 * Один кадр через SurfaceShareApi (RobotAPI_en.md, «Camera Data Stream Sharing»): камеру не занимаем,
 * зрение робота продолжает работать. Поток закрывается сразу после кадра (stopPushStream).
 * ПРОВЕРИТЬ НА РОБОТЕ (спайк 2.7): работает ли SurfaceShareApi на Nova, ориентация кадра, утечки.
 */
class SurfaceShareCameraGateway : CameraGateway {

    private val mutex = Mutex()

    override suspend fun captureJpeg(quality: Int): ByteArray? = mutex.withLock {
        val thread = HandlerThread("SurfaceShareCapture").apply { start() }
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(WIDTH, HEIGHT, ImageFormat.YUV_420_888, MAX_IMAGES)
        val bean = SurfaceShareBean().apply { name = "AstanaHubLook" }
        var shareError = 0
        try {
            withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    reader.setOnImageAvailableListener({ r ->
                        val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                        try {
                            if (cont.isActive) {
                                val jpeg = YuvJpeg.fromYuv420(image, quality)
                                cont.resume(jpeg)
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "frame convert failed")
                            if (cont.isActive) cont.resume(null)
                        } finally {
                            image.close()
                        }
                    }, handler)
                    val rc = SurfaceShareApi.getInstance().requestImageFrame(
                        reader.surface,
                        bean,
                        object : SurfaceShareListener() {
                            override fun onError(error: Int, message: String?) {
                                Timber.w("SurfaceShare error %d %s", error, message)
                                shareError = error
                                if (cont.isActive) cont.resume(null)
                            }
                        },
                    )
                    Timber.d("requestImageFrame rc=%d", rc)
                }
            }
        } finally {
            stopPushStream(bean, shareError)
            reader.close()
            thread.quitSafely()
        }
    }

    /** Аналог stopPushStream() из примера вендора: отказаться от кадров, иначе OOM. */
    private fun stopPushStream(bean: SurfaceShareBean, error: Int) {
        if (error == SurfaceShareError.ERROR_SURFACE_SHARE_USED) return
        runCatching { SurfaceShareApi.getInstance().abandonImageFrame(bean) }
            .onFailure { Timber.e(it, "abandonImageFrame") }
    }

    private companion object {
        const val WIDTH = 640
        const val HEIGHT = 480
        const val MAX_IMAGES = 4 // как в VisionSDK по доке
        const val CAPTURE_TIMEOUT_MS = 3_000L
    }
}
