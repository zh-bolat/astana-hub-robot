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
        crashGuard = CrashGuard(
            this, MainActivity::class.java, TechPauseActivity::class.java,
            buildLabel = "${BuildConfig.FLAVOR} ${BuildConfig.BUILD_TYPE} ${BuildConfig.VERSION_NAME}",
        ).also { it.install() }
        techPause = crashGuard.inPause()
        if (techPause) {
            Timber.w("technical pause: SDK and network are not started")
            return
        }
        // На роботе здесь же создаётся единственный AppAgent (см. src/robot/.../FlavorGateways.kt).
        container = AppContainer(this)
        registerActivityLifecycleCallbacks(ForegroundTracker { visible, force ->
            container.assistantGuard.setForeground(visible, force)
        })
    }

    /**
     * Приложение на переднем плане, пока виден хоть один его экран. onResume — применить заново (AgentOS сам
     * открывает микрофон при выходе приложения на передний план, глушим после него).
     */
    private class ForegroundTracker(private val onChange: (visible: Boolean, force: Boolean) -> Unit) :
        ActivityLifecycleCallbacks {
        private var started = 0

        override fun onActivityStarted(activity: android.app.Activity) {
            started++
            if (started == 1) onChange(true, false)
        }

        override fun onActivityResumed(activity: android.app.Activity) = onChange(true, true)

        override fun onActivityStopped(activity: android.app.Activity) {
            started = (started - 1).coerceAtLeast(0)
            if (started == 0) onChange(false, false)
        }

        override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) = Unit
        override fun onActivityPaused(activity: android.app.Activity) = Unit
        override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) = Unit
        override fun onActivityDestroyed(activity: android.app.Activity) = Unit
    }
}
