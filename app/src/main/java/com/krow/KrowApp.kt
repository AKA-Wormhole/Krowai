package com.krow

import android.app.Application

class KrowApp : Application() {
    lateinit var core: Core
    override fun onCreate() {
        super.onCreate()
        core = Core(this)
    }
}
