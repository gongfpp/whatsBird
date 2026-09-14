package com.whatsbird.pipeline

import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import com.whatsbird.classify.Prediction
import com.whatsbird.classify.SpeciesClassifier
import com.whatsbird.detect.BirdDetector
import com.whatsbird.label.LabelKind
import com.whatsbird.label.LabelStabilizer
import com.whatsbird.label.TrackLabel
import com.whatsbird.settings.AppSettings
import com.whatsbird.species.Species
import com.whatsbird.species.SpeciesDictionary
import com.whatsbird.track.BirdTracker
import com.whatsbird.util.BitmapOps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** One bird as the overlay layer should draw it. */
data class OverlayItem(
    val trackId: Int,
    val box: RectF,
    val label: TrackLabel,
    val species: Species?,
    val tooSmall: Boolean,
)

enum class ModelStatus { LOADING, READY, DETECTOR_ONLY, FAILED }

data class PipelineStats(
    /** Frames handed to the pipeline by the camera, before any throttle or detector. */
    val previewFps: Float = 0f,
    /** Detections that actually completed inference — dropped/throttled frames are NOT counted. */
    val detectionFps: Float = 0f,
    /** Classifications that actually ran to completion. */
    val classificationFps: Float = 0f,
    val detectionLatencyMs: Float = 0f,
    val classificationLatencyMs: Float = 0f,
    /** Cumulative dropped/throttled frames since the pipeline started. */
    val droppedFrames: Int = 0,
    val trackedBirds: Int = 0,
    /** Latency from the first detection result to the first stable (CONFIRMED) species name. */
    val firstStableNameMs: Long = 0L,
)

/**
 * Owns the frame → label chain: detect, associate, classify on demand, stabilise, publish.
 *
 * Detection and classification run at deliberately different rates. Detection fires on every
 * accepted frame; classification is scheduled per bird only when it is new, stale, still uncertain,
 * or has grown noticeably. That is what keeps a five-bird frame from running the classifier five
 * times per frame.
 *
 * Threading: this class is driven from three threads — CameraX's analysis thread ([submitFrame]),
 * MediaPipe's result thread (the `onResult` callback, which runs [handleDetections]) and the single
 * classification thread ([drainQueue]). Anything they share is either a concurrent collection, a
 * `@Volatile` field, or a class that serialises its own entry points.
 */
