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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes uncaught exceptions to `filesDir/crashes/` before the process dies.
 *
 * The crash-prone work happens on threads this code does not own — MediaPipe's result thread and
 * the classification executor — so "it closes as soon as a bird shows up" is otherwise impossible
 * to act on without a live adb session attached at the moment of the crash.
 */
object CrashLog {

    private const val TAG = "CrashLog"
    private const val DIR_NAME = "crashes"
    private const val KEEP = 10

    fun install(context: Context) {
        val dir = File(context.filesDir, DIR_NAME)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { write(dir, thread, throwable) }
                .onFailure { Log.w(TAG, "could not persist crash report", it) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun write(dir: File, thread: Thread, throwable: Throwable) {
        if (!dir.isDirectory && !dir.mkdirs()) return
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        File(dir, "crash_$stamp.txt").writeText(
            buildString {
                // The name already identifies the origin (wb-classify, wb-frames, MediaPipe's result
                // thread). Thread#id needs a deprecation suppression and Thread#threadId is API 36,
                // so the number is simply not worth carrying.
                appendLine("thread: ${thread.name}")
                appendLine("at: ${Date()}")
                appendLine()
                append(Log.getStackTraceString(throwable))
            },
        )
        dir.listFiles()
            ?.sortedByDescending { it.name }
            ?.drop(KEEP)
            ?.forEach { runCatching { it.delete() } }
    }
}
