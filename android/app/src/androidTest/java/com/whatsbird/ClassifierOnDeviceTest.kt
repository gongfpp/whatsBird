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

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.whatsbird.classify.SpeciesClassifier
import com.whatsbird.detect.BirdDetector
import com.whatsbird.settings.AppSettings
import com.whatsbird.species.SpeciesDictionary
import com.whatsbird.species.SpeciesDictionaryLoader
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the *shipped* classifier on the actual phone, over thirty-six held-out iNaturalist photos:
 * thirty of listed species and six that the list does not contain.
 *
 * Why this exists rather than relying on the offline numbers in `MODEL_CARD.md`: the offline
 * evaluation happens in the Python TFLite runtime, which is a different LiteRT build with different
 * delegate behaviour (the int8 graph is rejected by XNNPACK there). Only running the packaged model
 * on the packaged runtime answers "does identification work on the device", and this test answers
 * it without needing somebody to point the camera at a live bird.
 *
 * The samples come from the `test` split of `ml/data`, which the model never saw during training or
 * tuning, so the accuracy asserted here is not training-set recall.
 */
@RunWith(AndroidJUnit4::class)
class ClassifierOnDeviceTest {

    @Test
    fun shippedClassifierIdentifiesHeldOutPhotos() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appContext = instrumentation.targetContext

        val dictionary = SpeciesDictionaryLoader.load(appContext)
        assertNotNull("assets/models/species.json must be readable on device", dictionary)
        val dict = dictionary!!
        assertTrue("species.json declares no classes", dict.classCount > 0)

        val classifier = SpeciesClassifier.create(appContext, dict)
        assertNotNull(
            "assets/models/bird_classifier.tflite did not load on this device; " +
                "the app would silently drop to detection-only",
            classifier,
        )

        val samples = samples()
        assertTrue("no bundled verification samples", samples.size >= 10)

        // The shipped default, not a number copied into the test, so this keeps measuring what users get.
        val threshold = AppSettings().confidenceThreshold
        var speciesSeen = 0
        var speciesCorrect = 0
        var backgroundSeen = 0
        var backgroundReported = 0
        val rows = ArrayList<String>()

        for (sample in samples) {
            val bytes = instrumentation.context.assets.open(sample.file).use { it.readBytes() }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertNotNull("could not decode ${sample.file}", bitmap)

            val predictions = when (val classified = classifier!!.classify(bitmap!!)) {
                is com.whatsbird.classify.ClassificationResult.Success -> classified.predictions
                else -> error("classify() failed or produced no ranking for ${sample.file}: $classified")
            }
            bitmap.recycle()
            assertTrue("classify() returned no predictions for ${sample.file}", predictions.isNotEmpty())

            val scores = predictions.map { it.score }
            assertTrue(
                "scores must descend for ${sample.file}: $scores",
                scores.zipWithNext().all { (first, second) -> first >= second },
            )
            assertTrue(
                "scores must be probabilities for ${sample.file}: $scores",
                scores.all { it in 0f..1f },
            )

            val top = predictions.first()
            val shown = top.classIndex != dict.backgroundClassIndex && top.score >= threshold
            val label = if (sample.species != null) {
                dict.classes.firstOrNull { it.scientificName == sample.species }?.chineseName ?: sample.species
            } else {
                "background(${sample.taxonName})"
            }

            if (sample.species != null) {
                speciesSeen++
                val expectedIndex = dict.classes.first { it.scientificName == sample.species }.index
                if (top.classIndex == expectedIndex) speciesCorrect++
                rows += "  ${sample.file}  expect=$label(idx=$expectedIndex)  " +
                    "top=${dict.speciesAt(top.classIndex)?.chineseName ?: "background"} " +
                    "p=%.3f".format(top.score) + if (top.classIndex == expectedIndex) "  OK" else "  MISS"
            } else {
                backgroundSeen++
                if (shown) backgroundReported++
                rows += "  ${sample.file}  expect=$label (not a listed species)  " +
                    "top=${dict.speciesAt(top.classIndex)?.chineseName ?: "background"} " +
                    "p=%.3f".format(top.score) + if (shown) "  FALSE-REPORT" else "  OK"
            }
        }

