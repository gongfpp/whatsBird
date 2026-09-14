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

package com.whatsbird.camera

import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import java.util.concurrent.Executor

/**
 * One place that decides how CameraX is wired, so preview and analysis can never end up with
 * different aspect ratios — the overlay's coordinate mapping depends on that being true.
 */
object CameraConfigurator {

    /** 4:3 matches the sensor on essentially every phone and keeps more vertical field of view. */
    private val ASPECT = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY

    private val ANALYSIS_TARGET = Size(1280, 960)

    /**
     * Upper bound for the still capture, and the reason the app does not fall over on high-megapixel
     * sensors.
     *
     * Leaving this unset lets CameraX take the largest stream the sensor offers — a 64MP shot on a
     * Redmi Note 8 Pro is 6936x9248, which decodes to ~256 MB as ARGB_8888 before the rotate and
     * annotation copies, i.e. roughly 700 MB of bitmaps for one shutter press. A bird ID app gains
     * nothing from that: 2560x1920 is still four times the detail the classifier sees and keeps the
     * whole capture path comfortably inside the heap.
     */
    private val CAPTURE_TARGET = Size(2560, 1920)

    fun configure(
        controller: LifecycleCameraController,
        analyzer: ImageAnalysis.Analyzer,
        analyzerExecutor: Executor,
    ) {
        controller.cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        controller.setEnabledUseCases(CameraController.IMAGE_CAPTURE or CameraController.IMAGE_ANALYSIS)
        controller.setImageAnalysisBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        controller.setImageCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        controller.setPinchToZoomEnabled(true)
        controller.setTapToFocusEnabled(true)

        controller.setPreviewResolutionSelector(
            ResolutionSelector.Builder()
                .setAspectRatioStrategy(ASPECT)
                .build(),
        )
        controller.setImageCaptureResolutionSelector(
            ResolutionSelector.Builder()
                .setAspectRatioStrategy(ASPECT)
                .setResolutionStrategy(
                    ResolutionStrategy(CAPTURE_TARGET, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
                )
                .build(),
        )
        controller.setImageAnalysisResolutionSelector(
            ResolutionSelector.Builder()
                .setAspectRatioStrategy(ASPECT)
                .setResolutionStrategy(
                    ResolutionStrategy(ANALYSIS_TARGET, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
                )
                .build(),
        )
        controller.setImageAnalysisAnalyzer(analyzerExecutor, analyzer)
    }
}
