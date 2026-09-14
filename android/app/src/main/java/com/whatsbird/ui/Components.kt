package com.whatsbird.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.whatsbird.R
import com.whatsbird.ui.theme.BirdColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/** Bridges a LiveData (CameraX's zoom/tap-to-focus state) without pulling in the extra artifact. */
@Composable
fun <T> LiveData<T>.observeState(): State<T?> {
    val state = remember(this) { mutableStateOf(value) }
    DisposableEffect(this) {
        val observer = Observer<T> { state.value = it }
        observeForever(observer)
        onDispose { removeObserver(observer) }
    }
    return state
}

@Composable
fun ShutterButton(
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ring = if (enabled) BirdColors.Accent else BirdColors.OnSurfaceMuted
    val shutterLabel = stringResource(R.string.shutter)
    Box(
        modifier = modifier
            .size(72.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled && !busy, onClick = onClick)
            .semantics { contentDescription = shutterLabel },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(72.dp)) {
            drawCircle(color = Color(0x66000000), radius = size.minDimension / 2f)
            drawCircle(
                color = ring,
                radius = size.minDimension / 2f - 3.dp.toPx(),
                style = Stroke(width = 3.dp.toPx()),
            )
            drawCircle(
                color = if (enabled) Color.White else BirdColors.OnSurfaceMuted,
                radius = size.minDimension / 2f - 11.dp.toPx(),
            )
        }
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(72.dp),
                color = BirdColors.Accent,
                strokeWidth = 3.dp,
            )
        }
    }
}

/**
 * Shows the most recent saved photo. Decoding is downsampled and off the main thread because this
 * runs while the analysis pipeline is already using the CPU.
 */
@Composable
fun ThumbnailButton(
    uri: android.net.Uri?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            uri?.let {
                runCatching {
                    context.contentResolver.openInputStream(it).use { stream: InputStream? ->
                        stream?.let { decodeDownsampled(it, 192) }
                    }
                }.getOrNull()
            }
        }
    }

    Box(
        modifier = modifier
            .size(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(BirdColors.SurfaceHigh)
            .border(1.dp, BirdColors.AccentDim, RoundedCornerShape(12.dp))
            .clickable(enabled = uri != null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val current = bitmap
        if (current != null) {
            Image(
                bitmap = current.asImageBitmap(),
                contentDescription = stringResource(R.string.gallery),
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(48.dp),
            )
        } else {
            Text(
                text = stringResource(R.string.gallery),
                style = MaterialTheme.typography.bodySmall,
                color = BirdColors.OnSurfaceMuted,
            )
        }
    }
}

private fun decodeDownsampled(stream: InputStream, target: Int): Bitmap? {
    val bytes = stream.readBytes()
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > target || bounds.outHeight / sample > target) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

@Composable
fun StatusChip(
    text: String,
    tone: ChipTone,
    modifier: Modifier = Modifier,
) {
    val dot = when (tone) {
        ChipTone.Good -> BirdColors.Accent
        ChipTone.Warn -> BirdColors.Warning
        ChipTone.Idle -> BirdColors.OnSurfaceMuted
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(0x99000000.toInt().let { Color(it) })
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Canvas(Modifier.size(8.dp)) { drawCircle(color = dot, radius = size.minDimension / 2f) }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = BirdColors.OnBackground,
        )
    }
}

enum class ChipTone { Good, Warn, Idle }

@Composable
fun ZoomPill(
    zoomRatio: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(0x99000000.toInt().let { Color(it) })
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (zoomRatio <= 1.02f) "1x" else String.format("%.1fx", zoomRatio),
            style = MaterialTheme.typography.bodySmall,
            color = BirdColors.OnBackground,
        )
    }
}

/** Draws a soft focus ring where the user tapped to focus. */
@Composable
fun FocusRing(center: Offset?, modifier: Modifier = Modifier) {
    if (center == null) return
    Canvas(modifier = modifier) {
        drawCircle(
            color = Color(0xCCFFFFFF),
            radius = 28.dp.toPx(),
            center = center,
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}
