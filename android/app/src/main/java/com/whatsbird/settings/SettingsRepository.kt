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

package com.whatsbird.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "whatsbird_settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val SAVE_MODE = stringPreferencesKey("save_mode")
        val SHOW_BOXES = booleanPreferencesKey("show_boxes")
        val PREFER_GPU = booleanPreferencesKey("prefer_gpu")
        val CONFIDENCE = floatPreferencesKey("confidence_threshold")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        val defaults = AppSettings()
        AppSettings(
            saveMode = SaveMode.fromName(prefs[Keys.SAVE_MODE]),
            showBoxes = prefs[Keys.SHOW_BOXES] ?: defaults.showBoxes,
            preferGpu = prefs[Keys.PREFER_GPU] ?: defaults.preferGpu,
            confidenceThreshold = prefs[Keys.CONFIDENCE] ?: defaults.confidenceThreshold,
        )
    }

    suspend fun setSaveMode(mode: SaveMode) {
        context.dataStore.edit { it[Keys.SAVE_MODE] = mode.name }
    }

    suspend fun setShowBoxes(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_BOXES] = enabled }
    }

    suspend fun setPreferGpu(enabled: Boolean) {
        context.dataStore.edit { it[Keys.PREFER_GPU] = enabled }
    }

    suspend fun setConfidenceThreshold(value: Float) {
        context.dataStore.edit { it[Keys.CONFIDENCE] = value.coerceIn(0.05f, 0.95f) }
    }
}
