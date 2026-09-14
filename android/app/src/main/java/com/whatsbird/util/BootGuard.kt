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

package com.whatsbird.util

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Stops a settings toggle from bricking the app.
 *
 * Model bring-up runs inside MediaPipe and TFLite, both of which can fail with a native `abort()`.
 * An abort is not an exception: no `runCatching` sees it, the process dies, and — if the offending
 * choice was persisted — the app dies again on every relaunch. A user cannot recover from that.
 * (This is not hypothetical: `preferGpu` + `setCategoryAllowlist` did exactly this.)
 *
 * The guard is a marker file recording which delegate was being brought up. It is armed before the
 * models are built and cleared only once a detection result has actually come back, so "armed"
 * means "a native failure before the first inference is possible". Finding it armed on the next
 * launch therefore means the previous run died during bring-up, and the one thing we know about
 * that run is the delegate it used — so next launch uses the safe one instead of repeating the
 * crash.
 */
class BootGuard(context: Context) {

    private val marker = File(context.filesDir, MARKER_NAME)

    /** True when the previous run died before the models finished coming up. */
    fun tripped(): Boolean = marker.isFile

    /** Which delegate the failed run was using, or null when that value was not recorded. */
    fun failedDelegate(): String? =
        runCatching { marker.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** Records the delegate being tried, before anything can abort. */
    fun arm(delegate: String) {
        runCatching { marker.writeText(delegate) }
            .onFailure { Log.w(TAG, "could not arm boot guard", it) }
    }

    /** Called once a model set has demonstrably worked, i.e. produced a detection result. */
    fun clear() {
        if (marker.isFile) runCatching { marker.delete() }
    }

    companion object {
        private const val TAG = "BootGuard"
        private const val MARKER_NAME = "model_boot_guard"
    }
}
