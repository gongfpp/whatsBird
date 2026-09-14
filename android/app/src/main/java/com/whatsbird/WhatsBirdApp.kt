package com.whatsbird

import android.app.Application
import com.whatsbird.settings.SettingsRepository
import com.whatsbird.species.SpeciesDictionary
import com.whatsbird.species.SpeciesDictionaryLoader
import com.whatsbird.util.CrashLog

/**
 * Loads the species dictionary once at startup. It is a few kilobytes, so doing it synchronously
 * here keeps the first frame from ever showing an empty label space.
 */
class WhatsBirdApp : Application() {

    lateinit var settings: SettingsRepository
        private set

    /** Null when the generated asset is missing or malformed — the app then runs detection-only. */
    var speciesDictionary: SpeciesDictionary? = null
        private set

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        settings = SettingsRepository(this)
        speciesDictionary = SpeciesDictionaryLoader.load(this)
    }
}
