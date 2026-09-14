package com.whatsbird.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.view.CameraController
import androidx.exifinterface.media.ExifInterface
import com.whatsbird.detect.BirdDetector
import com.whatsbird.label.LabelKind
import com.whatsbird.label.TrackLabel
import com.whatsbird.pipeline.BirdPipeline
import com.whatsbird.settings.AppSettings
import com.whatsbird.settings.SaveMode
import com.whatsbird.species.SpeciesDictionary
import com.whatsbird.util.BitmapOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import kotlin.coroutines.resume

sealed interface CaptureOutcome {
    data class Success(
        val original: SaveResult.Saved?,
        val labeled: SaveResult.Saved?,
        /** Species names resolved from the *photo*, not from whatever the preview had shown. */
        val identifiedNames: List<String>,
        val birdCount: Int,
        val labeledFailed: Boolean,
        /**
         * True when the label step could not run because the model was unavailable (not because the
         * model ran and found nothing). Lets the UI say "saved original, model was unavailable"
         * rather than the ambiguous "no bird found".
         */
        val modelUnavailable: Boolean = false,
    ) : CaptureOutcome

    data class Failure(val reason: FailureReason, val detail: String? = null) : CaptureOutcome

    enum class FailureReason { CAMERA, OUT_OF_SPACE, SAVE_FAILED, DECODE_FAILED }
}

/**
 * Turns a shutter press into files on disk.
 *
 * The saved labels are computed from the captured still, not copied from the preview overlay: the
 * user sees the frame before the shutter actually fires, so a moving bird would otherwise be
 * annotated in the wrong place. If re-identification fails the original is still kept, and the
 * caller is told the labeled copy did not happen rather than being shown a stale name.
 *
 * The two saves have very different costs. Writing the original is a straight copy of bytes the
 * camera already produced; building the annotated copy means decoding, detecting, classifying,
 * drawing and re-encoding. Since neither depends on the other, the original is written on the IO
 * executor while the analysis runs — on the reference device that hides about a second and a half
 * of gallery write behind work that was going to happen anyway.
 *
 * Every stage is timed and reported on one line. A shutter press is the app's slowest path by far
 * and the only one the user waits on, so "which stage ate the second" has to be answerable from a
 * log rather than by guessing.
 */
