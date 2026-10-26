package kz.astanahub.robot.robotapi

import com.ainirobot.coreservice.client.RobotApi
import com.ainirobot.coreservice.client.listener.ActionListener
import com.ainirobot.coreservice.client.listener.CommandListener
import com.ainirobot.coreservice.client.person.PersonApi
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kz.astanahub.robot.core.sdkCall
import kz.astanahub.robot.robot.HeadDiagnostics

/**
 * Проверка головы и зрения на роботе (экран диагностики): команды RobotApi с отчётом о каждом ответе.
 * moveHead / resetHead / startFocusFollow / stopFocusFollow — docs/vendor/RobotAPI_en.md («Head Gimbal Motion»,
 * «Focus Following»); getHeadStatus — есть в robotservice.jar (javap), в документации не описан.
 * Люди — PersonApi.getAllPersons(maxDistance) и getCompleteFaceList() («Vision Capability»).
 */
class RobotHeadDiagnostics(
    private val api: () -> RobotApi = { RobotApi.getInstance() },
    private val personApi: () -> PersonApi = { PersonApi.getInstance() },
) : HeadDiagnostics {

    private val reqId = AtomicInteger(20_000)

    override fun move(hMode: String, vMode: String, hAngle: Int, vAngle: Int, report: (String) -> Unit) {
        val what = "moveHead($hMode, $vMode, h=$hAngle, v=$vAngle)"
        val rc = sdkCall("diag moveHead") { api().moveHead(reqId.getAndIncrement(), hMode, vMode, hAngle, vAngle, Reporting(what, report)) }
        report("$what → rc=${rc ?: "исключение SDK"}")
    }

    override fun reset(report: (String) -> Unit) {
        val rc = sdkCall("diag resetHead") { api().resetHead(reqId.getAndIncrement(), Reporting("resetHead", report)) }
        report("resetHead → rc=${rc ?: "исключение SDK"}")
    }

    override fun status(report: (String) -> Unit) {
        val rc = sdkCall("diag getHeadStatus") { api().getHeadStatus(reqId.getAndIncrement(), Reporting("getHeadStatus", report)) }
        report("getHeadStatus → rc=${rc ?: "исключение SDK"}")
    }

    override fun follow(faceId: Int, report: (String) -> Unit) {
        val what = "startFocusFollow(face=$faceId)"
        val rc = sdkCall("diag startFocusFollow") {
            api().startFocusFollow(reqId.getAndIncrement(), faceId, LOST_TIMEOUT_MS, MAX_DISTANCE_M, ReportingAction(what, report))
        }
        report("$what → rc=${rc ?: "исключение SDK"}")
    }

    override fun stopFollow(report: (String) -> Unit) {
        val rc = sdkCall("diag stopFocusFollow") { api().stopFocusFollow(reqId.getAndIncrement()) }
        report("stopFocusFollow → rc=${rc ?: "исключение SDK"}")
    }

    override fun persons(): List<String> {
        val all = sdkCall("diag getAllPersons") { personApi().getAllPersons(MAX_PERSON_DISTANCE_M) }
        val faces = sdkCall("diag getCompleteFaceList") { personApi().getCompleteFaceList() }
        val lines = ArrayList<String>()
        lines += "PersonApi: людей ${all?.size ?: "нет ответа"}, лиц с полной информацией ${faces?.size ?: "нет ответа"}"
        all?.forEach { p ->
            val line = sdkCall("diag person") {
                String.format(
                    Locale.US, "id=%d  %.2f м  угол=%d°  лицо x=%.0f° y=%.0f°  высота лица=%d",
                    p.id, p.distance, p.angle, p.faceAngleX, p.faceAngleY, p.faceHeight,
                )
            }
            if (line != null) lines += line
        }
        return lines
    }

    override fun nearestFaceId(): Int? {
        val all = sdkCall("diag getAllPersons") { personApi().getAllPersons(MAX_PERSON_DISTANCE_M) } ?: return null
        return all.mapNotNull { p -> sdkCall("diag person") { p.id to p.distance } }
            .filter { it.first >= 0 && it.second > 0 }
            .minByOrNull { it.second }?.first
    }

    /** Отчёт о каждом ответе CommandListener (moveHead, resetHead, getHeadStatus). */
    private class Reporting(private val what: String, private val report: (String) -> Unit) : CommandListener() {
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onResult(result: Int, message: String?) = report("$what onResult $result $message")
        override fun onResult(result: Int, message: String?, extraData: String?) = report("$what onResult $result $message ${extraData.orEmpty()}")
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onStatusUpdate(status: Int, data: String?) = report("$what onStatusUpdate $status $data")
        override fun onStatusUpdate(status: Int, data: String?, extraData: String?) = report("$what onStatusUpdate $status $data ${extraData.orEmpty()}")
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onError(errorCode: Int, errorString: String?) = report("$what onError $errorCode $errorString")
        override fun onError(errorCode: Int, errorString: String?, extraData: String?) = report("$what onError $errorCode $errorString ${extraData.orEmpty()}")
    }

    /** Отчёт о каждом ответе ActionListener (startFocusFollow). */
    private class ReportingAction(private val what: String, private val report: (String) -> Unit) : ActionListener() {
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onResult(status: Int, responseString: String?) = report("$what onResult $status $responseString")
        override fun onResult(status: Int, responseString: String?, extraData: String?) = report("$what onResult $status $responseString ${extraData.orEmpty()}")
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onStatusUpdate(status: Int, data: String?) = report("$what onStatusUpdate $status $data")
        override fun onStatusUpdate(status: Int, data: String?, extraData: String?) = report("$what onStatusUpdate $status $data ${extraData.orEmpty()}")
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onError(errorCode: Int, errorString: String?) = report("$what onError $errorCode $errorString")
        override fun onError(errorCode: Int, errorString: String?, extraData: String?) = report("$what onError $errorCode $errorString ${extraData.orEmpty()}")
    }

    private companion object {
        const val LOST_TIMEOUT_MS = 8_000L
        const val MAX_DISTANCE_M = 3.0f
        const val MAX_PERSON_DISTANCE_M = 5.0
    }
}
