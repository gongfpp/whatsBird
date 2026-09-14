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
     * 0.55 is not a taste call. Sweeping this knob over the 1612-photo held-out set
     * (`ml/sweep_threshold.py --model out2/classifier_float16.tflite --data data2`): at 0.55 a name
     * appears for 53.1% of listed-species photos and 85.7% of the shown names are right. At 0.60
     * that is 47.7% / 88.2% — more than half the birds stay unnamed. At 0.50 it is 58.3% / 83.5%,
     * but out-of-list birds get handed a listed name 15.2% of the time instead of 7.6%. 0.55 takes
     * the coverage without letting the visible "it named the wrong bird" failure rate double.
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
