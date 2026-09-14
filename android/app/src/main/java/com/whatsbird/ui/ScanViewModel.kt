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

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.camera.view.LifecycleCameraController
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.whatsbird.WhatsBirdApp
import com.whatsbird.camera.CameraConfigurator
import com.whatsbird.camera.FrameAnalyzer
import com.whatsbird.capture.CaptureOutcome
import com.whatsbird.capture.MediaStoreSaver
import com.whatsbird.capture.PhotoCapture
import com.whatsbird.capture.SaveResult
import com.whatsbird.classify.SpeciesClassifier
import com.whatsbird.detect.BirdDetector
import com.whatsbird.pipeline.BirdPipeline
import com.whatsbird.pipeline.ModelStatus
import com.whatsbird.pipeline.OverlayItem
import com.whatsbird.pipeline.PipelineStats
import com.whatsbird.settings.AppSettings
import com.whatsbird.settings.SaveMode
import com.whatsbird.util.BootGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

enum class CaptureMessage { ORIGINAL_SAVED, LABELED_SAVED, BOTH_SAVED, PARTIAL, MODEL_UNAVAILABLE, FAILED, NO_SPACE }

data class CaptureUiState(
    val busy: Boolean = false,
    val message: CaptureMessage? = null,
    val detail: String? = null,
    val thumbnailUri: Uri? = null,
    val identified: List<String> = emptyList(),
)

/**
 * Owns the camera controller, the model instances and the settings that drive them.
 *
 * Models are (re)built off the main thread because loading the detector takes long enough to drop
 * frames, and a GPU-preference change has to rebuild the pipeline while the preview keeps running.
 */
class ScanViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as WhatsBirdApp

    val settings: StateFlow<AppSettings> = app.settings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /**
     * The raw DataStore stream, collected for model rebuilds.
     *
     * Deliberately not [settings]: its seeded [AppSettings] default is emitted before storage has
     * been read, so building from it costs a full detector + classifier load on every launch and
     * then throws it away when the real value arrives — two model loads before the first frame.
     */
    private val settingsSource = app.settings.settings

    private val _overlay = MutableStateFlow<List<OverlayItem>>(emptyList())
    val overlay: StateFlow<List<OverlayItem>> = _overlay.asStateFlow()

    private val _modelStatus = MutableStateFlow(ModelStatus.LOADING)
    val modelStatus: StateFlow<ModelStatus> = _modelStatus.asStateFlow()

    private val _stats = MutableStateFlow(PipelineStats())
    val stats: StateFlow<PipelineStats> = _stats.asStateFlow()

    private val _captureState = MutableStateFlow(CaptureUiState())
    val captureState: StateFlow<CaptureUiState> = _captureState.asStateFlow()

    /** width / height of the upright analysis frame; drives the overlay coordinate mapping. */
    private val _analysisAspect = MutableStateFlow(4f / 3f)
    val analysisAspect: StateFlow<Float> = _analysisAspect.asStateFlow()

    private val analysisExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { Thread(it, "wb-frames") }
    private val captureIoExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { Thread(it, "wb-capture-io") }
    private val saver = MediaStoreSaver(application.applicationContext)
    private val bootGuard = BootGuard(application)

    private val analyzer = FrameAnalyzer(
        pipelineProvider = { pipeline },
        minDetectionIntervalMs = { minDetectionIntervalMs },
        onFrameGeometry = { width, height ->
            if (width > 0 && height > 0) {
                val aspect = width.toFloat() / height.toFloat()
                if (kotlin.math.abs(aspect - _analysisAspect.value) > 0.01f) _analysisAspect.value = aspect
            }
        },
    )

    @Volatile
    private var pipeline: BirdPipeline? = null

    @Volatile
    private var detector: BirdDetector? = null

    private var classifier: SpeciesClassifier? = null
    private var capture: PhotoCapture? = null
    private var controller: LifecycleCameraController? = null

    /** Null until the first settings emission decides whether to ask for a GPU delegate. */
    private var builtWithGpu: Boolean? = null

    /** Debounce so a detector that keeps throwing does not spin a rebuild loop. */
    private var lastDetectorErrorMs = 0L

    /** Collectors forwarding the current pipeline's streams; cancelled before every rebuild. */
    private var pipelineCollectors: Job? = null

    /** Incremented per model build; only the newest build installs its models. */
    private var buildToken = 0

    /**
     * Set when the previous launch died before the models came up. GPU bring-up is the only path
     * that can do that, so this process stays on CPU for its whole life — including before the
     * settings correction below has been written back, which would otherwise let the stale
     * `preferGpu = true` emission rebuild the pipeline that just killed us.
     */
    private var forceCpu = false

    /** Bumped on every model build, so a stale timer cannot clear a newer build's guard. */
    private var bootGeneration = 0

    private var minDetectionIntervalMs: Long = 1000L / 8L

    init {
        if (bootGuard.tripped()) {
            forceCpu = true
            Log.w(
                TAG,
                "previous launch died before the models came up (delegate=${bootGuard.failedDelegate()}); " +
                    "staying on CPU and turning the GPU preference off",
            )
            viewModelScope.launch { app.settings.setPreferGpu(false) }
        }

        viewModelScope.launch {
            settingsSource.collect { current ->
                minDetectionIntervalMs = 1000L / current.targetDetectionsPerSecond.coerceAtLeast(1)
                pipeline?.onSettingsChanged(current.confidenceThreshold)
                val useGpu = current.preferGpu && !forceCpu
                if (builtWithGpu != useGpu || pipeline == null) {
                    buildPipeline(useGpu, current.confidenceThreshold)
                }
            }
        }
    }

    private suspend fun buildPipeline(useGpu: Boolean, confidenceThreshold: Float) {
        builtWithGpu = useGpu
        _modelStatus.value = ModelStatus.LOADING
        // Cancel the previous pipeline's forwarders before closing it: BirdPipeline.close() stops the
        // detector and the executor but cannot stop the outside world collecting its StateFlows, so
        // without this every GPU toggle or error rebuild leaves another suspended collector — and
        // another reference to the old pipeline — behind.
        pipelineCollectors?.cancel()
        pipelineCollectors = null
        pipeline?.close()
        pipeline = null

        // Token for *this* build: a detector error rebuild can overlap a user's GPU-preference
        // rebuild, and model loading is slow enough that the older one can finish last. Whoever lands
        // second wins; the stale result is discarded rather than overwriting the newer state.
        val token = ++buildToken
        detector = null
        classifier = null
        capture = null
        _overlay.value = emptyList()

        // Armed before the detector is constructed, cleared once a detection has actually come
        // back: anything that aborts in between means this delegate cannot work on this device.
        bootGeneration += 1
        val generation = bootGeneration
        if (useGpu) {
            bootGuard.arm("gpu")
            // A bring-up abort happens within a second or two of the graph being created. Clearing
            // unconditionally after this window keeps a merely-short session — camera covered, app
            // closed straight away, no frame ever detected — from looking like a crash and quietly
            // costing the user the GPU on every later launch.
            viewModelScope.launch {
                delay(BOOT_GUARD_WINDOW_MS)
                if (generation == bootGeneration) bootGuard.clear()
            }
        } else {
            bootGuard.clear()
        }

        val built = withContext(Dispatchers.IO) {
            val dictionary = app.speciesDictionary
            val newDetector = runCatching { BirdDetector(getApplication(), useGpu) }
                .onFailure { Log.e(TAG, "detector unavailable", it) }
                .getOrNull()
            val newClassifier = if (dictionary?.isEmpty == false) {
                SpeciesClassifier.create(getApplication(), dictionary)
            } else {
                null
            }
            Triple(newDetector, newClassifier, dictionary)
        }

        if (token != buildToken) {
            // A newer build started while these models were loading; close ours and let that one win.
            Log.i(TAG, "discarding superseded model build token=$token current=$buildToken")
            runCatching { built.first?.close() }
            runCatching { built.second?.close() }
            return
        }

        installModels(
            newDetector = built.first,
            newClassifier = built.second,
            dictionary = built.third,
            confidenceThreshold = confidenceThreshold,
        )
    }

    /** Installs the freshly built model set and wires up the streams the UI observes. */
    private fun installModels(
        newDetector: BirdDetector?,
        newClassifier: SpeciesClassifier?,
        dictionary: com.whatsbird.species.SpeciesDictionary?,
        confidenceThreshold: Float,
    ) {
        detector = newDetector
        classifier = newClassifier
        newDetector?.setErrorListener { error ->
            val now = System.currentTimeMillis()
            Log.e(TAG, "detector runtime error", error)
            // A native detection error can leave the preview frozen; rebuild once, bounded by a
            // debounce so a persistently-failing detector cannot loop forever.
            if (now - lastDetectorErrorMs > DETECTOR_ERROR_REBUILD_DEBOUNCE_MS) {
                lastDetectorErrorMs = now
                viewModelScope.launch { buildPipeline(builtWithGpu ?: false, settings.value.confidenceThreshold) }
            }
        }
        if (newDetector == null) {
            pipeline = null
            capture = null
            _modelStatus.value = ModelStatus.FAILED
            // A construction failure is a caught exception, not an abort, so this run is safe to
            // repeat: disarm rather than penalise the next launch for it.
            bootGuard.clear()
            return
        }

        val newPipeline = BirdPipeline(
            detector = newDetector,
            classifier = newClassifier,
            dictionary = dictionary,
        )
        newPipeline.onSettingsChanged(confidenceThreshold)
        pipeline = newPipeline
        _modelStatus.value = newPipeline.status.value
        refreshCaptureHandler()

        // One parent job for all three forwarders so a rebuild cancels them in a single step.
        pipelineCollectors = viewModelScope.launch {
            launch { newPipeline.overlay.collect { _overlay.value = it } }
            launch { newPipeline.stats.collect { _stats.value = it } }
            launch { newPipeline.firstResultSeen.collect { seen -> if (seen) bootGuard.clear() } }
        }
    }

    private fun refreshCaptureHandler() {
        val controller = controller ?: return
        // The camera and the original-photo save must work even when the model failed to load: a
        // detection/model error must never take away the basic shutter. PhotoCapture accepts a null
        // detector/pipeline and simply produces an unlabeled photo in that case.
        capture = PhotoCapture(
            controller = controller,
            detector = detector,
            pipeline = pipeline,
            dictionary = app.speciesDictionary,
            saver = saver,
            ioExecutor = captureIoExecutor,
        )
    }

    fun onControllerAttached(controller: LifecycleCameraController, owner: LifecycleOwner) {
        this.controller = controller
        CameraConfigurator.configure(controller, analyzer, analysisExecutor)
        controller.bindToLifecycle(owner)
        refreshCaptureHandler()
    }

    fun onControllerDetached() {
        controller?.unbind()
        pipeline?.reset()
        _overlay.value = emptyList()
    }

    fun capture() {
        val active = capture
        if (active == null) {
            _captureState.value = CaptureUiState(message = CaptureMessage.FAILED, detail = "model unavailable")
            return
        }
        if (_captureState.value.busy) return
        val currentSettings = settings.value
        viewModelScope.launch {
            _captureState.value = _captureState.value.copy(busy = true, message = null)
            // Runs on the main dispatcher on purpose: CameraController.takePicture() asserts it is
            // on the main thread. The expensive work — decode, detect, classify, render, encode —
            // is moved off it inside PhotoCapture, so this call returns as soon as the shutter has
            // been asked to fire.
            val outcome = runCatching { active.capture(currentSettings) }
                .onFailure { Log.w(TAG, "capture threw", it) }
                .getOrNull()
            _captureState.value = when (outcome) {
                is CaptureOutcome.Success -> CaptureUiState(
                    busy = false,
                    message = when {
                        outcome.modelUnavailable -> CaptureMessage.MODEL_UNAVAILABLE
                        outcome.labeledFailed -> CaptureMessage.PARTIAL
                        outcome.original != null && outcome.labeled != null -> CaptureMessage.BOTH_SAVED
                        outcome.original != null -> CaptureMessage.ORIGINAL_SAVED
                        outcome.labeled != null -> CaptureMessage.LABELED_SAVED
                        else -> CaptureMessage.FAILED
                    },
                    thumbnailUri = (outcome.labeled ?: outcome.original)?.uri,
                    identified = outcome.identifiedNames,
                )
                is CaptureOutcome.Failure -> CaptureUiState(
                    busy = false,
                    message = when (outcome.reason) {
                        CaptureOutcome.FailureReason.OUT_OF_SPACE -> CaptureMessage.NO_SPACE
                        else -> CaptureMessage.FAILED
                    },
                    detail = outcome.detail,
                )
                null -> CaptureUiState(busy = false, message = CaptureMessage.FAILED)
            }
        }
    }

    fun dismissCaptureMessage() {
        _captureState.value = _captureState.value.copy(message = null, detail = null)
    }

    fun setSaveMode(mode: SaveMode) = viewModelScope.launch { app.settings.setSaveMode(mode) }

    fun setShowBoxes(enabled: Boolean) = viewModelScope.launch { app.settings.setShowBoxes(enabled) }

    fun setPreferGpu(enabled: Boolean) = viewModelScope.launch { app.settings.setPreferGpu(enabled) }

    fun setConfidenceThreshold(value: Float) =
        viewModelScope.launch { app.settings.setConfidenceThreshold(value) }

    /** Exposed so the settings sheet can state what the bundled model actually covers. */
    val speciesCount: Int get() = app.speciesDictionary?.classes?.size ?: 0

    val speciesModelVersion: String get() = app.speciesDictionary?.modelVersion ?: "none"

    override fun onCleared() {
        super.onCleared()
        capture = null
        pipeline?.close()
        pipeline = null
        detector = null
        classifier?.close()
        classifier = null
        analysisExecutor.shutdownNow()
        captureIoExecutor.shutdownNow()
    }

    companion object {
        private const val TAG = "ScanViewModel"

        /** How long a GPU pipeline must survive before its delegate counts as proven on this device. */
        private const val BOOT_GUARD_WINDOW_MS = 15_000L

        /** Minimum gap between detector-error-driven pipeline rebuilds, to avoid a rebuild loop. */
        private const val DETECTOR_ERROR_REBUILD_DEBOUNCE_MS = 10_000L
    }
}
