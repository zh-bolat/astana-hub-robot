package kz.astanahub.robot.camera

/** Один снимок с камеры для «Что это?». */
interface CameraGateway {
    /**
     * Делает кадр и возвращает JPEG (≈640×480, качество ≈80) или null, если камера недоступна.
     * На роботе — только SurfaceShareApi, поток всегда закрывается после снимка.
     */
    suspend fun captureJpeg(quality: Int = DEFAULT_JPEG_QUALITY): ByteArray?

    companion object {
        const val DEFAULT_JPEG_QUALITY = 80
    }
}
