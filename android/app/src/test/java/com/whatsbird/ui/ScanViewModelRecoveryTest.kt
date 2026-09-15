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

import androidx.activity.ComponentActivity
import androidx.camera.view.LifecycleCameraController
import com.whatsbird.WhatsBirdApp
import com.whatsbird.pipeline.ModelStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Field
import java.util.concurrent.TimeUnit

/**
 * Permanent regression coverage for the capture-handler lifecycle around model failure.
 *
 * The UI promises "模型加载失败，仅可拍照" whenever the status chip shows FAILED, so the shutter
 * must survive *every* order in which a failed model build and the camera controller arrive:
 *
 *  - controller attach → model fail: `buildPipeline()` clears `capture` before loading, so the
 *    FAILED branch has to rebuild the capture handler once it gives up;
 *  - model fail → controller attach: `onControllerAttached()` must (re)build it after the failure;
 *  - settings-driven rebuild → model fail: a rebuild that ends in FAILED must not strand the shutter.
 *
 * Under Robolectric the MediaPipe and LiteRT native libraries cannot load, so the detector
 * construction attempt genuinely fails and the real FAILED path runs every time — no test stubs
 * needed. The `capture` field is reached through reflection because it is internal state; the
 * assertions are about the state machine, not the field's privacy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScanViewModelRecoveryTest {

    private val captureField: Field =
        ScanViewModel::class.java.getDeclaredField("capture").also { it.isAccessible = true }

    private fun captureOf(viewModel: ScanViewModel): Any? = captureField.get(viewModel)

    /** Activity-backed lifecycle owner, context and controller, exactly what the UI wires up. */
    private fun newViewModel(): Pair<ScanViewModel, ComponentActivity> {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        return ScanViewModel(activity.application as WhatsBirdApp) to activity
    }

    private fun attachController(viewModel: ScanViewModel, activity: ComponentActivity) {
        viewModel.onControllerAttached(LifecycleCameraController(activity), activity)
    }

    /** Runs the main-looper work and polls until the model status reaches [status]. */
    private fun awaitStatus(viewModel: ScanViewModel, status: ModelStatus) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (viewModel.modelStatus.value != status) {
            if (System.nanoTime() > deadline) {
                assertEquals(
                    "model status did not reach $status in time",
                    status,
                    viewModel.modelStatus.value,
                )
            }
            Thread.sleep(25)
            Robolectric.flushForegroundThreadScheduler()
        }
    }

    @Test
    fun `controller attach then model fail still leaves the shutter usable`() {
        val (viewModel, activity) = newViewModel()
        attachController(viewModel, activity)
        awaitStatus(viewModel, ModelStatus.FAILED)
        assertNotNull(
            "after a failed build with the controller attached, capture must be rebuilt so the " +
                "promised 'model failed, photo only' shutter actually works",
            captureOf(viewModel),
        )
    }

    @Test
    fun `model fail then controller attach builds the capture handler`() {
        val (viewModel, activity) = newViewModel()
        awaitStatus(viewModel, ModelStatus.FAILED)
        attachController(viewModel, activity)
        assertNotNull(
            "attaching the controller after a failed build must create the capture handler",
            captureOf(viewModel),
        )
    }

    @Test
    fun `settings-driven rebuild that fails still keeps the capture handler alive`() {
        val (viewModel, activity) = newViewModel()
        attachController(viewModel, activity)
        awaitStatus(viewModel, ModelStatus.FAILED)

        // A settings-driven rebuild (the equivalent of the real GPU-preference rebuild path): the
        // pipeline is torn down first, clearing the capture handler, and must come back once the
        // rebuild ends in FAILED again.
        viewModel.setPreferGpu(false)
        awaitStatus(viewModel, ModelStatus.FAILED)
        assertNotNull(
            "a rebuild that ends in FAILED must not leave the shutter dead",
            captureOf(viewModel),
        )
    }
}
