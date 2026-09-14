package com.whatsbird.camera

import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.whatsbird.pipeline.BirdPipeline
import com.whatsbird.util.BitmapOps

/**
 * Turns CameraX analysis frames into upright bitmaps and hands them to the pipeline.
 *
 * Runs on the single-threaded executor CameraX was given, so MediaPipe is never entered
 * concurrently. The frame is rotated here rather than inside MediaPipe so detection boxes,
 * classification crops, overlay labels and saved photos all share one coordinate space.
 *
 * The pipeline is resolved per frame through [pipelineProvider] so switching the GPU preference —
 * which rebuilds the pipeline — does not require reconfiguring CameraX.
 */
class FrameAnalyzer(
    private val pipelineProvider: () -> BirdPipeline?,
    private val minDetectionIntervalMs: () -> Long,
    private val onFrameGeometry: (width: Int, height: Int) -> Unit,
) : ImageAnalysis.Analyzer {

    override fun analyze(image: ImageProxy) {
        val timestampMs: Long
        val upright: Bitmap
        try {
            timestampMs = image.imageInfo.timestamp / 1_000_000L
            val raw = image.toBitmap()
            upright = BitmapOps.rotate(raw, image.imageInfo.rotationDegrees)
            onFrameGeometry(upright.width, upright.height)
        } catch (t: Throwable) {
            Log.w(TAG, "failed to convert analysis frame", t)
            return
        } finally {
            image.close()
        }

        val pipeline = pipelineProvider()
        if (pipeline == null) {
            upright.recycle()
            return
        }
        pipeline.submitFrame(
            bitmap = upright,
            timestampMs = timestampMs,
            minDetectionIntervalMs = minDetectionIntervalMs(),
        )
    }

    companion object {
        private const val TAG = "FrameAnalyzer"
    }
}
