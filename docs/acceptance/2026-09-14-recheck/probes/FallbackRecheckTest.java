package com.whatsbird.audit;

import android.net.Uri;
import androidx.camera.view.LifecycleCameraController;
import com.whatsbird.capture.CaptureOutcome;
import com.whatsbird.capture.PhotoCapture;
import com.whatsbird.capture.SaveResult;
import com.whatsbird.classify.SpeciesClassifier;
import com.whatsbird.detect.BirdDetector;
import com.whatsbird.pipeline.ModelStatus;
import com.whatsbird.species.SpeciesDictionary;
import com.whatsbird.ui.CaptureMessage;
import com.whatsbird.ui.CaptureUiState;
import com.whatsbird.ui.ScanViewModel;
import com.whatsbird.util.BootGuard;
import java.lang.reflect.*;
import java.util.*;
import kotlinx.coroutines.flow.MutableStateFlow;
import kotlinx.coroutines.flow.StateFlowKt;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Isolated branch probes: avoid starting native models or a real camera in a JVM test. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34, manifest=Config.NONE)
public class FallbackRecheckTest {
    private static Field field(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name); f.setAccessible(true); return f;
    }
    private static <T> T uninitialized(Class<T> type) throws Exception {
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Object unsafe = field(unsafeType, "theUnsafe").get(null);
        return type.cast(unsafeType.getMethod("allocateInstance", Class.class).invoke(unsafe, type));
    }

    @Test public void detectorLoadFailureMustNotDisableExistingCameraCapture() throws Exception {
        ScanViewModel vm = uninitialized(ScanViewModel.class);
        // Camera attachment has already established a capture handler before model loading ends.
        field(ScanViewModel.class, "controller").set(vm, uninitialized(LifecycleCameraController.class));
        field(ScanViewModel.class, "capture").set(vm, uninitialized(PhotoCapture.class));
        MutableStateFlow<ModelStatus> model = StateFlowKt.MutableStateFlow(ModelStatus.LOADING);
        MutableStateFlow<CaptureUiState> state = StateFlowKt.MutableStateFlow(new CaptureUiState());
        field(ScanViewModel.class, "_modelStatus").set(vm, model);
        field(ScanViewModel.class, "_captureState").set(vm, state);
        field(ScanViewModel.class, "bootGuard").set(vm, new BootGuard(RuntimeEnvironment.getApplication()));
        Method install = ScanViewModel.class.getDeclaredMethod("installModels",
            BirdDetector.class, SpeciesClassifier.class, SpeciesDictionary.class, float.class);
        install.setAccessible(true);
        install.invoke(vm, null, null, null, .55f);
        assertEquals(ModelStatus.FAILED, model.getValue());
        Object handler = field(ScanViewModel.class, "capture").get(vm);
        if (handler == null) {
            vm.capture();
            assertEquals(CaptureMessage.FAILED, state.getValue().getMessage());
            assertEquals("model unavailable", state.getValue().getDetail());
        }
        assertNotNull("Model initialization failure erased the camera capture handler", handler);
    }

    @Test public void savingAnUnannotatedCopyMustNotHideModelFailure() throws Exception {
        PhotoCapture capture = uninitialized(PhotoCapture.class);
        Method resolve = PhotoCapture.class.getDeclaredMethod("resolve", SaveResult.class,
            SaveResult.Saved.class, boolean.class, List.class, boolean.class,
            CaptureOutcome.FailureReason.class, boolean.class);
        resolve.setAccessible(true);
        SaveResult.Saved original = new SaveResult.Saved(Uri.parse("content://media/external/images/media/1"));
        SaveResult.Saved labeled = new SaveResult.Saved(Uri.parse("content://media/external/images/media/2"));
        CaptureOutcome.Success result = (CaptureOutcome.Success) resolve.invoke(capture,
            original, labeled, true, Collections.emptyList(), false, null, true);
        assertNotNull(result.getOriginal());
        assertNotNull(result.getLabeled());
        assertTrue("Writing an empty label copy cleared modelUnavailable, so the UI reports BOTH_SAVED", result.getModelUnavailable());
    }
}
