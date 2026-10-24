package kz.astanahub.robot.fake

import kz.astanahub.robot.voice.BuiltinAssistant
import timber.log.Timber

/** На эмуляторе встроенного ассистента нет — только лог. */
class FakeAssistant : BuiltinAssistant {
    override fun takeOver(listen: Boolean) = Timber.d("FAKE builtin assistant: take over (listen=%s)", listen)
    override fun release() = Timber.d("FAKE builtin assistant: release")
}
