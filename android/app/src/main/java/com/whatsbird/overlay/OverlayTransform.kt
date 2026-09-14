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

/**
 * Maps boxes normalised to the upright analysis frame onto pixels in the preview view.
 *
 * The preview is displayed with `FILL_CENTER`, so the frame is centre-cropped to cover the view.
 * Analysis and preview are configured with the same aspect-ratio strategy, which is what makes this
 * simple two-term mapping exact rather than approximate.
 */
class OverlayTransform(
    analysisAspect: Float,
    private val viewWidth: Float,
    private val viewHeight: Float,
) {
    private val contentWidth: Float
    private val contentHeight: Float
    private val offsetX: Float
    private val offsetY: Float

    init {
        val safeAspect = if (analysisAspect.isFinite() && analysisAspect > 0.01f) analysisAspect else 4f / 3f
        val scale = maxOf(viewWidth / safeAspect, viewHeight)
        contentWidth = safeAspect * scale
        contentHeight = scale
        offsetX = (viewWidth - contentWidth) / 2f
        offsetY = (viewHeight - contentHeight) / 2f
    }

    fun mapX(normalised: Float): Float = offsetX + normalised * contentWidth

    fun mapY(normalised: Float): Float = offsetY + normalised * contentHeight

    fun mapRect(norm: RectF, out: RectF) {
        out.set(
            mapX(norm.left),
            mapY(norm.top),
            mapX(norm.right),
            mapY(norm.bottom),
        )
    }
}
