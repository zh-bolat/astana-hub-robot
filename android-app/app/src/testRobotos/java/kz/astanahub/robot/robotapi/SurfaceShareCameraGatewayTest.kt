package kz.astanahub.robot.robotapi

import com.ainirobot.coreservice.client.surfaceshare.SurfaceShareApi
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Test

class SurfaceShareCameraGatewayTest {

    @Test
    fun `camera failure returns null instead of crashing`() = runTest {
        val api = mockk<SurfaceShareApi> {
            every { requestImageFrame(any(), any(), any()) } throws IllegalStateException("service gone")
            every { abandonImageFrame(any()) } throws IllegalStateException("service gone")
        }
        assertNull(SurfaceShareCameraGateway { api }.captureJpeg())
    }
}
