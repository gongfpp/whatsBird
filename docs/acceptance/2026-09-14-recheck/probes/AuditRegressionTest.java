package com.whatsbird.audit;
import android.graphics.Bitmap;
import android.graphics.RectF;
import com.whatsbird.detect.RawDetection;
import com.whatsbird.pipeline.BirdPipeline;
import com.whatsbird.label.LabelStabilizer;
import com.whatsbird.track.BirdTracker;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=29, manifest=Config.NONE)
public class AuditRegressionTest {
 private static Field f(Class<?> c,String n)throws Exception {Field x=c.getDeclaredField(n);x.setAccessible(true);return x;}
 private static Method m(Class<?> c,String n,Class<?>...t)throws Exception {Method x=c.getDeclaredMethod(n,t);x.setAccessible(true);return x;}
 private static BirdPipeline pipeline(BirdTracker t){return new BirdPipeline(null,null,null,t,new LabelStabilizer(-1,6,2,0.5f,0.72f,2200L,3500L));}
 @Test public void temporarilyMissingTrackMustBeEligibleForClassificationAgain()throws Exception {
  BirdTracker tracker=new BirdTracker(); BirdPipeline p=pipeline(tracker);
  List<RawDetection> birds=Collections.singletonList(new RawDetection(new RectF(.2f,.2f,.5f,.5f),.9f));
  int id=tracker.update(birds,1000L).get(0).getId();
  Class<?> mc=Class.forName("com.whatsbird.pipeline.BirdPipeline$TrackMemory");
  Constructor<?> ct=mc.getDeclaredConstructor();ct.setAccessible(true);Object mem=ct.newInstance();f(mc,"enqueued").setBoolean(mem,true);
  ((Map)f(BirdPipeline.class,"memory").get(p)).put(id,mem);
  Class<?> jc=Class.forName("com.whatsbird.pipeline.BirdPipeline$ClassifyJob");
  Constructor<?> jt=jc.getDeclaredConstructor(int.class,long.class,Bitmap.class,int.class,float.class);jt.setAccessible(true);
  Object job=jt.newInstance(id,1000L,Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888),0,.09f);
  ((Deque)f(BirdPipeline.class,"queue").get(p)).add(job);
  tracker.update(Collections.emptyList(),1100L);
  m(BirdPipeline.class,"drainQueue").invoke(p);
  ((ExecutorService)f(BirdPipeline.class,"classifyExecutor").get(p)).submit(()->{}).get(3,TimeUnit.SECONDS);
  int reacquired=tracker.update(birds,1200L).get(0).getId();
  try {assertEquals("same track must have returned",id,reacquired);assertFalse("discarded crop must release enqueued; otherwise handleDetections skips this bird forever",f(mc,"enqueued").getBoolean(mem));}
  finally {p.close();}
 }
 @Test public void detectionFpsMustNotCountRejectedFrames()throws Exception {
  BirdPipeline p=pipeline(new BirdTracker());f(BirdPipeline.class,"statsWindowStart").setLong(p,1000L);
  Method update=m(BirdPipeline.class,"updateStats",long.class);
  // updateStats is called by both rejection branches as well as successful completions.
  // No detector has completed any frame in this fixture.
  for(int i=1;i<=20;i++) update.invoke(p,1000L+i*50);
  try {assertEquals("zero completed detections should report zero detection FPS",0f,p.getStats().getValue().getDetectionFps(),.001f);}
  finally {p.close();}
 }
}
