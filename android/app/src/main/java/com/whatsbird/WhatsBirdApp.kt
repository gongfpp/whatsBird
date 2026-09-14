/*
 * Copyright 2026 gongfpp (https://github.com/gongfpp/whatsBird)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
