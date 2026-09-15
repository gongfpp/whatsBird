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

package com.whatsbird.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.whatsbird.R
import com.whatsbird.pipeline.ModelStatus
import com.whatsbird.ui.theme.BirdColors
import kotlinx.coroutines.delay

/**
 * The whole app surface: live preview, label overlay, shutter, and the settings sheet.
 *
 * The preview sits underneath a full-size overlay canvas. Both are the same size, and analysis and
 * preview share an aspect-ratio strategy, which is what makes a normalised box map to the right
 * pixels on every device.
 */
@Composable
fun CameraScreen(viewModel: ScanViewModel = viewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val configuration = LocalConfiguration.current

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val overlay by viewModel.overlay.collectAsStateWithLifecycle()
    val modelStatus by viewModel.modelStatus.collectAsStateWithLifecycle()
    val captureState by viewModel.captureState.collectAsStateWithLifecycle()
    val analysisAspect by viewModel.analysisAspect.collectAsStateWithLifecycle()

    val controller = remember(context) { LifecycleCameraController(context) }
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(controller, lifecycleOwner) {
        viewModel.onControllerAttached(controller, lifecycleOwner)
    }
    DisposableEffect(controller) {
        onDispose { viewModel.onControllerDetached() }
    }

    val zoomState by controller.zoomState.observeState()
    val zoomRatio = zoomState?.zoomRatio ?: 1f

    LaunchedEffect(captureState.message) {
        if (captureState.message != null) {
            delay(2600)
            viewModel.dismissCaptureMessage()
        }
    }

    // Read the locale from LocalConfiguration (Compose-observable) rather than Locale.getDefault(),
    // which the NonObservableLocale lint flags because it does not react to runtime locale changes.
    val useChinese = (configuration.locales.get(0)?.language ?: "en").startsWith("zh")
    // Tap-to-focus is handled inside PreviewView (CameraController gestures). The view listener
    // here only *observes* the tap and returns false, so the real focus gesture is untouched —
    // this exists purely to mirror where focus was set, and the ring fades by itself.
    var focusCenter by remember { mutableStateOf<Offset?>(null) }
    LaunchedEffect(focusCenter) {
        if (focusCenter != null) {
            delay(900)
            focusCenter = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    // TextureView keeps the overlay compositing predictable across devices.
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    setOnTouchListener { _, event ->
                        if (event.actionMasked == android.view.MotionEvent.ACTION_UP) {
                            focusCenter = Offset(event.x, event.y)
                        }
                        false
                    }
                }
            },
            update = { it.controller = controller },
            modifier = Modifier.fillMaxSize(),
        )

        BirdOverlay(
            items = overlay,
            analysisAspect = analysisAspect,
            showBoxes = settings.showBoxes,
            identifyingText = stringResource(R.string.label_identifying),
            unknownText = stringResource(R.string.label_unknown_bird),
            tooSmallText = stringResource(R.string.label_too_small),
            chineseNames = useChinese,
            modifier = Modifier.fillMaxSize(),
        )

        FocusRing(center = focusCenter, modifier = Modifier.fillMaxSize())

        // Status chips must clear the status bar and any display cutout, not sit under them.
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                )
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (modelStatus) {
                ModelStatus.READY -> {
                    // A READY pipeline with no bird in frame is not "nothing to say": show the idle
                    // chip so a silent camera is distinguishable from a stalled one.
                    StatusChip(
                        text = if (overlay.isEmpty()) {
                            stringResource(R.string.status_waiting)
                        } else {
                            stringResource(R.string.status_scanning)
                        },
                        tone = if (overlay.isEmpty()) ChipTone.Idle else ChipTone.Good,
                    )
                }
                ModelStatus.LOADING -> StatusChip(
                    text = stringResource(R.string.status_model_loading),
                    tone = ChipTone.Idle,
                )
                ModelStatus.DETECTOR_ONLY -> StatusChip(
                    text = stringResource(R.string.status_detector_only),
                    tone = ChipTone.Warn,
                )
                ModelStatus.FAILED -> StatusChip(
                    text = stringResource(R.string.status_model_failed),
                    tone = ChipTone.Warn,
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal,
                    ),
                )
                .padding(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            captureState.message?.let { message ->
                CaptureFeedback(
                    message = message,
                    identified = captureState.identified,
                )
                Spacer(Modifier.height(16.dp))
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ZoomPill(
                    zoomRatio = zoomRatio,
                    onClick = { controller.setZoomRatio(1f) },
                )
                // Quick presets: pinching is awkward one-handed in the field, so common ratios are
                // one tap away. Only ratios the lens actually reaches are offered.
                val maxZoom = zoomState?.maxZoomRatio ?: 1f
                if (maxZoom >= 2f || zoomRatio > 1.02f) {
                    ZoomPreset(ratio = 2f, current = zoomRatio, onClick = { controller.setZoomRatio(2f) })
                }
                if (maxZoom >= 5f) {
                    ZoomPreset(ratio = 5f, current = zoomRatio, onClick = { controller.setZoomRatio(5f) })
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(36.dp),
            ) {
                ThumbnailButton(
                    uri = captureState.thumbnailUri,
                    onClick = { openGallery(context, captureState.thumbnailUri) },
                )
                ShutterButton(
                    busy = captureState.busy,
                    enabled = modelStatus != ModelStatus.LOADING,
                    onClick = { viewModel.capture() },
                )
                SettingsGlyphButton(onClick = { showSettings = true })
            }
        }
    }

    if (showSettings) {
        SettingsSheet(
            settings = settings,
            speciesCount = viewModel.speciesCount,
            modelVersion = viewModel.speciesModelVersion,
            onSaveMode = viewModel::setSaveMode,
            onShowBoxes = viewModel::setShowBoxes,
            onPreferGpu = viewModel::setPreferGpu,
            onConfidence = viewModel::setConfidenceThreshold,
            onDismiss = { showSettings = false },
        )
    }
}

