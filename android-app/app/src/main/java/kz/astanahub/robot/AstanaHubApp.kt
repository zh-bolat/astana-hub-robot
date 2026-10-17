package kz.astanahub.robot

import android.app.Application
import kz.astanahub.robot.core.CrashGuard
import kz.astanahub.robot.di.AppContainer
import timber.log.Timber

class AstanaHubApp : Application() {

    lateinit var container: AppContainer
        private set

    lateinit var crashGuard: CrashGuard
        private set

    /** «Техническая пауза» после серии падений: контейнер (SDK, сеть) не создаётся. */
    var techPause: Boolean = false
        private set

    override fun onCreate() {
        super.onCreate()
        // Логи только в debug и без персональных данных: тексты речи/вопросов/ответов не пишем
        // (только длина и хеш, см. core/LogSafe). В release дерево Timber не сажаем — logcat пуст.
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        // Первым делом — обработчик падений: перезапуск киоска вместо системного диалога.
        crashGuard = CrashGuard(this, MainActivity::class.java, TechPauseActivity::class.java).also { it.install() }
        techPause = crashGuard.inPause()
        if (techPause) {
            Timber.w("technical pause: SDK and network are not started")
            return
        }
        // На роботе здесь же создаётся единственный AppAgent (см. src/robot/.../FlavorGateways.kt).
        container = AppContainer(this)
    }
}
