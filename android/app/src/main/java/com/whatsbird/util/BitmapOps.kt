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

package com.whatsbird.util

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF

object BitmapOps {

    /** Rotates in 90° steps. Returns the source unchanged when [degrees] is 0. */
    fun rotate(source: Bitmap, degrees: Int): Bitmap {
        val normalised = ((degrees % 360) + 360) % 360
        if (normalised == 0) return source
        val matrix = Matrix().apply { postRotate(normalised.toFloat()) }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (rotated !== source) source.recycle()
        return rotated
    }

    /**
     * Crops [norm] (normalised to [source]) with a proportional margin, clamped to the bitmap.
     * Returns null when the result would be degenerate.
     */
    fun crop(source: Bitmap, norm: RectF, paddingRatio: Float, minPixels: Int = 24): Bitmap? {
        val padX = norm.width() * paddingRatio
        val padY = norm.height() * paddingRatio
        val left = ((norm.left - padX).coerceIn(0f, 1f) * source.width).toInt()
        val top = ((norm.top - padY).coerceIn(0f, 1f) * source.height).toInt()
        val right = ((norm.right + padX).coerceIn(0f, 1f) * source.width).toInt()
        val bottom = ((norm.bottom + padY).coerceIn(0f, 1f) * source.height).toInt()

        val width = right - left
        val height = bottom - top
        if (width < minPixels || height < minPixels) return null
        return runCatching { Bitmap.createBitmap(source, left, top, width, height) }.getOrNull()
    }

    /** Converts a normalised rect into pixel bounds for the given bitmap size. */
    fun toPixelRect(norm: RectF, width: Int, height: Int): Rect = Rect(
        (norm.left * width).toInt(),
        (norm.top * height).toInt(),
        (norm.right * width).toInt(),
        (norm.bottom * height).toInt(),
    )

    /** Fraction of the frame the box covers — used to explain "move closer" hints. */
    fun areaFraction(norm: RectF): Float = (norm.width() * norm.height()).coerceIn(0f, 1f)

    /**
     * Horizontal mirror, for flip-augmented inference.
     *
     * A classifier is not mirror-invariant, so running a photo twice — once as shot, once flipped —
     * and averaging the score vectors is the cheapest test-time augmentation there is. Returns null
     * rather than throwing so a caller on the capture path degrades to a single pass.
     */
    fun mirror(source: Bitmap): Bitmap? = runCatching {
        val matrix = Matrix().apply { postScale(-1f, 1f, source.width / 2f, source.height / 2f) }
        Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }.getOrNull()
}