class BirdPipeline(
    private val detector: BirdDetector?,
    private val classifier: SpeciesClassifier?,
    private val dictionary: SpeciesDictionary?,
    private val tracker: BirdTracker = BirdTracker(),
    private val stabilizer: LabelStabilizer = LabelStabilizer(
        backgroundClassIndex = dictionary?.backgroundClassIndex ?: -1,
    ),
) : Closeable {

    private val _overlay = MutableStateFlow<List<OverlayItem>>(emptyList())
    val overlay: StateFlow<List<OverlayItem>> = _overlay.asStateFlow()

    private val _status = MutableStateFlow(
        when {
            detector == null -> ModelStatus.FAILED
            classifier == null -> ModelStatus.DETECTOR_ONLY
            else -> ModelStatus.READY
        },
    )
    val status: StateFlow<ModelStatus> = _status.asStateFlow()

    init {
        // The UI shows a single chip for "something is missing"; this is the line that says which
        // piece, so a field report can be diagnosed without a rebuild.
        Log.i(
            TAG,
            "pipeline ready status=${_status.value} detector=${detector != null} " +
                "classifier=${classifier != null} dictionary=${dictionary != null}",
        )
    }

    private val _stats = MutableStateFlow(PipelineStats())
    val stats: StateFlow<PipelineStats> = _stats.asStateFlow()

    /**
     * Flips once the detector has actually completed an inference. Detection is the first stage that
     * touches native code, so this is the earliest point at which "the models work on this device"
     * is a fact rather than a hope — and therefore the point at which [BootGuard] can be disarmed.
     */
    private val _firstResultSeen = MutableStateFlow(false)
    val firstResultSeen: StateFlow<Boolean> = _firstResultSeen.asStateFlow()

    /**
     * DiscardPolicy rather than the default AbortPolicy. [close] shuts this executor down while
     * MediaPipe may still have a detection result in flight, and that result is delivered on
     * MediaPipe's own thread — an abort there throws off the app's call stack and takes the process
     * down during an otherwise orderly shutdown.
     */
    private val classifyExecutor: ExecutorService = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue<Runnable>(),
        { Thread(it, "wb-classify") },
        ThreadPoolExecutor.DiscardPolicy(),
    )

    private val queue = ConcurrentLinkedDeque<ClassifyJob>()
    private val draining = AtomicBoolean(false)

    private class ClassifyJob(
        val trackId: Int,
        val timestampMs: Long,
        val crop: Bitmap,
        val priority: Int,
        val areaFraction: Float,
    )

    /**
     * Per-track classify bookkeeping. Read on the detection path while the classification thread
     * writes back, so the fields are volatile.
     */
    private class TrackMemory {
        @Volatile var lastClassifyMs = 0L
        @Volatile var lastArea = 0f
        @Volatile var enqueued = false
    }

    /**
     * Written from the MediaPipe result thread ([handleDetections]) and read from the classification
     * thread ([drainQueue]). Those overlap for as long as a bird is on screen, which is exactly when
     * a plain [HashMap] would be corrupted.
     */
    private val memory = ConcurrentHashMap<Int, TrackMemory>()

    private var lastDetectionMs = 0L
    private val framesReceived = AtomicInteger(0)
    private val framesDetected = AtomicInteger(0)
    private val classificationsDone = AtomicInteger(0)
    private val droppedFrames = AtomicInteger(0)
    private var detectionLatency = 0f
    private var classificationLatency = 0f
    private var statsWindowStart = SystemClock.elapsedRealtime()
    private var lastStatsLogMs = 0L

    /** Set once, on the first detection result, as t0 for the first-stable-name latency. */
    private val firstResultCaptured = AtomicBoolean(false)
    private var firstResultMs = 0L
    private var firstStableNameMs = 0L
    private val statsLock = Any()

    /**
     * Feed one upright frame. [bitmap] is consumed by this call — it is recycled once the detector
     * and any crops derived from it are done with it.
     *
     * @param minDetectionIntervalMs throttle so the detector never saturates the analysis thread.
     */
    fun submitFrame(bitmap: Bitmap, timestampMs: Long, minDetectionIntervalMs: Long) {
        val now = SystemClock.elapsedRealtime()
        // Counted up-front so the preview-FPS figure reflects frames the camera actually delivered,
        // independent of the throttle and of whether the detector accepted them.
        framesReceived.incrementAndGet()
        if (detector == null) {
            bitmap.recycle()
            return
        }
        if (now - lastDetectionMs < minDetectionIntervalMs) {
            droppedFrames.incrementAndGet()
            bitmap.recycle()
            updateStats(now)
            return
        }
        lastDetectionMs = now

        val accepted = detector.detectAsync(
            bitmap = bitmap,
            timestampMs = timestampMs,
            onResult = { detections, frame ->
                // This runs on MediaPipe's result thread, which the app does not own: an exception
                // escaping here kills the process instead of dropping one frame.
                try {
                    val detectDone = SystemClock.elapsedRealtime()
                    detectionLatency = detectionLatency * 0.8f + (detectDone - now) * 0.2f
                    framesDetected.incrementAndGet()
                    handleDetections(detections, frame, timestampMs)
                    updateStats(detectDone)
                } catch (t: Throwable) {
                    Log.e(TAG, "handling detections failed", t)
                    runCatching { frame.recycle() }
                }
            },
            onError = { error ->
                Log.w(TAG, "detection error", error)
                bitmap.recycle()
            },
        )
        if (!accepted) {
            droppedFrames.incrementAndGet()
            bitmap.recycle()
            updateStats(now)
        }
    }

    private fun handleDetections(
        detections: List<com.whatsbird.detect.RawDetection>,
        frame: Bitmap,
        timestampMs: Long,
    ) {
        if (firstResultCaptured.compareAndSet(false, true)) {
            firstResultMs = SystemClock.elapsedRealtime()
        }
        _firstResultSeen.value = true
        val tracks = tracker.update(detections, timestampMs)

        // Tracks that expired keep their vote history and crops otherwise — over a long scan that
        // is an unbounded leak, and a recycled track id would inherit a stale species name.
        val liveIds = tracks.mapTo(HashSet(tracks.size)) { it.id }
        val departed = memory.keys.filterNot { it in liveIds }
        for (id in departed) {
            memory.remove(id)
            stabilizer.forget(id)
        }

        val padded = dictionary?.cropPaddingRatio ?: DEFAULT_CROP_PADDING
        var enqueuedThisFrame = 0

        for (track in tracks) {
            if (track.missedFrames > 0) continue
            val area = BitmapOps.areaFraction(track.box)
            val mem = memory.computeIfAbsent(track.id) { TrackMemory() }

            // A bird filling under a fifth of a percent of the frame carries too few pixels to
            // classify; saying so is more useful than guessing.
            if (area < MIN_CLASSIFY_AREA || classifier == null) continue
            if (enqueuedThisFrame >= MAX_CLASSIFICATIONS_PER_FRAME) break
            if (mem.enqueued) continue

            val age = timestampMs - mem.lastClassifyMs
            val grew = mem.lastArea > 0f && area > mem.lastArea * GROWTH_TRIGGER
            val priority = when {
                mem.lastClassifyMs == 0L -> 0
                // Still short of the votes a label needs. Without this the second look only arrives
                // after RECLASSIFY_INTERVAL_MS, so the earliest a bird could ever be named was
                // 1.5 s — long enough that the app reads as "it does not recognise anything".
                // [TrackMemory.enqueued] still caps this at one outstanding job per bird, so the
                // warmer does not turn into a queue of duplicate work.
                stabilizer.voteCount(track.id) < stabilizer.minSamples -> 1
                grew -> 2
                age > RECLASSIFY_INTERVAL_MS -> 3
                else -> continue
            }

            val crop = BitmapOps.crop(frame, track.box, padded) ?: continue
            mem.enqueued = true
            mem.lastArea = area
            enqueuedThisFrame += 1
            queue.addLast(ClassifyJob(track.id, timestampMs, crop, priority, area))
        }

        frame.recycle()
        drainQueue()
        publishOverlay(SystemClock.elapsedRealtime())
    }

    private fun drainQueue() {
        if (!draining.compareAndSet(false, true)) return
        classifyExecutor.execute {
            var failed = false
            try {
                var processed = 0
                while (processed < MAX_DRAIN_PER_CYCLE) {
                    val job = pollBest() ?: break
                    val mem = memory[job.trackId]
                    if (mem == null) {
                        // Track already departed; nothing to reset, just free the crop.
                        Log.i(TAG, "dropping classify job for departed track=${job.trackId}")
                        job.crop.recycle()
                        continue
                    }
                    val track = tracker.find(job.trackId)
                    if (track == null || track.missedFrames > 0) {
                        // The track is alive but currently missed (brief occlusion, camera pause, a
                        // frame the detector dropped). Release the in-flight hold so it can be
                        // re-queued when the bird is seen again. Leaving `enqueued` true here is
                        // exactly what made "brief miss → never classified" reproducible:
                        // handleDetections then skips this track forever.
                        mem.enqueued = false
                        job.crop.recycle()
                        continue
                    }
                    val classifier = classifier ?: run {
                        mem.enqueued = false
                        job.crop.recycle()
                        break
                    }

                    val started = SystemClock.elapsedRealtime()
                    val predictions = try {
                        classifier.classify(job.crop)
                    } catch (t: Throwable) {
                        failed = true
                        Log.e(TAG, "classify failed for track=${job.trackId}", t)
                        mem.enqueued = false
                        job.crop.recycle()
                        break
                    }
                    val took = SystemClock.elapsedRealtime() - started
                    classificationLatency = classificationLatency * 0.7f + took * 0.3f
                    job.crop.recycle()

                    mem.enqueued = false
                    mem.lastClassifyMs = job.timestampMs
                    classificationsDone.incrementAndGet()
                    if (predictions.isNotEmpty()) {
                        // One line per classification (~1-2/s at the live throttle). This is the only
                        // place that says what the model actually answered, as opposed to what the
                        // vote did with it — without it, "the name is wrong" cannot be split into
                        // "bad model" versus "bad voting".
                        val top = predictions.first()
                        Log.i(TAG, "classify track=${job.trackId} ${took}ms top=${top.classIndex}:${"%.3f".format(top.score)}")
                        stabilizer.observe(job.trackId, predictions, job.timestampMs)
                    } else {
                        Log.w(TAG, "classify track=${job.trackId} produced no predictions")
                    }
                    processed += 1
                }
                publishOverlay(SystemClock.elapsedRealtime())
            } catch (t: Throwable) {
                failed = true
                Log.e(TAG, "classification pass failed", t)
                // A whole pass failing (e.g. the classifier threw) must not strand every queued
                // track's `enqueued` flag: release them all so the next accepted frame can
                // re-schedule work instead of silently skipping the birds for the rest of the scan.
                for (job in queue) {
                    memory[job.trackId]?.let { it.enqueued = false }
                    runCatching { job.crop.recycle() }
                }
                queue.clear()
                publishOverlay(SystemClock.elapsedRealtime())
            } finally {
                draining.set(false)
                // Retrying straight after a failure would throw again and spin; the next accepted
                // frame restarts the pass instead.
                if (!failed && queue.isNotEmpty()) drainQueue()
            }
        }
    }

    /** Highest priority first: brand-new birds, then grown ones, then merely stale ones. */
    private fun pollBest(): ClassifyJob? {
        val snapshot = queue.toList()
        if (snapshot.isEmpty()) return null
        var best: ClassifyJob? = null
        for (job in snapshot) {
            if (best == null || job.priority < best.priority) best = job
        }
        return if (best != null && queue.remove(best)) best else null
    }

    private fun publishOverlay(nowMs: Long) {
        val tracks = tracker.currentSnapshot()
        val items = ArrayList<OverlayItem>(tracks.size)
        for (track in tracks) {
            val label = if (classifier == null) {
                TrackLabel(LabelKind.UNKNOWN)
            } else {
                stabilizer.snapshot(track.id, nowMs)
            }
            val species = label.classIndex?.let { dictionary?.speciesAt(it) }
            val area = BitmapOps.areaFraction(track.box)
            items += OverlayItem(
                trackId = track.id,
                box = RectF(track.box),
                label = label,
                species = species,
                tooSmall = area < MIN_TOO_SMALL_AREA && label.kind != LabelKind.CONFIRMED,
            )
        }
        if (firstStableNameMs == 0L && firstResultMs != 0L) {
            for (item in items) {
                if (item.label.kind == LabelKind.CONFIRMED) {
                    firstStableNameMs = nowMs - firstResultMs
                    break
                }
            }
        }
        _overlay.value = items
    }

    private fun updateStats(nowMs: Long) {
        synchronized(statsLock) {
            val window = nowMs - statsWindowStart
            if (window < STATS_WINDOW_MS) return
            // Each counter is reset as it is read so the window rates are independent of one another
            // and of how many threads happened to call in.
            val received = framesReceived.getAndSet(0)
            val detected = framesDetected.getAndSet(0)
            val classified = classificationsDone.getAndSet(0)
            val dropped = droppedFrames.get()
            val previewFps = received * 1000f / window
            val detectionFps = detected * 1000f / window
            val classificationFps = classified * 1000f / window
            _stats.value = PipelineStats(
                previewFps = previewFps,
                detectionFps = detectionFps,
                classificationFps = classificationFps,
                detectionLatencyMs = detectionLatency,
                classificationLatencyMs = classificationLatency,
                droppedFrames = dropped,
                trackedBirds = tracker.currentSnapshot().size,
                firstStableNameMs = firstStableNameMs,
            )

            // A rolling line so the live loop can be judged from a log instead of by eye. The three
            // rates are independent: "boxes update slowly" can be the throttle, a slow detector, or a
            // slow classifier — and those need different fixes. Sampled every few seconds rather than
            // every window to stay readable.
            if (nowMs - lastStatsLogMs >= STATS_LOG_INTERVAL_MS) {
                lastStatsLogMs = nowMs
                Log.i(
                    TAG,
                    "live previewFps=%.1f detectFps=%.1f classifyFps=%.1f detectMs=%.0f " +
                        "classifyMs=%.0f dropped=%d birds=%d firstStable=%dms".format(
                            previewFps,
                            detectionFps,
                            classificationFps,
                            detectionLatency,
                            classificationLatency,
                            dropped,
                            tracker.currentSnapshot().size,
                            firstStableNameMs,
                        ),
                )
            }
            statsWindowStart = nowMs
        }
    }

    /**
     * Single-shot identification for a still photo. Deliberately does not reuse the preview's
     * labels: the shutter fires after the frame the user saw, so a moving bird would be annotated
     * in the wrong place.
     *
     * Runs on the capture thread while the preview classifier may be mid-inference; the mutual
     * exclusion lives in [SpeciesClassifier.classify].
     */
    fun identifyStill(frame: Bitmap, box: RectF): TrackLabel {
        val classifier = classifier ?: return TrackLabel(LabelKind.UNKNOWN)
        val crop = BitmapOps.crop(frame, box, dictionary?.cropPaddingRatio ?: DEFAULT_CROP_PADDING)
            ?: return TrackLabel(LabelKind.UNKNOWN)
        val predictions = classifier.classifyAveraged(crop)
        crop.recycle()
        val label = evaluateStill(predictions)
        // The saved photo is the artefact a user judges the app by, and "it said 鸟类" is equally
        // consistent with "no candidate" and "a candidate just under the bar". Logging the ranked
        // candidates next to the verdict is what makes those two separable from a field report.
        Log.i(
            TAG,
            "still box=${box.toShortString()} verdict=$label candidates=" +
                predictions.take(3).joinToString { "${it.classIndex}:${"%.3f".format(it.score)}" },
        )
        return label
    }

    /**
     * Still photos get one shot, so the vote window cannot apply. A single confident prediction
     * below [displayThreshold] is reported as unknown rather than forced into a species.
     */
    private fun evaluateStill(predictions: List<Prediction>): TrackLabel {
        val top = predictions.firstOrNull() ?: return TrackLabel(LabelKind.UNKNOWN)
        if (top.classIndex == (dictionary?.backgroundClassIndex ?: -1)) {
            return TrackLabel(LabelKind.UNKNOWN, score = top.score)
        }
        val second = predictions.getOrNull(1)?.score ?: 0f
        val margin = top.score - second
        return if (top.score >= stillThreshold && margin >= STILL_MARGIN) {
            TrackLabel(LabelKind.CONFIRMED, top.classIndex, top.score)
        } else {
            TrackLabel(LabelKind.UNKNOWN, score = top.score)
        }
    }

    /** Mirrors the display threshold so the saved photo's labels match what the preview showed. */
    @Volatile
    var stillThreshold: Float = AppSettings.DEFAULT_CONFIDENCE_THRESHOLD

    fun onSettingsChanged(confidenceThreshold: Float) {
        stabilizer.displayThreshold = confidenceThreshold
        stillThreshold = confidenceThreshold
    }

    fun reset() {
        tracker.reset()
        stabilizer.reset()
        memory.clear()
        queue.forEach { it.crop.recycle() }
        queue.clear()
        _overlay.value = emptyList()
        // A new scan restarts the clock on the per-session metrics: without this, the "first stable
        // name" latency and the dropped-frame total would be inherited from the previous scan.
        framesReceived.set(0)
        framesDetected.set(0)
        classificationsDone.set(0)
        droppedFrames.set(0)
        firstResultCaptured.set(false)
        firstResultMs = 0L
        firstStableNameMs = 0L
        _stats.value = PipelineStats()
    }

    override fun close() {
        queue.forEach { runCatching { it.crop.recycle() } }
        queue.clear()
        classifyExecutor.shutdownNow()
        runCatching { classifier?.close() }
        runCatching { detector?.close() }
    }

    companion object {
        private const val TAG = "BirdPipeline"
        private const val DEFAULT_CROP_PADDING = 0.15f

        /** Below this share of the frame there are too few pixels for a species judgement. */
        private const val MIN_CLASSIFY_AREA = 0.0025f

        /** Below this the overlay suggests moving closer. */
        private const val MIN_TOO_SMALL_AREA = 0.012f

        /** A bird that grew by this factor is worth re-running the classifier on. */
        private const val GROWTH_TRIGGER = 1.35f

        private const val RECLASSIFY_INTERVAL_MS = 1500L
        private const val MAX_CLASSIFICATIONS_PER_FRAME = 2
        private const val MAX_DRAIN_PER_CYCLE = 3
        private const val STATS_WINDOW_MS = 1000L
        private const val STATS_LOG_INTERVAL_MS = 5_000L
        private const val STILL_MARGIN = 0.15f
    }
}
