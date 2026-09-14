package com.whatsbird.pipeline

import android.graphics.Bitmap
import android.graphics.RectF
import com.whatsbird.detect.RawDetection
import com.whatsbird.label.LabelStabilizer
import com.whatsbird.track.BirdTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Field
import java.util.Deque
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * Regression coverage for the two defects the acceptance review reproduced on-device:
 *
 *  - a briefly-missed bird must stay eligible for classification (the in-flight `enqueued` hold has
 *    to be released on every path that discards a job, not only on success);
 *  - the detection frame-rate must count completed detections, not every frame the camera handed in
 *    (including the ones the throttle or the detector rejected).
 *
 * The pipeline is driven through its private queue/memory and `drainQueue` directly because the
 * detector is a native MediaPipe object that cannot load under Robolectric. The scenario is the same
 * one the on-device probe built: enqueue a job, let the track briefly miss, run a drain pass, then
 * re-detect and assert the hold was released.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BirdPipelineRecoveryTest {

    private fun newPipeline(tracker: BirdTracker): BirdPipeline =
        BirdPipeline(
            detector = null,
            classifier = null,
            dictionary = null,
            tracker = tracker,
            stabilizer = LabelStabilizer(-1, 6, 2, 0.5f, 0.72f, 2200L, 3500L),
        )

    private fun field(obj: Any, name: String): Field =
        obj.javaClass.getDeclaredField(name).also { it.isAccessible = true }

    private fun call(instance: Any, name: String) {
        instance.javaClass.getDeclaredMethod(name).also { it.isAccessible = true }.invoke(instance)
    }

    @Test
    fun `brief miss releases the enqueue hold so the bird can be classified again`() {
        val tracker = BirdTracker()
        val pipeline = newPipeline(tracker)
        try {
            val birds = listOf(RawDetection(RectF(0.2f, 0.2f, 0.5f, 0.5f), 0.9f))
            val id = tracker.update(birds, 1000L).single().id

            // Seed the pipeline's internal per-track memory with an outstanding `enqueued` hold and a
            // queued job, exactly as handleDetections would have done the frame before the miss.
            val memClass = Class.forName("com.whatsbird.pipeline.BirdPipeline\$TrackMemory")
            val mem = memClass.getDeclaredConstructor().newInstance()
            memClass.getDeclaredField("enqueued").also { it.isAccessible = true }.setBoolean(mem, true)
            @Suppress("UNCHECKED_CAST")
            (field(pipeline, "memory").get(pipeline) as MutableMap<Int, Any>)[id] = mem

            val jobClass = Class.forName("com.whatsbird.pipeline.BirdPipeline\$ClassifyJob")
            val jobCtor = jobClass.getDeclaredConstructor(
                Int::class.java, Long::class.java, Bitmap::class.java, Int::class.java, Float::class.java,
            )
            val crop = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            val job = jobCtor.newInstance(id, 1000L, crop, 0, 0.09f)
            @Suppress("UNCHECKED_CAST")
            (field(pipeline, "queue").get(pipeline) as Deque<Any>).addLast(job)

            // The bird is briefly missed but still alive in the tracker.
            tracker.update(emptyList(), 1100L)
            call(pipeline, "drainQueue")
            // Wait for the single-thread classify executor to finish the pass.
            (field(pipeline, "classifyExecutor").get(pipeline) as ExecutorService)
                .submit { }.get(3, TimeUnit.SECONDS)

            val reacquired = tracker.update(birds, 1200L).single().id
            assertEquals("the same track must return after a brief miss", id, reacquired)
            assertFalse(
                "discarded crop must release `enqueued`; otherwise handleDetections skips this bird forever",
                memClass.getDeclaredField("enqueued").also { it.isAccessible = true }.getBoolean(mem),
            )
        } finally {
            pipeline.close()
        }
    }

    @Test
    fun `detectionFps is zero when no detection completed`() {
        val pipeline = newPipeline(BirdTracker())
        try {
            field(pipeline, "statsWindowStart").also { it.isAccessible = true }.setLong(pipeline, 1000L)
            val updateStats = pipeline.javaClass.getDeclaredMethod("updateStats", Long::class.java)
                .also { it.isAccessible = true }
            // updateStats is called by both rejection branches and successful completions. No detector
            // has completed any frame in this fixture.
            for (i in 1..20) updateStats.invoke(pipeline, 1000L + i * 50L)

            val stats = pipeline.stats.value
            assertEquals("zero completed detections should report zero detection FPS", 0f, stats.detectionFps, 0.001f)
            assertEquals("zero classifications should report zero classification FPS", 0f, stats.classificationFps, 0.001f)
        } finally {
            pipeline.close()
        }
    }

    /**
     * The rolling stats log line is built with `String.format` on a concatenated literal. Kotlin's
     * `.` binds tighter than `+`, so an unparenthesised concatenation formats only the second half and
     * shifts every argument — a Float reaching `%d` throws IllegalFormatConversionException on the
     * frame thread and kills the process. This test crosses both the 1 s stats window and the 5 s
     * log-sampling interval so that path is actually executed.
     */
    @Test
    fun `the rolling stats log line formats cleanly after the 5s sampling interval`() {
        val pipeline = newPipeline(BirdTracker())
        try {
            field(pipeline, "statsWindowStart").also { it.isAccessible = true }.setLong(pipeline, 1000L)
            field(pipeline, "lastStatsLogMs").also { it.isAccessible = true }.setLong(pipeline, 0L)
            val updateStats = pipeline.javaClass.getDeclaredMethod("updateStats", Long::class.java)
                .also { it.isAccessible = true }

            val thrown = runCatching { updateStats.invoke(pipeline, 6000L) }.exceptionOrNull()
            assertNull(
                "the stats log line must format cleanly; a mis-format here kills the frame thread",
                thrown?.cause ?: thrown,
            )
        } finally {
            pipeline.close()
        }
    }

    @Test
    fun `previewFps counts received frames even without a detector while detectionFps stays zero`() {
        val pipeline = newPipeline(BirdTracker())
        try {
            field(pipeline, "statsWindowStart").also { it.isAccessible = true }.setLong(pipeline, 1000L)
            // detector is null, so submitFrame only records the received frame then recycles it.
            for (i in 1..20) {
                pipeline.submitFrame(Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888), 1000L + i, 0L)
            }
            val updateStats = pipeline.javaClass.getDeclaredMethod("updateStats", Long::class.java)
                .also { it.isAccessible = true }
            updateStats.invoke(pipeline, 2000L)

            val stats = pipeline.stats.value
            assertTrue("preview FPS must count the frames the camera delivered", stats.previewFps > 0f)
            assertEquals("no detector ran, so detection FPS must be zero", 0f, stats.detectionFps, 0.001f)
            assertEquals("no classifier ran, so classification FPS must be zero", 0f, stats.classificationFps, 0.001f)
        } finally {
            pipeline.close()
        }
    }
}
