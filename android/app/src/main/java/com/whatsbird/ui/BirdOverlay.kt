package com.whatsbird.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whatsbird.label.LabelKind
import com.whatsbird.overlay.OverlayTransform
import com.whatsbird.pipeline.OverlayItem
import com.whatsbird.ui.theme.BirdColors
import android.graphics.RectF

private data class ResolvedLabel(val text: String, val kind: LabelKind)

/**
 * Draws detection boxes and species chips over the live preview.
 *
 * Chip placement is resolved here rather than in the pipeline because it needs the view's pixel
 * size. Labels stack upward on collision, fall below the box when there is no room above, and
 * draw a short leader line when they had to move away from their box.
 */
@Composable
fun BirdOverlay(
    items: List<OverlayItem>,
    analysisAspect: Float,
    showBoxes: Boolean,
    identifyingText: String,
    unknownText: String,
    tooSmallText: String,
    chineseNames: Boolean,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val labels = remember(items, identifyingText, unknownText, tooSmallText, chineseNames) {
        items.associate { item ->
            val species = item.species
            val text = when {
                item.tooSmall -> tooSmallText
                item.label.kind == LabelKind.CONFIRMED && species != null ->
                    if (chineseNames) species.chineseName else species.englishName
                item.label.kind == LabelKind.IDENTIFYING -> identifyingText
                else -> unknownText
            }
            item.trackId to ResolvedLabel(
                text = text,
                kind = if (item.tooSmall) LabelKind.IDENTIFYING else item.label.kind,
            )
        }
    }

    val labelStyle = remember {
        TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = BirdColors.OnBackground)
    }
    val gap = with(androidx.compose.ui.platform.LocalDensity.current) { 6.dp.toPx() }
    val chipPaddingH = with(androidx.compose.ui.platform.LocalDensity.current) { 8.dp.toPx() }
    val chipPaddingV = with(androidx.compose.ui.platform.LocalDensity.current) { 4.dp.toPx() }
    val boxStroke = with(androidx.compose.ui.platform.LocalDensity.current) { 2.dp.toPx() }

    Canvas(modifier = modifier) {
        if (items.isEmpty()) return@Canvas
        val transform = OverlayTransform(analysisAspect, size.width, size.height)
        val placed = ArrayList<Rect>(items.size)
        val scratch = RectF()

        for (item in items.sortedBy { it.box.top }) {
            transform.mapRect(item.box, scratch)
            val box = Rect(scratch.left, scratch.top, scratch.right, scratch.bottom)
            val resolved = labels[item.trackId] ?: continue

            val chipColor = when (resolved.kind) {
                LabelKind.CONFIRMED -> BirdColors.ChipConfirmed
                LabelKind.UNKNOWN -> BirdColors.ChipUnknown
                LabelKind.IDENTIFYING -> BirdColors.ChipIdentifying
            }
            val borderColor = when (resolved.kind) {
                LabelKind.CONFIRMED -> BirdColors.Accent
                LabelKind.UNKNOWN -> Color(0xFFBFD8C9)
                LabelKind.IDENTIFYING -> BirdColors.OnSurfaceMuted
            }

            if (showBoxes) {
                // Shadow stroke first so the box stays visible against bright plumage.
                drawRect(
                    color = Color(0x66000000),
                    topLeft = Offset(box.left - boxStroke, box.top - boxStroke),
                    size = Size(box.width + boxStroke * 2, box.height + boxStroke * 2),
                    style = Stroke(width = boxStroke * 1.8f),
                )
                drawRect(
                    color = borderColor,
                    topLeft = box.topLeft,
                    size = box.size,
                    style = Stroke(width = boxStroke),
                )
            }

            val layout = measurer.measure(resolved.text, labelStyle)
            val chipWidth = layout.size.width + chipPaddingH * 2
            val chipHeight = layout.size.height + chipPaddingV * 2
            val chipLeft = box.left.coerceIn(0f, (size.width - chipWidth).coerceAtLeast(0f))

            val placement = placeChip(
                box = box,
                placed = placed,
                chipWidth = chipWidth,
                chipHeight = chipHeight,
                chipLeft = chipLeft,
                gap = gap,
                bounds = Rect(Offset.Zero, size),
            )
            val chip = Rect(
                chipLeft,
                placement,
                chipLeft + chipWidth,
                placement + chipHeight,
            )
            placed += chip

            if (showBoxes && placement > box.bottom) {
                drawLine(
                    color = borderColor,
                    start = Offset(chip.left + chipWidth / 2f, chip.top),
                    end = Offset(box.left + box.width / 2f, box.top),
                    strokeWidth = boxStroke * 0.6f,
                )
            }

            drawRoundRect(
                color = chipColor,
                topLeft = chip.topLeft,
                size = chip.size,
                cornerRadius = CornerRadius(chipHeight / 2f, chipHeight / 2f),
            )
            drawRoundRect(
                color = borderColor.copy(alpha = 0.7f),
                topLeft = chip.topLeft,
                size = chip.size,
                cornerRadius = CornerRadius(chipHeight / 2f, chipHeight / 2f),
                style = Stroke(width = 1.5f),
            )
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(chip.left + chipPaddingH, chip.top + chipPaddingV),
            )
        }
    }
}

/**
 * Picks a vertical position for a chip: above the box by default, pushed up past occupied space,
 * and dropped below the box only when there is genuinely no room at the top of the frame.
 */
private fun DrawScope.placeChip(
    box: Rect,
    placed: List<Rect>,
    chipWidth: Float,
    chipHeight: Float,
    chipLeft: Float,
    gap: Float,
    bounds: Rect,
): Float {
    fun overlaps(top: Float): Boolean {
        val candidate = Rect(chipLeft, top, chipLeft + chipWidth, top + chipHeight)
        return placed.any { it.overlaps(candidate) }
    }

    var top = box.top - gap - chipHeight
    var attempts = 0
    while (overlaps(top) && attempts < 8) {
        top -= chipHeight + 2f
        attempts += 1
    }
    if (top < bounds.top) {
        top = box.bottom + gap
        attempts = 0
        while (overlaps(top) && attempts < 8) {
            top += chipHeight + 2f
            attempts += 1
        }
    }
    return top.coerceIn(bounds.top, (bounds.bottom - chipHeight).coerceAtLeast(bounds.top))
}
