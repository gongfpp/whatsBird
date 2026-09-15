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

package com.whatsbird.detect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.Category
import com.google.mediapipe.tasks.components.containers.Detection
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import java.io.Closeable

/** A bird box from the detector, normalised to the *upright* frame it was produced from. */
data class RawDetection(val box: RectF, val score: Float)

/**
 * Wraps MediaPipe's object detector, filtered to the COCO `bird` class so every downstream stage can
 * assume it is looking at a bird.
 *
 * Two detector instances are needed because MediaPipe pins a task to a single running mode: the
 * preview path wants LIVE_STREAM (which drops frames the graph cannot keep up with), while
 * still-photo re-identification needs the synchronous IMAGE mode.
 *
 * The bird filter is applied here rather than through `setCategoryAllowlist`, which looks like the
 * obvious API but aborts the process. MediaPipe's `TensorsToDetectionsCalculator` asserts that a
 * class-index set is either every class or every class except the background, i.e. it assumes the
 * SSD layout where class 0 is background; EfficientDet has no background class, so a one-entry
 * allowlist fails the check with `(1 vs. 89)`. That is a native `CHECK`, so no `runCatching` can
 * survive it — the process dies. Worse, it only fails on the GPU delegate, so a CPU-only test run
 * says the allowlist is fine. Filtering the returned detections instead costs nothing and cannot
 * abort: with the allowlist gone, `num_classes_` equals the model's own class count and every
 * CHECK passes on both delegates.
 *
 * Every box handed out of this class is normalised to 0..1 of the frame it came from. MediaPipe
 * itself reports pixels, and everything downstream — the crop, the overlay transform, the
 * "move closer" hint — is written against normalised coordinates, so the conversion happens here,
 * once, rather than being repeated (or forgotten) at each call site.
 */
class BirdDetector(context: Context, useGpu: Boolean) : Closeable {

    /**
     * Delivery slot for the in-flight LIVE_STREAM frame. MediaPipe hands the result back on its own
     * thread, so the bitmap it was built from must stay untouched until then — this holds that
     * reference, and [detectAsync] refuses to start a new frame while one is still out.
     */
    private var inflightBitmap: Bitmap? = null
    private var inflightCallback: ((List<RawDetection>, Bitmap) -> Unit)? = null
    private var errorCallback: ((RuntimeException) -> Unit)? = null

    /**
     * Set once by the pipeline owner (the ViewModel) so a runtime detect error can trigger a bounded
     * rebuild. Kept separate from [errorCallback], which is the per-frame callback that only knows
     * how to recycle that frame's bitmap.
     */
    private var persistentErrorListener: ((RuntimeException) -> Unit)? = null

    fun setErrorListener(listener: (RuntimeException) -> Unit) {
        persistentErrorListener = listener
    }

    private val streamDetector: ObjectDetector = build(
        context = context,
        useGpu = useGpu,
        runningMode = RunningMode.LIVE_STREAM,
        scoreThreshold = STREAM_SCORE_THRESHOLD,
        onResult = { detections ->
            val bitmap = inflightBitmap
            val callback = inflightCallback
            inflightBitmap = null
            inflightCallback = null
            if (bitmap != null && callback != null) {
                callback(detections.map { it.normalisedTo(bitmap) }, bitmap)
            }
        },
        onError = { error ->
            // The slot must be cleared here, not only on the success path: a frame that errors out
            // would otherwise leave `inflightBitmap` non-null and every later frame would be
            // rejected by detectAsync's "still in flight" guard — the preview would silently freeze
            // after a single native detection error.
            inflightBitmap = null
            inflightCallback = null
            errorCallback?.invoke(error)
            errorCallback = null
            persistentErrorListener?.invoke(error)
        },
    )

    private val imageDetector: ObjectDetector? = runCatching {
        build(
            context = context,
            useGpu = useGpu,
            runningMode = RunningMode.IMAGE,
            scoreThreshold = STILL_SCORE_THRESHOLD,
            onResult = null,
            onError = null,
        )
    }.onFailure { Log.w(TAG, "still-image detector unavailable; capture will fall back to no labels", it) }
        .getOrNull()

    /**
     * Submits one frame. [bitmap] must remain immutable until [onResult] fires — the caller keeps it
     * alive and recycles it inside the callback.
     *
     * @return true if the frame was accepted; false when the previous frame is still in flight.
     */
    fun detectAsync(
        bitmap: Bitmap,
        timestampMs: Long,
        onResult: (List<RawDetection>, Bitmap) -> Unit,
        onError: (RuntimeException) -> Unit,
    ): Boolean {
        if (inflightBitmap != null) return false
        inflightBitmap = bitmap
        inflightCallback = onResult
        errorCallback = onError
        return runCatching {
            streamDetector.detectAsync(
                BitmapImageBuilder(bitmap).build(),
                ImageProcessingOptions.builder().build(),
                timestampMs,
            )
        }.onFailure {
            inflightBitmap = null
            inflightCallback = null
            throw it
        }.isSuccess
    }

