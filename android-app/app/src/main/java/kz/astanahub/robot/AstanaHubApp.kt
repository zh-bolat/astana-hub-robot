package kz.astanahub.robot

import android.app.Application
import kz.astanahub.robot.di.AppContainer
import timber.log.Timber

class AstanaHubApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Логи только в debug и без персональных данных: тексты речи/вопросов/ответов не пишем
        // (только длина и хеш, см. core/LogSafe). В release дерево Timber не сажаем — logcat пуст.
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        // На роботе здесь же создаётся единственный AppAgent (см. src/robot/.../FlavorGateways.kt).
        container = AppContainer(this)
    }
}
