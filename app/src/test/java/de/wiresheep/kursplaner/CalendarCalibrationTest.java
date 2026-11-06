package de.wiresheep.kursplaner;

import android.app.Application;
import android.graphics.PointF;
import android.view.Surface;
import android.view.WindowManager;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.time.LocalDate;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class CalendarCalibrationTest {
    private Application context;
    private PointF first,next;
    @Before public void setup() {
        context=RuntimeEnvironment.getApplication(); CalendarCalibration.clear(context);
        var screen=CalendarCalibration.screen(context);
        first=new PointF(screen.widthPixels*.25f,screen.heightPixels*.3f);
        next=new PointF(screen.widthPixels*.42f,screen.heightPixels*.3f);
    }
    @Test public void savedPositionsMapTodayAndTomorrowAcrossMonthAndYearChanges() {
        CalendarCalibration.save(context,first,next);
        for(LocalDate today:new LocalDate[]{LocalDate.of(2026,11,30),LocalDate.of(2026,12,31),LocalDate.of(2028,2,29)}) {
            assertEquals(first.x,CalendarCalibration.point(context,today,today).x,0);
            assertEquals(next.x,CalendarCalibration.point(context,today,today.plusDays(1)).x,0);
            assertNull(CalendarCalibration.point(context,today,today.plusDays(2)));
        }
    }
    @Test public void changedOrientationOrScreenSizeRequiresNewCalibration() {
        CalendarCalibration.save(context,first,next);
        var display=context.getSystemService(WindowManager.class).getDefaultDisplay();
        int oldRotation=display.getRotation();
        shadowOf(display).setRotation(Surface.ROTATION_90);
        assertFalse(CalendarCalibration.available(context));
        shadowOf(display).setRotation(oldRotation);
        assertTrue(CalendarCalibration.available(context));
        shadowOf(display).setRealWidth(CalendarCalibration.screen(context).widthPixels+10);
        assertFalse(CalendarCalibration.available(context));
    }
    @Test public void reversedOrInvalidPositionsAreRejectedAndClearRemovesCalibration() {
        try { CalendarCalibration.save(context,next,first); fail(); } catch(IllegalArgumentException expected) { }
        try { CalendarCalibration.save(context,new PointF(Float.NaN,first.y),next); fail(); } catch(IllegalArgumentException expected) { }
        assertFalse(CalendarCalibration.available(context));
        CalendarCalibration.save(context,first,next); CalendarCalibration.clear(context);
        assertFalse(CalendarCalibration.available(context));
    }
    @Test public void overlayCapturesTwoTapsWithoutPerformingUnderlyingClicks() {
        final PointF[] captured=new PointF[2];
        var view=new CalendarCalibrationView(context,new CalendarCalibrationView.Listener() {
            public void finished(PointF today,PointF tomorrow) { captured[0]=today; captured[1]=tomorrow; }
            public void cancelled() { fail("Unexpected cancellation"); }
        });
        view.layout(0,0,320,480);
        var one=android.view.MotionEvent.obtain(0,0,android.view.MotionEvent.ACTION_UP,80,140,0);
        var two=android.view.MotionEvent.obtain(0,10,android.view.MotionEvent.ACTION_UP,135,140,0);
        view.onTouchEvent(one); assertNull(captured[0]); view.onTouchEvent(two);
        assertEquals(80,captured[0].x,0); assertEquals(135,captured[1].x,0);
        one.recycle(); two.recycle();
    }
    @Test public void markerUsesLocalCoordinatesButCalibrationRetainsScreenCoordinates() {
        try(var controller=Robolectric.buildActivity(android.app.Activity.class).setup()) {
            var activity=controller.get(); var frame=new android.widget.FrameLayout(activity); activity.setContentView(frame);
            final PointF[] saved=new PointF[2];
            var view=new CalendarCalibrationView(activity,new CalendarCalibrationView.Listener() {
                public void finished(PointF today,PointF tomorrow) { saved[0]=today; saved[1]=tomorrow; }
                public void cancelled() { fail(); }
            });
            var params=new android.widget.FrameLayout.LayoutParams(320,480); params.leftMargin=12; params.topMargin=70;
            frame.addView(view,params);
            frame.measure(android.view.View.MeasureSpec.makeMeasureSpec(400,1073741824),android.view.View.MeasureSpec.makeMeasureSpec(800,1073741824));
            frame.layout(0,0,400,800);
            int[] origin=new int[2]; view.getLocationOnScreen(origin); assertTrue(origin[1]>=70);
            var firstTap=android.view.MotionEvent.obtain(0,0,android.view.MotionEvent.ACTION_UP,100,200,0);
            firstTap.offsetLocation(-origin[0],-origin[1]); view.onTouchEvent(firstTap);
            assertEquals(firstTap.getX(),view.markerPosition().x,.01); assertEquals(firstTap.getY(),view.markerPosition().y,.01);
            var secondTap=android.view.MotionEvent.obtain(0,1,android.view.MotionEvent.ACTION_UP,150,200,0);
            secondTap.offsetLocation(-origin[0],-origin[1]); view.onTouchEvent(secondTap);
            assertEquals(100,saved[0].x,.01); assertEquals(200,saved[0].y,.01);
            assertEquals(150,saved[1].x,.01); assertEquals(200,saved[1].y,.01);
            firstTap.recycle(); secondTap.recycle();
        }
    }

}
