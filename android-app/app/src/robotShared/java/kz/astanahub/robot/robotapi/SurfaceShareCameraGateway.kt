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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kz.astanahub.robot.camera.CameraGateway
import kz.astanahub.robot.camera.YuvJpeg
import kz.astanahub.robot.core.sdkCall
import timber.log.Timber

/**
 * Один кадр через SurfaceShareApi (RobotAPI_en.md, «Camera Data Stream Sharing»): камеру не занимаем,
 * зрение робота продолжает работать. Поток закрывается сразу после кадра (stopPushStream).
 * Код возврата requestImageFrame < 0 (−1 — нет сервиса, −3 — RemoteException, −14 — поток занят) → сразу null.
 * ImageReader закрывается на его же потоке после слушателя — кадр не читается из закрытого reader.
 * ПРОВЕРИТЬ НА РОБОТЕ (спайк 2.7): работает ли SurfaceShareApi на Nova, ориентация кадра, утечки.
 */
class SurfaceShareCameraGateway(
    private val api: () -> SurfaceShareApi = { SurfaceShareApi.getInstance() },
) : CameraGateway {

    private val mutex = Mutex()

    override suspend fun captureJpeg(quality: Int): ByteArray? = mutex.withLock {
        try {
            capture(quality)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Timber.e(e, "SurfaceShare capture failed")
            null
        }
    }

    private suspend fun capture(quality: Int): ByteArray? {
        val thread = HandlerThread("SurfaceShareCapture").apply { start() }
        val handler = Handler(thread.looper)
        val reader: ImageReader? = runCatching {
            ImageReader.newInstance(WIDTH, HEIGHT, ImageFormat.YUV_420_888, MAX_IMAGES)
        }.onFailure { Timber.e(it, "ImageReader") }.getOrNull()
        if (reader == null) {
            thread.quitSafely()
            return null
        }
        val bean = SurfaceShareBean().apply { name = "AstanaHubLook" }
        var shareError = 0
        try {
            return withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    reader.setOnImageAvailableListener({ r ->
                        val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                        try {
                            if (cont.isActive) cont.resume(YuvJpeg.fromYuv420(image, quality))
                        } catch (e: Exception) {
                            Timber.e(e, "frame convert failed")
                            if (cont.isActive) cont.resume(null)
                        } finally {
                            runCatching { image.close() }
                        }
                    }, handler)
                    val rc = sdkCall("SurfaceShareApi.requestImageFrame") {
                        api().requestImageFrame(
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
                    }
                    Timber.d("requestImageFrame rc=%s", rc)
                    when {
                        rc == null -> if (cont.isActive) cont.resume(null)
                        rc < 0 -> {
                            shareError = rc
                            if (cont.isActive) cont.resume(null)
                        }
                        else -> Unit // ждём кадр или onError
                    }
                }
            }
        } finally {
            // Всегда (кроме «поток занят другим»): иначе сервис продолжит слать кадры — OOM.
            stopPushStream(bean, shareError)
            // Закрываем reader на его потоке: сообщения слушателя, уже стоящие в очереди, отработают раньше.
            val posted = runCatching {
                handler.post {
                    runCatching { reader.setOnImageAvailableListener(null, null) }
                    runCatching { reader.close() }
                }
            }.getOrDefault(false)
            if (!posted) runCatching { reader.close() }
            thread.quitSafely()
        }
    }

    /** Аналог stopPushStream() из примера вендора: отказаться от кадров, иначе OOM. */
    private fun stopPushStream(bean: SurfaceShareBean, error: Int) {
        if (error == SurfaceShareError.ERROR_SURFACE_SHARE_USED) return
        sdkCall("SurfaceShareApi.abandonImageFrame") { api().abandonImageFrame(bean) }
    }

    private companion object {
        const val WIDTH = 640
        const val HEIGHT = 480
        const val MAX_IMAGES = 4 // как в VisionSDK по доке
        const val CAPTURE_TIMEOUT_MS = 3_000L
    }
}