@Composable
private fun CaptureFeedback(
    message: CaptureMessage,
    identified: List<String>,
    modifier: Modifier = Modifier,
) {
    val text = when (message) {
        CaptureMessage.ORIGINAL_SAVED -> stringResource(R.string.save_original_ok)
        CaptureMessage.LABELED_SAVED -> stringResource(R.string.save_labeled_ok)
        CaptureMessage.BOTH_SAVED -> stringResource(R.string.save_both_ok)
        CaptureMessage.PARTIAL -> stringResource(R.string.save_partial)
        CaptureMessage.MODEL_UNAVAILABLE -> stringResource(R.string.save_model_unavailable)
        CaptureMessage.FAILED -> stringResource(R.string.save_failed)
        CaptureMessage.NO_SPACE -> stringResource(R.string.save_no_space)
    }
    val detail = if (identified.isNotEmpty()) identified.distinct().joinToString("、") else null

    Column(
        modifier = modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .background(0xE6141A17.toInt().let { Color(it) })
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = BirdColors.OnBackground)
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = BirdColors.Accent,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun SettingsGlyphButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val settingsLabel = stringResource(R.string.settings)
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = settingsLabel },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(24.dp)) {
            val stroke = 2.dp.toPx()
            val xs = listOf(0.25f, 0.6f, 0.4f)
            for (i in 0..2) {
                val y = size.height * (0.22f + i * 0.28f)
                drawLine(
                    color = BirdColors.OnBackground,
                    start = androidx.compose.ui.geometry.Offset(0f, y),
                    end = androidx.compose.ui.geometry.Offset(size.width, y),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                val knobX = size.width * xs[i]
                drawCircle(color = BirdColors.Accent, radius = stroke * 1.8f, center = androidx.compose.ui.geometry.Offset(knobX, y))
            }
        }
    }
}

private fun openGallery(context: Context, uri: Uri?) {
    if (uri == null) return
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "image/jpeg")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}
