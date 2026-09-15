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

package com.whatsbird.track

import android.graphics.RectF
import com.whatsbird.detect.RawDetection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The tracker is what keeps a species name glued to the bird it was computed for. These tests pin
 * the behaviours the overlay depends on: identity survives a frame or two of occlusion, but a bird
 * that is genuinely gone does not leave a ghost label behind.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BirdTrackerTest {

    private fun detection(left: Float, top: Float, right: Float, bottom: Float, score: Float = 0.9f) =
        RawDetection(RectF(left, top, right, bottom), score)

    @Test
    fun `a bird seen once becomes a track`() {
        val tracker = BirdTracker()
        val tracks = tracker.update(listOf(detection(0.2f, 0.2f, 0.4f, 0.5f)))

        assertEquals(1, tracks.size)
        assertEquals(0, tracks.first().missedFrames)
    }

    @Test
    fun `overlapping detections keep the same identity`() {
        val tracker = BirdTracker()
        val first = tracker.update(listOf(detection(0.2f, 0.2f, 0.4f, 0.5f))).single().id

        // same bird, drifted slightly between frames
        val second = tracker.update(listOf(detection(0.22f, 0.21f, 0.42f, 0.51f))).single()

        assertEquals(first, second.id)
    }

    @Test
    fun `two disjoint birds get two identities`() {
        val tracker = BirdTracker()
        val tracks = tracker.update(
            listOf(detection(0.05f, 0.1f, 0.2f, 0.3f), detection(0.6f, 0.5f, 0.85f, 0.8f)),
        )

        assertEquals(2, tracks.size)
        assertEquals(2, tracks.map { it.id }.toSet().size)
    }

    @Test
    fun `distinct ids are not swapped when two birds cross match scores`() {
        val tracker = BirdTracker()
        tracker.update(listOf(detection(0.10f, 0.10f, 0.25f, 0.30f), detection(0.60f, 0.60f, 0.75f, 0.80f)))
        val left = tracker.currentSnapshot().first { it.box.left < 0.5f }.id
        val right = tracker.currentSnapshot().first { it.box.left > 0.5f }.id

        // Both move; each stays closest to its own previous position.
        tracker.update(listOf(detection(0.12f, 0.11f, 0.27f, 0.31f), detection(0.58f, 0.59f, 0.73f, 0.79f)))

        assertTrue(left != right)
        assertEquals(left, tracker.currentSnapshot().first { it.box.left < 0.5f }.id)
        assertEquals(right, tracker.currentSnapshot().first { it.box.left > 0.5f }.id)
    }

    @Test
    fun `a brief occlusion coasts the track instead of dropping it`() {
        val tracker = BirdTracker(maxMissedFrames = 4)
        val id = tracker.update(listOf(detection(0.2f, 0.2f, 0.4f, 0.5f))).single().id

        val afterMiss = tracker.update(emptyList())

        assertEquals(1, afterMiss.size)
        assertEquals(id, afterMiss.single().id)
        assertEquals(1, afterMiss.single().missedFrames)
    }

    @Test
    fun `a bird that stays gone loses its track`() {
        val tracker = BirdTracker(maxMissedFrames = 3)
        tracker.update(listOf(detection(0.2f, 0.2f, 0.4f, 0.5f)))

        repeat(3) { tracker.update(emptyList()) }
        val afterExpiry = tracker.update(emptyList())

        assertTrue(afterExpiry.isEmpty())
        assertNull(tracker.find(1))
    }

    @Test
    fun `tracking capacity evicts the stalest bird rather than the new one`() {
        val tracker = BirdTracker(maxTracks = 2)
        tracker.update(listOf(detection(0.0f, 0.0f, 0.1f, 0.1f)))
        tracker.update(listOf(detection(0.0f, 0.0f, 0.1f, 0.1f)))
        // second bird appears while the first is no longer detected
        tracker.update(listOf(detection(0.5f, 0.5f, 0.6f, 0.6f)))
        val tracks = tracker.update(listOf(detection(0.8f, 0.1f, 0.9f, 0.2f)))

        assertEquals(2, tracks.size)
        assertNotNull(tracks.firstOrNull { it.box.left > 0.75f })
    }

    @Test
    fun `reset clears every track and restarts ids`() {
        val tracker = BirdTracker()
        tracker.update(listOf(detection(0.2f, 0.2f, 0.4f, 0.5f)))
        tracker.reset()

        val tracks = tracker.update(listOf(detection(0.2f, 0.2f, 0.4f, 0.5f)))

        assertEquals(1, tracks.single().id)
    }
}
