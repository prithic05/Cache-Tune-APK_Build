package com.cachetune.app

import android.app.Application
import android.util.Log

class CacheTuneApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.i("CacheTune", "Cache Tune Lossless Audio Engine Initialized.")
    }
}