        val speciesAccuracy = if (speciesSeen > 0) speciesCorrect.toFloat() / speciesSeen else 0f
        val backgroundFalseReport = if (backgroundSeen > 0) backgroundReported.toFloat() / backgroundSeen else 0f

        // Latency is the only honest reason to accept a lossy quantisation, so it is measured on the
        // shipped graph on the real device rather than assumed. Note it also captures whether the
        // graph got a delegate: an int8 model that XNNPACK rejects runs on plain kernels and loses
        // the speed the quantisation was supposed to buy.
        val timing = samples.take(3).map { sample ->
            instrumentation.context.assets.open(sample.file).use { BitmapFactory.decodeStream(it) }!!
        }
        repeat(5) { timing.forEach { classifier!!.classify(it) } }
        var runs = 0
        val startedNs = System.nanoTime()
        repeat(15) { timing.forEach { classifier!!.classify(it); runs++ } }
        val msPerCrop = (System.nanoTime() - startedNs) / 1e6 / runs
        timing.forEach { it.recycle() }

        val report = buildString {
            appendLine("on-device classifier report (model=${dict.modelVersion})")
            appendLine("  listed-species top-1: $speciesCorrect/$speciesSeen = %.1f%%".format(speciesAccuracy * 100))
            appendLine(
                "  background shown as a species (p>=$threshold): " +
                    "$backgroundReported/$backgroundSeen = %.1f%%".format(backgroundFalseReport * 100),
            )
            appendLine("  latency: %.1f ms per classified crop (n=$runs)".format(msPerCrop))
            rows.forEach { appendLine(it) }
        }
        Log.i(TAG, report)
        report.lineSequence().forEach { Log.i(TAG, it) }

