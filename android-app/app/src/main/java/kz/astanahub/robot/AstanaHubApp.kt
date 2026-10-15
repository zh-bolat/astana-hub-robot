package kz.astanahub.robot

import android.app.Application
import kz.astanahub.robot.di.AppContainer
import timber.log.Timber

class AstanaHubApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        // На роботе здесь же создаётся единственный AppAgent (см. src/robot/.../FlavorGateways.kt).
        container = AppContainer(this)
    }
}