    /**
     * Synchronous detection for still photos; boxes are normalised to the upright bitmap.
     *
     * Failures are reported as [DetectionResult.Failure] rather than an empty list so the caller
     * can tell "the model works and there is no bird" from "the model could not run".
     */
    fun detectSync(bitmap: Bitmap): DetectionResult {
        val detector = imageDetector
            ?: return DetectionResult.Failure(
                IllegalStateException("still-image detector unavailable; capture will run without labels"),
            )
        return runCatching {
            detector.detect(BitmapImageBuilder(bitmap).build()).detections().mapNotNull { it.asBird() }
                .map { it.normalisedTo(bitmap) }
        }.onFailure { Log.w(TAG, "still-image detection failed", it) }.map { detections ->
            if (detections.isEmpty()) DetectionResult.NoBird else DetectionResult.Success(detections)
        }.getOrElse { DetectionResult.Failure(it) }
    }

    override fun close() {
        runCatching { streamDetector.close() }
        runCatching { imageDetector?.close() }
    }

    companion object {
        private const val TAG = "BirdDetector"
        const val ASSET_PATH = "models/bird_detector.tflite"

        /**
         * COCO label the detector is filtered on. The bundled model carries its label map in TFLite
         * metadata, so the task library reports these names directly.
         */
        private const val BIRD_CATEGORY = "bird"

        /**
         * Below the library default so partially occluded birds in foliage still surface; the
         * downstream multi-frame vote is what rejects the resulting false positives.
         */
        private const val STREAM_SCORE_THRESHOLD = 0.28f
        private const val STILL_SCORE_THRESHOLD = 0.22f

        /**
         * Applies to *all* COCO classes, not just birds, now that the allowlist is gone: the graph
         * returns the top N detections overall and the bird filter runs afterwards, so a frame with
         * a dozen people in it must still have room left for the birds behind them.
         */
        private const val MAX_RESULTS = 25

        /** Logs the labels MediaPipe actually reports, once, so a field report is diagnosable. */
        private var reportedLabels = false

        /** True when this detection is the COCO `bird` class. */
        private fun Detection.asBird(): RawDetection? {
            val category: Category = categories().maxByOrNull { it.score() } ?: return null
            val name = category.categoryName().orEmpty()
            if (!reportedLabels && name.isNotEmpty()) {
                reportedLabels = true
                Log.i(
                    TAG,
                    "detector labels available; top=${name} (${category.index()}) " +
                        "classes=${categories().size}",
                )
            }
            if (!name.equals(BIRD_CATEGORY, ignoreCase = true)) return null
            return RawDetection(RectF(boundingBox()), category.score())
        }

        /**
         * MediaPipe reports boxes in pixels of the image it was handed. Downstream code works in
         * 0..1, so leaving them as pixels silently breaks the crop (it clamps to the whole frame),
         * the overlay position and the "too small to identify" hint all at once.
         */
        private fun RawDetection.normalisedTo(bitmap: Bitmap): RawDetection {
            val width = bitmap.width
            val height = bitmap.height
            if (width <= 0 || height <= 0) return this
            return RawDetection(
                RectF(
                    (box.left / width).coerceIn(0f, 1f),
                    (box.top / height).coerceIn(0f, 1f),
                    (box.right / width).coerceIn(0f, 1f),
                    (box.bottom / height).coerceIn(0f, 1f),
                ),
                score,
            )
        }

        private fun build(
            context: Context,
            useGpu: Boolean,
            runningMode: RunningMode,
            scoreThreshold: Float,
            onResult: ((List<RawDetection>) -> Unit)?,
            onError: ((RuntimeException) -> Unit)?,
        ): ObjectDetector {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(ASSET_PATH)
                .apply { if (useGpu) setDelegate(Delegate.GPU) }
                .build()

            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(runningMode)
                .setScoreThreshold(scoreThreshold)
                .setMaxResults(MAX_RESULTS)
                .apply {
                    if (runningMode == RunningMode.LIVE_STREAM) {
                        setResultListener { result, _ ->
                            onResult?.invoke(
                                result.detections().mapNotNull { it.asBird() },
                            )
                        }
                        setErrorListener { error -> onError?.invoke(error) }
                    }
                }
                .build()

            return ObjectDetector.createFromOptions(context, options)
        }
    }
}
