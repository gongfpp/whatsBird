package com.whatsbird.audit;
import com.whatsbird.pipeline.BirdPipeline;
import com.whatsbird.track.BirdTracker;
import com.whatsbird.label.LabelStabilizer;
import java.lang.reflect.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34, manifest=Config.NONE)
public class StatsLoggingRecheckTest {
    @Test public void statisticsMustRemainSafeWhenPeriodicLoggingStarts() throws Exception {
        BirdPipeline p = new BirdPipeline(null, null, null, new BirdTracker(),
            new LabelStabilizer(-1, 6, 2, .5f, .72f, 2200L, 3500L));
        Field start = BirdPipeline.class.getDeclaredField("statsWindowStart");
        start.setAccessible(true); start.setLong(p, 1000L);
        Method update = BirdPipeline.class.getDeclaredMethod("updateStats", long.class);
        update.setAccessible(true);
        try {
            // Existing tests stop at 2 seconds and never reach the 5-second logging branch.
            update.invoke(p, 7000L);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        } finally { p.close(); }
    }
}
