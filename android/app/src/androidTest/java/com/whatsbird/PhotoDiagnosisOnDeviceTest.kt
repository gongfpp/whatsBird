package com.whatsbird

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.whatsbird.classify.SpeciesClassifier
import com.whatsbird.detect.BirdDetector
import com.whatsbird.detect.RawDetection
import com.whatsbird.pipeline.BirdPipeline
import com.whatsbird.species.SpeciesDictionary
import com.whatsbird.species.SpeciesDictionaryLoader
import com.whatsbird.util.BitmapOps
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Prints what the app's *still-photo* path concludes about a photo someone pushed onto the device.
 *
 * This exists because "识别不准" is not one bug. Eyeballing the saved `_labeled.jpg` cannot tell
 * apart the four cases that produce the same "鸟类" chip:
 *
 *  1. the detector never found a bird,
 *  2. the crop was under `BitmapOps.crop`'s 24px floor, so the classifier never ran,
 *  3. the classifier's top-1 was the right species but its score sat under the display threshold,
 *  4. the score cleared the threshold but the runner-up was within `STILL_MARGIN`, so the name was
 *     withheld.
 *
 * Cases 3 and 4 are operating-point decisions and are fixed by changing the threshold; cases 1 and
 * 2 are pipeline defects; a wrong top-1 is a model defect that only retraining fixes. The script
 * `ml/diagnose_photo.py` answers the same question on a workstation, but the macOS MediaPipe wheel
 * aborts inside `TensorsToDetectionsCalculator` (it insists on a Metal graph service that a headless
 * process cannot provide), so on this machine the device is the only faithful place to run it — and
 * it is the real target anyway.
 *
 * Photos come from the app's own external files directory, which needs no permission at all:
 *
 * ```
 * adb push photo.jpg /sdcard/Android/data/com.whatsbird.debug/files/diagnose/
 * adb shell am instrument -w \
 *   -e class com.whatsbird.PhotoDiagnosisOnDeviceTest \
 *   com.whatsbird.debug.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * With no photos staged the test skips itself, so it never reddens the normal suite.
 */
@RunWith(AndroidJUnit4::class)
class PhotoDiagnosisOnDeviceTest {

    @Test
    fun reportsWhatTheStillPathConcludesForEachStagedPhoto() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), STAGED_DIR)
        val photos = directory.listFiles { file -> file.isFile && file.name.endsWith(".jpg", true) }
            ?.sortedBy { it.name }
            .orEmpty()

        Log.i(TAG, "staged directory ${directory.absolutePath}: ${photos.size} photo(s)")
        assumeTrue(
            "no photos staged in ${directory.absolutePath}; nothing to diagnose",
            photos.isNotEmpty(),
        )

        val dictionary = SpeciesDictionaryLoader.load(context) ?: error("species.json unreadable")
        val classifier = SpeciesClassifier.create(context, dictionary) ?: error("classifier unreadable")
        val detector = BirdDetector(context, useGpu = false)
        val pipeline = BirdPipeline(detector, classifier, dictionary)

        try {
            var foundAnyBird = false
            for (photo in photos) {
                Log.i(TAG, "--- ${photo.name} (${photo.length()} bytes)")
                val decoded = BitmapFactory.decodeFile(photo.absolutePath)
                if (decoded == null) {
                    Log.w(TAG, "    decode failed")
                    continue
                }
                val detections = detector.detectSync(decoded)
                Log.i(TAG, "    detector found ${detections.size} bird(s)")
                if (detections.isNotEmpty()) foundAnyBird = true
                for ((index, detection) in detections.withIndex()) {
                    describe(detection, index + 1, decoded, pipeline, dictionary, classifier)
                }
                decoded.recycle()
            }

            // The only thing worth asserting here: on photos the detector itself found birds in,
            // the still path produced a decision. Everything else is printed for a human to read.
            assertTrue(
                "the detector found no bird in any staged photo — that is a detection problem, not " +
                    "a threshold one",
                foundAnyBird,
            )
        } finally {
            pipeline.close()
        }
    }

    private fun describe(
        detection: RawDetection,
        ordinal: Int,
        frame: Bitmap,
        pipeline: BirdPipeline,
        dictionary: SpeciesDictionary,
        classifier: SpeciesClassifier,
    ) {
        val crop = BitmapOps.crop(frame, detection.box, dictionary.cropPaddingRatio)
        if (crop == null) {
            // Reporting the pixel size is the point: it says whether the 24px floor is what rejected
            // the crop, which is a pipeline defect rather than a model one.
            val width = (detection.box.width() * frame.width).toInt()
            val height = (detection.box.height() * frame.height).toInt()
            Log.i(TAG, "    框$ordinal 检测=${"%.2f".format(detection.score)} → 裁剪被拒（约 ${width}x${height}px）")
            return
        }
        val predictions = classifier.classifyAveraged(crop)
        val verdict = pipeline.identifyStill(frame, detection.box)
        Log.i(
            TAG,
            "    框$ordinal 检测=${"%.2f".format(detection.score)} 裁剪=${crop.width}x${crop.height}px " +
                "判定=$verdict",
        )
        predictions.forEachIndexed { rank, prediction ->
            Log.i(
                TAG,
                "      ${rank + 1}. ${"%.3f".format(prediction.score)}  ${nameOf(dictionary, prediction.classIndex)}",
            )
        }
        crop.recycle()
    }

    private fun nameOf(dictionary: SpeciesDictionary, classIndex: Int): String {
        if (classIndex == dictionary.backgroundClassIndex) return "background（不在清单内）"
        val species = dictionary.speciesAt(classIndex) ?: return "<index $classIndex>"
        return "${species.chineseName} / ${species.englishName}"
    }

    private companion object {
        const val TAG = "PhotoDiagnosisOnDeviceTest"
        const val STAGED_DIR = "diagnose"
    }
}
