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

/** What gets written to the gallery on every shutter press. */
enum class SaveMode {
    ORIGINAL,
    LABELED,
    BOTH,
    ;

    companion object {
        fun fromName(name: String?): SaveMode =
            entries.firstOrNull { it.name == name } ?: BOTH
    }
}

data class AppSettings(
    val saveMode: SaveMode = SaveMode.BOTH,
    val showBoxes: Boolean = true,
    /**
     * On by default, and that is a measurement rather than a preference.
     *
     * The plan asks for a CPU baseline first and then for the acceleration options to be measured,
     * so they were. On the reference device (Redmi Note 8 Pro, Mali-G76), the shipped
     * EfficientDet-Lite2 detector takes **782 ms** per 1280x960 analysis frame on the CPU delegate
     * and **221 ms** on the GPU delegate back to back — and **126 ms** in the live pipeline, where
     * MediaPipe overlaps consecutive frames. The preview is throttled to
     * [targetDetectionsPerSecond], so 126 ms is inside the budget while 782 ms is six times outside
     * it: on CPU the detection rate collapses to about one frame per second regardless of the
     * throttle setting.
     *
     * The risk this takes on is a device whose GPU delegate cannot come up at all, since that failure
     * is a native abort. `util/BootGuard` covers it: a launch that dies during GPU bring-up forces
     * the next launch back to CPU and writes this flag off, so the worst case is one bad launch
     * instead of an app that never opens again.
     */
    val preferGpu: Boolean = true,
    /**
     * Below this score the UI says "鸟类，暂未确定" instead of claiming a species.
     *
     * 0.55 is a development-stage empirical default, **not** a calibrated operating point: it was
     * chosen from threshold sweeps whose held-out sets turned out to be contaminated across model
     * versions, and the shipped model's corpus predates the licence-clean fetch defaults. Until a
     * genuinely independent test set exists (a freshly trained model with its
     * `training_provenance.json`, evaluated by `sweep_threshold.py` on data the model never saw),
     * treat this number as a placeholder to be re-calibrated — do not cite the old sweep numbers
     * as a quality baseline.
     */
    val confidenceThreshold: Float = DEFAULT_CONFIDENCE_THRESHOLD,
    /** Analyses per second. Preview stays smooth because analysis is throttled independently. */
    val targetDetectionsPerSecond: Int = 8,
) {
    companion object {
        /** Shared so the stabiliser, the still-photo path and the settings default cannot drift apart. */
        const val DEFAULT_CONFIDENCE_THRESHOLD = 0.55f
    }
}
