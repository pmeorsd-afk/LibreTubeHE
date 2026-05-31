package io.github.aedev.flow

import android.app.Application
import android.content.Context

class FlowApplication : Application() {
    companion object {
        lateinit var appContext: Context
            private set

        fun installContext(context: Context) {
            appContext = context.applicationContext
        }
    }

    override fun onCreate() {
        super.onCreate()
        installContext(this)
    }
}