class PhotoCapture(
    private val controller: CameraController,
    private val detector: BirdDetector?,
    private val pipeline: BirdPipeline?,
    private val dictionary: SpeciesDictionary?,
    private val saver: MediaStoreSaver,
    private val ioExecutor: ExecutorService,
) {

    private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)

    suspend fun capture(settings: AppSettings): CaptureOutcome {
        val shutter = StageLog()
        val proxy = takePicture() ?: return CaptureOutcome.Failure(CaptureOutcome.FailureReason.CAMERA)
        shutter.mark("shutter")
        val jpegBytes = proxy.use { bufferToBytes(it) }
            ?: return CaptureOutcome.Failure(CaptureOutcome.FailureReason.DECODE_FAILED)
        shutter.mark("read")

        val name = "whatsbird_${stamp.format(Date())}"
        return withContext(Dispatchers.Default) {
            process(name, jpegBytes, settings, shutter)
        }
    }

    private fun process(
        name: String,
        jpegBytes: ByteArray,
        settings: AppSettings,
        stages: StageLog,
    ): CaptureOutcome {
        val labeledRequested = settings.saveMode != SaveMode.ORIGINAL

        // Kicked off first so it overlaps everything below.
        val pendingOriginal: Future<SaveResult>? = if (settings.saveMode != SaveMode.LABELED) {
            ioExecutor.submit<SaveResult> { saver.saveJpeg(jpegBytes, "$name.jpg") }
        } else {
            null
        }

        val decoded = decodeBounded(jpegBytes, MAX_DECODE_LONG_SIDE)
        if (decoded == null) {
            return resolve(
                original = awaitOriginal(pendingOriginal, stages),
                labeled = null,
                labeledRequested = labeledRequested,
                annotations = emptyList(),
                labeledFailed = true,
                reason = CaptureOutcome.FailureReason.DECODE_FAILED,
                modelUnavailable = false,
            )
        }
        val upright = BitmapOps.rotate(decoded, exifRotation(jpegBytes))
        val frameSize = "${upright.width}x${upright.height}"
        stages.mark("decode+rotate")

        val (annotations, modelUnavailable) = identify(upright, stages)

        var labeled: SaveResult.Saved? = null
        var labeledFailed = false
        var reason: CaptureOutcome.FailureReason? = null
        if (labeledRequested) {
            val rendered = render(upright, annotations)
            stages.mark("render")
            when (val result = saver.saveBitmap(rendered, "${name}_labeled.jpg")) {
                is SaveResult.Saved -> labeled = result
                SaveResult.OutOfSpace -> reason = CaptureOutcome.FailureReason.OUT_OF_SPACE
                is SaveResult.Failed -> {
                    Log.w(TAG, "labeled copy failed: ${result.message}")
                    labeledFailed = true
                }
            }
            stages.mark("saveLabeled")
            rendered.recycle()
        }
        upright.recycle()

        val original = awaitOriginal(pendingOriginal, stages)
        Log.i(TAG, "capture $name frame=$frameSize birds=${annotations.size} modelUnavailable=$modelUnavailable $stages")

        return resolve(original, labeled, labeledRequested, annotations, labeledFailed, reason, modelUnavailable)
    }

    /** Collects the background original write and records how long it was still outstanding. */
    private fun awaitOriginal(pending: Future<SaveResult>?, stages: StageLog): SaveResult? {
        if (pending == null) return null
        val result = runCatching { pending.get() }
            .onFailure { Log.w(TAG, "original write failed", it) }
            .getOrNull()
        stages.mark("awaitOriginal")
        return result
    }

    private fun resolve(
        original: SaveResult?,
        labeled: SaveResult.Saved?,
        labeledRequested: Boolean,
        annotations: List<Annotation>,
        labeledFailed: Boolean,
        reason: CaptureOutcome.FailureReason?,
        modelUnavailable: Boolean,
    ): CaptureOutcome {
        val savedOriginal = original as? SaveResult.Saved
        val names = annotations.mapNotNull { if (it.label.kind == LabelKind.CONFIRMED) it.name else null }

        // Anything on disk beats a hard failure: the user pressed the shutter for a photo, and a
        // missing annotation is a smaller disappointment than a missing photo.
        if (savedOriginal != null || labeled != null) {
            return CaptureOutcome.Success(
                original = savedOriginal,
                labeled = labeled,
                identifiedNames = names,
                birdCount = annotations.size,
                labeledFailed = labeledFailed || (labeledRequested && labeled == null),
                // Only meaningful when we could not produce a label because the model was missing,
                // not when the model ran and simply found nothing.
                modelUnavailable = modelUnavailable && labeled == null,
            )
        }

        return when {
            original is SaveResult.OutOfSpace || reason == CaptureOutcome.FailureReason.OUT_OF_SPACE ->
                CaptureOutcome.Failure(CaptureOutcome.FailureReason.OUT_OF_SPACE)

            original is SaveResult.Failed ->
                CaptureOutcome.Failure(CaptureOutcome.FailureReason.SAVE_FAILED, original.message)

            reason != null -> CaptureOutcome.Failure(reason)

            else -> CaptureOutcome.Failure(CaptureOutcome.FailureReason.SAVE_FAILED)
        }
    }

    private data class Annotation(val box: RectF, val label: TrackLabel, val name: String?)

    /** Re-detects and re-classifies the still; a single frame gets no multi-frame vote. */
    private fun identify(frame: Bitmap, stages: StageLog): Pair<List<Annotation>, Boolean> {
        val detector = detector
        val pipeline = pipeline
        // The model being absent is a different outcome from "the model ran and found nothing": signal
        // it so the caller can tell the user "saved original, model unavailable" instead of the
        // ambiguous "no bird found".
        if (detector == null || pipeline == null) return emptyList<Annotation>() to true
        val detections = detector.detectSync(frame)
        stages.mark("detect(n=${detections.size})")
        val annotations = detections.map { detection ->
            val label = pipeline.identifyStill(frame, detection.box)
            val species = label.classIndex?.let { dictionary?.speciesAt(it) }
            Annotation(detection.box, label, species?.chineseName ?: species?.englishName)
        }.also { stages.mark("classify(n=${it.size})") }
        return annotations to false
    }

    private fun render(frame: Bitmap, annotations: List<Annotation>): Bitmap {
        val output = frame.copy(Bitmap.Config.ARGB_8888, true)
        if (annotations.isEmpty()) return output

        val canvas = Canvas(output)
        val stroke = maxOf(output.width * 0.004f, 3f)
        val textSize = maxOf(output.width * 0.038f, 26f)
        val padding = textSize * 0.35f

        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            color = Color.WHITE
        }
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke * 2.2f
            color = 0x66000000.toInt()
        }
        val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC0B2018.toInt() }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            isFakeBoldText = true
        }

        // Chips are stacked as they are placed, so two birds whose boxes sit on top of each other do
        // not end up with their names drawn over one another — which reads as a single garbled name.
        val placed = ArrayList<RectF>(annotations.size)

        for (annotation in annotations) {
            val rect = RectF(
                annotation.box.left * output.width,
                annotation.box.top * output.height,
                annotation.box.right * output.width,
                annotation.box.bottom * output.height,
            )
            canvas.drawRect(rect, shadowPaint)
            canvas.drawRect(rect, boxPaint)

            val text = annotation.name ?: "鸟类"
            val textWidth = textPaint.measureText(text)
            var top = rect.top - textSize - padding * 2
            if (top < 0f) top = rect.bottom + padding
            top = top.coerceIn(0f, output.height - textSize - padding * 2)
            val height = textSize + padding * 2
            val left = rect.left.coerceIn(0f, (output.width - textWidth - padding * 2).coerceAtLeast(0f))

            // Slide the chip up past anything already drawn, the way the live overlay does.
            var attempts = 0
            while (attempts < 8 &&
                placed.any { it.intersects(left, top, left + textWidth + padding * 2, top + height) }
            ) {
                top = (top - height - 2f).coerceAtLeast(0f)
                attempts += 1
            }

            val background = RectF(
                left,
                top,
                (left + textWidth + padding * 2).coerceAtMost(output.width.toFloat()),
                top + height,
            )
            canvas.drawRoundRect(background, padding, padding, labelBg)
            canvas.drawText(text, background.left + padding, background.bottom - padding * 1.2f, textPaint)
            placed += background
        }
        return output
    }

    private suspend fun takePicture(): ImageProxy? = suspendCancellableCoroutine { continuation ->
        controller.takePicture(ioExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                if (continuation.isActive) continuation.resume(image) else image.close()
            }

            override fun onError(exception: ImageCaptureException) {
                Log.w(TAG, "capture failed", exception)
                if (continuation.isActive) continuation.resume(null)
            }
        })
    }

    /**
     * Decodes the JPEG at, or just above, [maxLongSide]. The capture resolution selector is supposed
     * to keep the stream small, but this is the backstop for a device that ignores it: a full decode
     * of a 64MP sensor photo is ~256 MB, and the rotate and annotation copies multiply that.
     */
    private fun decodeBounded(jpegBytes: ByteArray, maxLongSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, bounds)
        val longSide = maxOf(bounds.outWidth, bounds.outHeight)
        if (longSide <= 0) return null

        var sample = 1
        while (longSide / sample > maxLongSide) sample *= 2
        return BitmapFactory.decodeByteArray(
            jpegBytes,
            0,
            jpegBytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }

    private fun bufferToBytes(proxy: ImageProxy): ByteArray? = runCatching {
        val buffer = proxy.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        bytes
    }.getOrNull()

    private fun exifRotation(jpegBytes: ByteArray): Int = runCatching {
        val exif = ExifInterface(ByteArrayInputStream(jpegBytes))
        when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)

    /** Wall-clock breakdown of one shutter press, reported as a single log line. */
    private class StageLog {
        private val started = SystemClock.elapsedRealtime()
        private var last = started
        private val parts = StringBuilder()

        fun mark(stage: String) {
            val now = SystemClock.elapsedRealtime()
            if (parts.isNotEmpty()) parts.append(' ')
            parts.append(stage).append('=').append(now - last).append("ms")
            last = now
        }

        override fun toString(): String = "total=${SystemClock.elapsedRealtime() - started}ms $parts"
    }

    companion object {
        private const val TAG = "PhotoCapture"

        /** Matches CameraConfigurator's CAPTURE_TARGET; keeps decode cost bounded on any device. */
        private const val MAX_DECODE_LONG_SIDE = 2560
    }
}
