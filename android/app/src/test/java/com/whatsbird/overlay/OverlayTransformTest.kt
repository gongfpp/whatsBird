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

package com.whatsbird.overlay

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The overlay's only job is to put a box where the bird actually is. Getting this wrong is
 * invisible on a developer's device with a matching aspect ratio and obvious on a phone with a
 * different one, so the mapping is pinned here rather than eyeballed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OverlayTransformTest {

    private val tolerance = 0.01f

    @Test
    fun `the centre of the frame lands at the centre of the view`() {
        val transform = OverlayTransform(analysisAspect = 4f / 3f, viewWidth = 1080f, viewHeight = 2340f)

        assertEquals(540f, transform.mapX(0.5f), tolerance)
        assertEquals(1170f, transform.mapY(0.5f), tolerance)
    }

    @Test
    fun `a taller view centre crops the frame horizontally`() {
        // 4:3 content in a 9:19.5 view: the frame is scaled to fill height, so width overflows.
        val transform = OverlayTransform(4f / 3f, 1080f, 2340f)

        // The left edge of the frame is off-screen; the right edge is too. That is what FILL_CENTER
        // does, and the formula must reproduce it rather than clamp.
        assertTrue(transform.mapX(0f) < 0f)
        assertTrue(transform.mapX(1f) > 1080f)
        assertEquals(0f, transform.mapY(0f), tolerance)
        assertEquals(2340f, transform.mapY(1f), tolerance)
    }

    @Test
    fun `a wider view centre crops the frame vertically`() {
        val transform = OverlayTransform(analysisAspect = 1f, viewWidth = 2000f, viewHeight = 1000f)

        assertEquals(0f, transform.mapX(0f), tolerance)
        assertEquals(2000f, transform.mapX(1f), tolerance)
        assertTrue(transform.mapY(0f) < 0f)
        assertTrue(transform.mapY(1f) > 1000f)
    }

    @Test
    fun `rect mapping is consistent with point mapping`() {
        val transform = OverlayTransform(4f / 3f, 1080f, 2340f)
        val norm = RectF(0.25f, 0.25f, 0.75f, 0.5f)
        val out = RectF()

        transform.mapRect(norm, out)

        assertEquals(transform.mapX(norm.left), out.left, tolerance)
        assertEquals(transform.mapY(norm.top), out.top, tolerance)
        assertEquals(transform.mapX(norm.right), out.right, tolerance)
        assertEquals(transform.mapY(norm.bottom), out.bottom, tolerance)
    }

    @Test
    fun `a degenerate aspect ratio falls back instead of producing NaN`() {
        val transform = OverlayTransform(analysisAspect = 0f, viewWidth = 1080f, viewHeight = 1920f)

        assertTrue(transform.mapX(0.5f).isFinite())
        assertTrue(transform.mapY(0.5f).isFinite())
    }

    @Test
    fun `boxes are mapped in order so top stays above bottom`() {
        val transform = OverlayTransform(4f / 3f, 1080f, 2340f)
        val out = RectF()

        transform.mapRect(RectF(0.1f, 0.2f, 0.3f, 0.4f), out)

        assertTrue(out.top < out.bottom)
        assertTrue(out.left < out.right)
    }

    @Test
    fun `a landscape frame in a landscape view maps the long edge to the long edge`() {
        // Phone turned sideways: the analysis frame is rotated before it reaches the pipeline, so
        // both the frame and the view are now wider than they are tall. Mixing up the two — keeping
        // the portrait aspect while the view is landscape — is exactly how the boxes end up rotated
        // 90 degrees away from the birds.
        val transform = OverlayTransform(analysisAspect = 4f / 3f, viewWidth = 2340f, viewHeight = 1080f)

        assertEquals(0f, transform.mapX(0f), tolerance)
        assertEquals(2340f, transform.mapX(1f), tolerance)
        // 4:3 content in a 2.17:1 view fills the width and centre crops top and bottom.
        assertTrue(transform.mapY(0f) < 0f)
        assertTrue(transform.mapY(1f) > 1080f)
        assertEquals(540f, transform.mapY(0.5f), tolerance)
        assertEquals(1170f, transform.mapX(0.5f), tolerance)
    }
}