        // This floor exists to catch a *broken export* — scrambled class order, a mis-quantised graph,
        // a preprocessing mismatch — not to certify an accuracy figure. Those failures land at or
        // below chance (1/52 ≈ 2%), so 30% is far above any breakage signal and far below the ~47%
        // the model actually measures here. Real accuracy is tracked in docs/MODEL_CARD.md, which has
        // 829 test images behind it instead of thirty.
        assertTrue(
            // Parenthesised: "a" + "b".format(x) formats only "b" in Kotlin, which would leave the
            // %.1f%% in the first half unformatted and drop the accuracy from the failure message.
            (
                "listed-species top-1 accuracy collapsed to %.1f%% ($speciesCorrect/$speciesSeen), " +
                    "which points at a broken export rather than a weak model"
                ).format(speciesAccuracy * 100),
            speciesAccuracy >= 0.30f,
        )
        assertTrue(
            "out-of-list photos were reported as listed species %.1f%% of the time".format(
                backgroundFalseReport * 100,
            ),
            backgroundFalseReport <= 0.5f,
        )
    }

    /**
     * Checks the other half of the pipeline: MediaPipe's COCO detector actually produces bird boxes
     * for real photos. The camera feed cannot be driven from a test, so this is what stands in for
     * "point the phone at a bird" on the detection side.
     */
    @Test
    fun detectorFindsBirdsInHeldOutPhotos() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val detector = BirdDetector(instrumentation.targetContext, useGpu = false)
        try {
            val speciesSamples = samples().filter { it.species != null }
            var withDetection = 0
            for (sample in speciesSamples) {
                val bitmap = instrumentation.context.assets.open(sample.file).use { stream ->
                    BitmapFactory.decodeStream(stream)
                } ?: error("could not decode ${sample.file}")
                val detections = when (val outcome = detector.detectSync(bitmap)) {
                    is com.whatsbird.detect.DetectionResult.Success -> outcome.detections
                    else -> emptyList()
                }
                bitmap.recycle()
                if (detections.isNotEmpty()) withDetection++
                val best = detections.maxByOrNull { it.score }
                Log.i(
                    TAG,
                    "  ${sample.file}  boxes=${detections.size}  " +
                        (best?.let { "best=%.2f %s".format(it.score, it.box.toShortString()) } ?: "no bird found"),
                )
            }
            val rate = withDetection.toFloat() / speciesSamples.size
            Log.i(
                TAG,
                "on-device detector report: $withDetection/${speciesSamples.size} held-out photos produced a bird box " +
                    "(%.1f%%)".format(rate * 100),
            )

            // Detector cost, measured the same way as the classifier's: this is the number that
            // decides whether a larger backbone (EfficientDet-Lite2 at 448px instead of Lite0 at
            // 320px) fits inside the 8 analyses per second the preview is throttled to.
            val timingBitmap = instrumentation.context.assets
                .open(speciesSamples.first().file).use { BitmapFactory.decodeStream(it) }!!
            repeat(3) { detector.detectSync(timingBitmap) }
            var runs = 0
            val startedNs = System.nanoTime()
            repeat(10) { detector.detectSync(timingBitmap); runs++ }
            val msPerDetect = (System.nanoTime() - startedNs) / 1e6 / runs
            timingBitmap.recycle()
            Log.i(TAG, "on-device detector latency: %.1f ms per frame (n=$runs)".format(msPerDetect))

            // Not every iNaturalist photo frames the bird as a detector-sized subject — some are
            // distant, heavily occluded, or dominated by foliage — so this floor is about "the
            // detector asset loads and works", not a recall figure.
            assertTrue(
                "detector produced a bird box for only %.1f%% of held-out photos".format(rate * 100),
                rate >= 0.5f,
            )
        } finally {
            detector.close()
        }
    }

    /**
     * Detector cost per delegate, measured on a frame the size the preview actually analyses.
     *
     * The preview is throttled to `targetDetectionsPerSecond` (8 by default) and refuses to start a
     * new frame while one is in flight, so a detector slower than ~125 ms cannot keep up no matter
     * what the throttle says. This number is what decides whether a heavier detector model is
     * shippable and whether the GPU delegate has to be the default rather than an option.
     */
    @Test
    fun detectorLatencyByDelegate() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val sample = samples().first { it.species != null }
        val decoded = instrumentation.context.assets.open(sample.file).use { BitmapFactory.decodeStream(it) }!!
        val frame = Bitmap.createScaledBitmap(decoded, 1280, 960, true)
        decoded.recycle()

        for (useGpu in listOf(false, true)) {
            val detector = runCatching { BirdDetector(context, useGpu) }.getOrNull()
            if (detector == null) {
                Log.i(TAG, "detector delegate gpu=$useGpu: unavailable")
                continue
            }
            try {
                repeat(2) { detector.detectSync(frame) }
                var runs = 0
                val startedNs = System.nanoTime()
                repeat(8) { detector.detectSync(frame); runs++ }
                val ms = (System.nanoTime() - startedNs) / 1e6 / runs
                Log.i(TAG, "detector delegate gpu=$useGpu: %.1f ms per 1280x960 frame (n=$runs)".format(ms))
            } finally {
                detector.close()
            }
        }
        frame.recycle()
    }

    private data class Sample(
        val file: String,
        val species: String?,
        val taxonName: String,
    )

    private fun samples(): List<Sample> {
        val json = InstrumentationRegistry.getInstrumentation().context.assets
            .open(SAMPLES_MANIFEST).bufferedReader().readText()
        val array = JSONObject(json).getJSONArray("samples")
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            Sample(
                file = item.getString("file"),
                species = if (item.isNull("species")) null else item.getString("species"),
                taxonName = item.optString("taxon", item.optString("species")),
            )
        }
    }

    private companion object {
        const val TAG = "ClassifierOnDeviceTest"
        const val SAMPLES_MANIFEST = "samples.json"
    }
}
