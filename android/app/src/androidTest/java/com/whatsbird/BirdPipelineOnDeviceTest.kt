package com.whatsbird

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.whatsbird.classify.SpeciesClassifier
import com.whatsbird.detect.BirdDetector
import com.whatsbird.label.LabelKind
import com.whatsbird.pipeline.BirdPipeline
import com.whatsbird.species.SpeciesDictionaryLoader
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the whole frame → label chain on a held-out bird photo, the way the camera would.
 *
 * [ClassifierOnDeviceTest] exercises detection and classification separately, but the crash this
 * covers happened strictly *between* them: once a detection produced a live track, the pipeline
 * started building snapshot objects, and that code path is never entered on an empty viewfinder.
 * Feeding real frames through [BirdPipeline.submitFrame] is the only way to keep that gap covered
 * without somebody standing outside with the phone.
 */
@RunWith(AndroidJUnit4::class)
class BirdPipelineOnDeviceTest {

    @Test
    fun aFrameContainingABirdReachesTheOverlay() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val dictionary = SpeciesDictionaryLoader.load(context)
        assertNotNull("assets/models/species.json must be readable on device", dictionary)

        val classifier = SpeciesClassifier.create(context, dictionary)
        assertNotNull("assets/models/bird_classifier.tflite must load on device", classifier)

        val detector = BirdDetector(context, useGpu = false)
        val pipeline = BirdPipeline(detector, classifier, dictionary)

        try {
            // Which sample to drive the chain with is decided by the detector, not by file order.
            // Hard-coding "the first listed species" made this test hostage to one photo: swapping
            // EfficientDet-Lite0 for Lite2 raised overall detection recall from 22/30 to 25/30 and
            // still failed the suite, because that one photo happens to be one of the five Lite2
            // misses. Detection quality is measured over all thirty samples in
            // [ClassifierOnDeviceTest]; what this test has to prove is that *a* detected bird travels
            // the whole chain, and for that any detected photo will do.
            val (frame, source) = openDetectableBirdFrame(detector)

            // Same clock the pipeline measures label staleness against: CameraX frame timestamps
            // and SystemClock.elapsedRealtime() are both CLOCK_BOOTTIME. A hand-rolled counter here
            // would make every published label read as stale immediately.
            //
            // Frames are fed until a label leaves IDENTIFYING rather than for a fixed count. The
            // chain is asynchronous by construction: detection is refused while the previous frame
            // is still in flight, and the classifier runs on its own thread behind a vote window
            // that needs two looks. On the CPU delegate this detector takes ~0.8 s per 1280x720
            // frame, so a fixed twelve 250 ms iterations lets roughly three frames through and the
            // label can legitimately still be mid-flight when the loop exits — a fixed count tests
            // the machine's speed, not the pipeline. What the test has to prove is that the bird
            // *does* arrive at a decided label, so that is what it waits for.
            var timestampMs = SystemClock.elapsedRealtime()
            val deadline = timestampMs + SETTLE_TIMEOUT_MS
            var frames = 0
            var items = pipeline.overlay.value
            while (SystemClock.elapsedRealtime() < deadline) {
                timestampMs += FRAME_INTERVAL_MS
                // submitFrame consumes the bitmap, so every frame gets its own copy.
                pipeline.submitFrame(
                    bitmap = frame.copy(Bitmap.Config.ARGB_8888, false),
                    timestampMs = timestampMs,
                    minDetectionIntervalMs = 0L,
                )
                frames += 1
                Thread.sleep(FRAME_INTERVAL_MS)
                items = pipeline.overlay.value
                if (items.any { it.label.kind != LabelKind.IDENTIFYING }) break
            }

            Log.i(
                TAG,
                "source=$source frames=$frames overlay items=${items.size} " +
                    "trackedBirds=${pipeline.stats.value.trackedBirds}",
            )
            items.forEach { Log.i(TAG, "  track=${it.trackId} box=${it.box.toShortString()} label=${it.label}") }

            assertTrue(
                "a photo the detector itself found a bird in produced no overlay item — the tracking " +
                    "or snapshot path dropped it",
                items.isNotEmpty(),
            )
            // IDENTIFYING means the classifier was never consulted. Either a species name or a
            // deliberate "bird, species unknown" proves the crop actually reached it.
            assertTrue(
                "after ${SETTLE_TIMEOUT_MS}ms of frames every label is still IDENTIFYING, so no " +
                    "classification ever completed",
                items.any { it.label.kind != LabelKind.IDENTIFYING },
            )
        } finally {
            pipeline.close()
        }
    }

    /**
     * Scales bundled samples to a realistic analysis size and returns the first one the detector
     * finds a bird in.
     *
     * The scaling is not cosmetic: the pipeline refuses to classify a crop shorter than 24px, a floor
     * calibrated for a camera frame, and a 240px-wide test photo makes the bird 12px tall — so the
     * classifier would never be consulted at all.
     */
    private fun openDetectableBirdFrame(detector: BirdDetector): Pair<Bitmap, String> {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val array = JSONObject(assets.open(SAMPLES_MANIFEST).bufferedReader().readText())
            .getJSONArray("samples")
        var listed = 0
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            if (item.isNull("species")) continue
            val file = item.getString("file")
            val decoded = assets.open(file).use { BitmapFactory.decodeStream(it) } ?: continue
            val frame = Bitmap.createScaledBitmap(decoded, 1280, 720, true)
            if (frame !== decoded) decoded.recycle()
            listed += 1
            if (detector.detectSync(frame).isNotEmpty()) return frame to file
            Log.i(TAG, "  $file: detector found no bird; trying the next bundled sample")
            frame.recycle()
        }
        error(
            "none of the $listed bundled bird photos produced a detection; that is a broken detector " +
                "or a broken export, not an unlucky photo",
        )
    }

    private companion object {
        const val TAG = "BirdPipelineOnDeviceTest"
        const val SAMPLES_MANIFEST = "samples.json"

        /** Detection interval the live loop uses; kept realistic so the throttle is exercised. */
        const val FRAME_INTERVAL_MS = 250L

        /** Generous: the CPU delegate needs ~0.8 s per frame and the vote needs two looks. */
        const val SETTLE_TIMEOUT_MS = 30_000L
    }
}
