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

import android.graphics.PointF
import android.graphics.RectF
import com.whatsbird.detect.RawDetection

/** A bird the app is currently following. Boxes are normalised to the upright analysis frame. */
data class Track(
    val id: Int,
    val box: RectF,
    val velocity: PointF,
    val hits: Int,
    val missedFrames: Int,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
) {
    val isConfirmedByDetector: Boolean get() = missedFrames == 0
}

/**
 * Greedy IoU tracker with constant-velocity prediction.
 *
 * Deliberately not a learned tracker: the plan's requirement is only that a label stays attached to
 * the same bird between detections and that a lost bird's label is dropped, and it explicitly does
 * not promise re-identification through long mutual occlusion.
 *
 * Threading: [update] runs on MediaPipe's own result thread, while [currentSnapshot] and [find] are
 * called from the classification thread and from the UI. All four entry points are therefore
 * serialised on this instance. [states] is a plain [ArrayList]; a structural change made while the
 * other thread iterates it throws [ConcurrentModificationException], and because the throw lands
 * inside a third-party callback or an executor task nothing catches it — the process dies.
 */
class BirdTracker(
    private val iouThreshold: Float = 0.25f,
    private val maxMissedFrames: Int = 10,
    private val maxTracks: Int = 8,
    private val velocitySmoothing: Float = 0.5f,
) {

    private class State(
        val id: Int,
        var box: RectF,
        var velocity: PointF,
        var hits: Int,
        var missed: Int,
        val firstSeenMs: Long,
        var lastSeenMs: Long,
    )

    private val states = ArrayList<State>(maxTracks)
    private var nextId = 1

    @Synchronized
    fun update(detections: List<RawDetection>, timestampMs: Long): List<Track> {
        // Work off stable State references, not list indices: spawning a track can evict another
        // one, which would silently re-point any index-based bookkeeping at the wrong bird.
        val candidates = states.map { Candidate(it, predict(it)) }
        val matchedDetection = BooleanArray(detections.size)
        val matchedCandidate = BooleanArray(candidates.size)

        // Greedy: repeatedly take the best remaining pair above threshold.
        while (true) {
            var bestIou = iouThreshold
            var bestCandidate = -1
            var bestDetection = -1
            for (c in candidates.indices) {
                if (matchedCandidate[c]) continue
                for (d in detections.indices) {
                    if (matchedDetection[d]) continue
                    val iou = iou(candidates[c].predicted, detections[d].box)
                    if (iou > bestIou) {
                        bestIou = iou
                        bestCandidate = c
                        bestDetection = d
                    }
                }
            }
            if (bestCandidate < 0) break
            matchedCandidate[bestCandidate] = true
            matchedDetection[bestDetection] = true

            val state = candidates[bestCandidate].state
            val box = detections[bestDetection].box
            val dx = box.centerX() - state.box.centerX()
            val dy = box.centerY() - state.box.centerY()
            state.velocity = PointF(
                state.velocity.x * (1 - velocitySmoothing) + dx * velocitySmoothing,
                state.velocity.y * (1 - velocitySmoothing) + dy * velocitySmoothing,
            )
            state.box = RectF(box)
            state.hits += 1
            state.missed = 0
            state.lastSeenMs = timestampMs
        }

        // Unmatched detections become new tracks.
        for (d in detections.indices) {
            if (matchedDetection[d]) continue
            spawn(detections[d].box, timestampMs)
        }

        // Unmatched tracks coast on their predicted box for a bounded number of frames.
        for (c in candidates.indices) {
            if (matchedCandidate[c]) continue
            val state = candidates[c].state
            if (state !in states) continue
            state.missed += 1
            state.box = candidates[c].predicted
            state.velocity = PointF(state.velocity.x * 0.8f, state.velocity.y * 0.8f)
        }
        states.removeAll { it.missed > maxMissedFrames }

        return currentSnapshot()
    }

    private class Candidate(val state: State, val predicted: RectF)

    /** Live tracks without advancing the tracker — for overlay repaints between frames. */
    @Synchronized
    fun currentSnapshot(): List<Track> = states.map {
        Track(
            id = it.id,
            box = RectF(it.box),
            // PointF's copy constructor is API 35. On the Android 10 target it does not exist and
            // the call raises NoSuchMethodError, which nothing catches — that is why the app only
            // ever died once a bird was actually on screen: with an empty [states] this lambda
            // never runs and no Track is ever built.
            velocity = PointF(it.velocity.x, it.velocity.y),
            hits = it.hits,
            missedFrames = it.missed,
            firstSeenMs = it.firstSeenMs,
            lastSeenMs = it.lastSeenMs,
        )
    }

    @Synchronized
    fun find(id: Int): Track? = states.firstOrNull { it.id == id }?.let {
        Track(
            it.id,
            RectF(it.box),
            PointF(it.velocity.x, it.velocity.y),
            it.hits,
            it.missed,
            it.firstSeenMs,
            it.lastSeenMs,
        )
    }

    @Synchronized
    fun reset() {
        states.clear()
        nextId = 1
    }

    private fun spawn(box: RectF, timestampMs: Long) {
        if (states.size >= maxTracks) {
            // Only a bird that already missed a frame can be evicted. If every track is currently
            // visible, the new detection is dropped rather than displacing a bird we can still see.
            val victim = states.filter { it.missed > 0 }.maxByOrNull { it.missed } ?: return
            states.remove(victim)
        }
        states += State(
            id = nextId++,
            box = RectF(box),
            velocity = PointF(0f, 0f),
            hits = 1,
            missed = 0,
            firstSeenMs = timestampMs,
            lastSeenMs = timestampMs,
        )
    }

    private fun predict(state: State): RectF {
        val box = RectF(state.box)
        box.offset(state.velocity.x, state.velocity.y)
        return box
    }

    private fun iou(a: RectF, b: RectF): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        if (right <= left || bottom <= top) return 0f
        val intersection = (right - left) * (bottom - top)
        val union = a.width() * a.height() + b.width() * b.height() - intersection
        return if (union <= 0f) 0f else intersection / union
    }
}
