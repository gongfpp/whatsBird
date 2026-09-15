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

import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.whatsbird.classify.Prediction
import com.whatsbird.detect.RawDetection
import com.whatsbird.label.LabelStabilizer
import com.whatsbird.track.BirdTracker
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Pins down the crash that only showed up once a bird was on screen.
 *
 * The tracker and the stabiliser are written from MediaPipe's result thread and read from the
 * classification thread. Both hold plain collections, and the live app only exercised that overlap
 * when there was actually a bird to classify — which is why the failure looked like "point it at a
 * bird and it closes, point it at an empty garden and it is fine".
 *
 * The call pattern is driven from separate threads for a short burst. Neither class promises any
 * ordering, only that it will not corrupt itself, so the assertion is simply that nothing throws.
 */
@RunWith(AndroidJUnit4::class)
class ConcurrencyStressTest {

    private val burstMs = 2_000L

    /** Runs every worker until the first failure or the burst expires; returns what went wrong. */
    private fun hammer(workers: List<() -> Unit>): List<Throwable> {
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val stop = AtomicBoolean(false)
        val start = CountDownLatch(1)
        val threads = workers.map { body ->
            Thread {
                start.await()
                while (!stop.get()) {
                    try {
                        body()
                    } catch (t: Throwable) {
                        failures.add(t)
                        stop.set(true)
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }
        }
        start.countDown()
        Thread.sleep(burstMs)
        stop.set(true)
        threads.forEach { it.join(2_000) }
        return failures.toList()
    }

    private fun detections(offset: Float) = listOf(
        RawDetection(RectF(offset, 0.20f, offset + 0.20f, 0.60f), 0.90f),
        RawDetection(RectF(offset + 0.30f, 0.10f, offset + 0.50f, 0.40f), 0.80f),
    )

    /**
     * The regression this file exists for: `PointF`'s copy constructor is API 35, so on the Android
     * 10 target the tracker threw `NoSuchMethodError` as soon as it had a track to serialise — which
     * is only ever true once a bird has been detected. Asserting the snapshot is non-empty is what
     * makes that reproducible without pointing the camera at anything.
     */
    @Test
    fun snapshotBuildsTracksOnThisDevice() {
        val tracker = BirdTracker()
        val tracks = tracker.update(detections(0.10f))
        val snapshot = tracker.currentSnapshot()

        assertTrue("two detections should produce two live tracks", tracks.size == 2)
        assertTrue("the snapshot must be rebuildable", snapshot.size == 2)
        assertTrue(
            "find() must locate a track it just reported",
            tracker.find(snapshot.first().id) != null,
        )
    }

    @Test
    fun trackerToleratesConcurrentUpdateAndReads() {
        val tracker = BirdTracker()
        val clock = AtomicLong()

        val failures = hammer(
            listOf(
                { tracker.update(detections(0.10f)) },
                { tracker.update(detections(0.45f)) },
                { tracker.currentSnapshot() },
                { tracker.find(1) },
                { tracker.reset() },
            ),
        )

        assertTrue("concurrent tracker access threw: $failures", failures.isEmpty())
    }

    @Test
    fun stabilizerToleratesConcurrentObserveAndReads() {
        val stabilizer = LabelStabilizer(backgroundClassIndex = 0)
        val predictions = listOf(Prediction(3, 0.70f), Prediction(4, 0.20f))
        val clock = AtomicLong()

        val failures = hammer(
            listOf(
                { stabilizer.observe(1, predictions, clock.incrementAndGet()) },
                { stabilizer.observe(2, predictions, clock.incrementAndGet()) },
                { stabilizer.snapshot(1, clock.get()) },
                { stabilizer.snapshot(2, clock.get()) },
                { stabilizer.forget(1) },
            ),
        )

        assertTrue("concurrent stabiliser access threw: $failures", failures.isEmpty())
    }
}
