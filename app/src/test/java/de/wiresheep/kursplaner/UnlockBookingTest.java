package de.wiresheep.kursplaner;

import android.app.*;
import android.content.*;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import java.time.*;
import java.util.UUID;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class) @Config(sdk=31)
public class UnlockBookingTest {
    private Application context;
    private PlanStore store;
    private ServiceController<BookingAccessibilityService> service;
    @Before public void setup() {
        context=RuntimeEnvironment.getApplication(); store=new PlanStore(context);
        context.getSharedPreferences("course_plans",0).edit().clear().commit();
        context.getSharedPreferences("booking_preferences",0).edit().clear().commit();
        locked(false); PlanScheduler.createChannel(context);
    }
    @After public void cleanup() { if(service!=null) service.destroy(); }
    private void locked(boolean locked) {
        shadowOf(context.getSystemService(KeyguardManager.class)).setKeyguardLocked(locked);
        shadowOf(context.getSystemService(PowerManager.class)).setIsInteractive(!locked);
    }
    private void enableHelper() {
        var manager=shadowOf(context.getPackageManager());
        var info=manager.addActivityIfNotPresent(new ComponentName(VeniceBeach.PACKAGE,VeniceBeach.PACKAGE+".MainActivity"));
        ResolveInfo resolve=new ResolveInfo(); resolve.activityInfo=info;
        manager.addResolveInfoForIntent(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_INFO).setPackage(VeniceBeach.PACKAGE),resolve);
        service=Robolectric.buildService(BookingAccessibilityService.class).create(); service.get().onServiceConnected();
        shadowOf(Looper.getMainLooper()).idle();
    }
    private CoursePlan plan(long seconds) {
        var plan=new CoursePlan(UUID.randomUUID().toString(),"Yoga","VeniceBeach Grünstadt",Instant.now().plusSeconds(seconds),CoursePlan.Status.PLANNED,true);
        store.put(plan); return plan;
    }
    private void alarm(CoursePlan plan) {
        new BookingAlarmReceiver().onReceive(context,new Intent().setData(Uri.parse("kursfenster://plan/"+plan.id)));
    }
    private void unlockEvent() {
        locked(false); context.sendBroadcast(new Intent(Intent.ACTION_USER_PRESENT));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600));
    }
    @Test public void lockedDuePlanIsPersistedAndNotLaunchedBeforeUnlock() {
        enableHelper(); BookingPreferences.waitForUnlock(context,true); locked(true);
        CoursePlan plan=plan(3600); alarm(plan);
        assertEquals(CoursePlan.Status.WAITING_UNLOCK,new PlanStore(context).find(plan.id).status);
        BookingDispatcher.resumeAfterUnlock(context);
        assertNull(shadowOf(context).getNextStartedActivity());
        var notifications=shadowOf(context.getSystemService(NotificationManager.class)).getAllNotifications();
        assertEquals(1,notifications.size()); assertEquals("Jetzt anmelden",notifications.get(0).actions[0].title.toString());
    }
    @Test public void actualUnlockBroadcastStartsExactlyOneAttempt() {
        enableHelper(); BookingPreferences.waitForUnlock(context,true); locked(true); CoursePlan plan=plan(3600); alarm(plan);
        unlockEvent(); assertEquals(CoursePlan.Status.AUTOMATING,store.find(plan.id).status);
        assertTrue(BookingAccessibilityService.running(plan.id)); assertNotNull(shadowOf(context).getNextStartedActivity());
        context.sendBroadcast(new Intent(Intent.ACTION_USER_PRESENT)); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600));
        assertNull(shadowOf(context).getNextStartedActivity());
    }
    @Test @Config(sdk=35) public void modernAndroidUnlockReceiverAcceptsSystemUiBroadcasts() {
        enableHelper();
        var receiver=shadowOf(context).getRegisteredReceivers().stream()
                .filter(r -> r.intentFilter.hasAction(Intent.ACTION_USER_PRESENT)).findFirst().orElseThrow();
        assertEquals(Context.RECEIVER_EXPORTED,receiver.flags);
        assertEquals(3,receiver.intentFilter.countActions());
        assertTrue(receiver.intentFilter.hasAction(Intent.ACTION_SCREEN_ON));
        assertTrue(receiver.intentFilter.hasAction(Intent.ACTION_SCREEN_OFF));
    }
    @Test @Config(sdk={31,35}) public void delayedKeyguardReleaseIsDetectedWithoutOpeningKursfenster() {
        enableHelper(); BookingPreferences.waitForUnlock(context,true); locked(true); CoursePlan plan=plan(3600); alarm(plan);
        context.sendBroadcast(new Intent(Intent.ACTION_USER_PRESENT));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1200));
        assertEquals(CoursePlan.Status.WAITING_UNLOCK,store.find(plan.id).status);
        locked(false); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000));
        assertEquals(CoursePlan.Status.AUTOMATING,store.find(plan.id).status);
        assertNotNull(shadowOf(context).getNextStartedActivity());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4));
        assertNull(shadowOf(context).getNextStartedActivity());
    }
    @Test public void screenOffCancelsPendingUnlockChecks() {
        enableHelper(); BookingPreferences.waitForUnlock(context,true); locked(true); CoursePlan plan=plan(3600); alarm(plan);
        context.sendBroadcast(new Intent(Intent.ACTION_USER_PRESENT)); shadowOf(Looper.getMainLooper()).idle();
        context.sendBroadcast(new Intent(Intent.ACTION_SCREEN_OFF)); shadowOf(Looper.getMainLooper()).idle();
        locked(false); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6));
        assertEquals(CoursePlan.Status.WAITING_UNLOCK,store.find(plan.id).status);
        assertNull(shadowOf(context).getNextStartedActivity());
    }
    @Test public void silentlyBlockedLaunchReturnsToExplicitStartWithoutRetryingOnUnlock() {
        enableHelper(); BookingPreferences.waitForUnlock(context,true); CoursePlan plan=plan(3600); alarm(plan);
        assertNotNull(shadowOf(context).getNextStartedActivity());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(14));
        assertEquals(CoursePlan.Status.WAITING_USER,store.find(plan.id).status);
        assertFalse(BookingAccessibilityService.running(plan.id));
        assertTrue(store.diagnostic(plan.id).contains("nicht im Vordergrund"));
        unlockEvent(); assertNull(shadowOf(context).getNextStartedActivity());
        BookingDispatcher.dispatch(context,plan.id,true);
        assertEquals(CoursePlan.Status.AUTOMATING,store.find(plan.id).status);
        assertNotNull(shadowOf(context).getNextStartedActivity());
    }
    @Test public void observedVeniceBeachWindowDoesNotCountAsABlockedLaunch() {
        enableHelper(); CoursePlan plan=plan(3600); alarm(plan);
        var event=android.view.accessibility.AccessibilityEvent.obtain(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        event.setPackageName(VeniceBeach.PACKAGE); service.get().onAccessibilityEvent(event);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(14));
        assertEquals(CoursePlan.Status.AUTOMATING,store.find(plan.id).status);
    }
    @Test public void screenOnWhileStillLockedCannotStartBooking() {
        enableHelper(); BookingPreferences.waitForUnlock(context,true); locked(true); CoursePlan plan=plan(3600); alarm(plan);
        shadowOf(context.getSystemService(PowerManager.class)).setIsInteractive(true);
        context.sendBroadcast(new Intent(Intent.ACTION_SCREEN_ON)); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600));
        assertEquals(CoursePlan.Status.WAITING_UNLOCK,store.find(plan.id).status); assertNull(shadowOf(context).getNextStartedActivity());
    }
    @Test public void automaticOpeningDisabledWaitsForExplicitStartEvenAfterUnlock() {
        enableHelper(); BookingPreferences.waitForUnlock(context,true); BookingPreferences.automaticOpen(context,false);
        locked(true); CoursePlan plan=plan(3600); alarm(plan); unlockEvent();
        assertEquals(CoursePlan.Status.WAITING_USER,store.find(plan.id).status); assertNull(shadowOf(context).getNextStartedActivity());
        BookingDispatcher.dispatch(context,plan.id,true);
        assertEquals(CoursePlan.Status.AUTOMATING,store.find(plan.id).status); assertNotNull(shadowOf(context).getNextStartedActivity());
    }
    @Test public void expiredWaitingCourseIsNotBookedOnUnlock() {
        enableHelper(); BookingPreferences.waitForUnlock(context,true);
        CoursePlan plan=plan(-1); store.put(plan.withStatus(CoursePlan.Status.WAITING_UNLOCK)); unlockEvent();
        assertEquals(CoursePlan.Status.EXPIRED,store.find(plan.id).status); assertNull(shadowOf(context).getNextStartedActivity());
    }
    @Test public void disablingUnlockWaitingKeepsPlanForManualStart() {
        enableHelper(); BookingPreferences.waitForUnlock(context,true); locked(true); CoursePlan plan=plan(3600); alarm(plan);
        BookingPreferences.waitForUnlock(context,false); BookingDispatcher.policyChanged(context); unlockEvent();
        assertEquals(CoursePlan.Status.WAITING_USER,store.find(plan.id).status); assertNull(shadowOf(context).getNextStartedActivity());
    }
    @Test public void submittedAndConfirmedPlansCannotBeRestartedByUnlockOrNotification() {
        enableHelper(); BookingPreferences.waitForUnlock(context,true);
        for(CoursePlan.Status status:new CoursePlan.Status[]{CoursePlan.Status.VERIFY_REQUIRED,CoursePlan.Status.BOOKED}) {
            CoursePlan plan=plan(3600); store.put(plan.withStatus(status));
            BookingDispatcher.resumeAfterUnlock(context); BookingDispatcher.dispatch(context,plan.id,true);
            assertEquals(status,store.find(plan.id).status);
        }
        assertNull(shadowOf(context).getNextStartedActivity());
    }
    @Test public void multipleWaitingPlansAreSerializedAfterUnlock() {
        enableHelper(); BookingPreferences.waitForUnlock(context,true);
        CoursePlan first=plan(3600),second=plan(7200); store.put(first.withStatus(CoursePlan.Status.WAITING_UNLOCK)); store.put(second.withStatus(CoursePlan.Status.WAITING_UNLOCK));
        BookingDispatcher.resumeAfterUnlock(context); BookingDispatcher.resumeAfterUnlock(context);
        assertEquals(CoursePlan.Status.AUTOMATING,store.find(first.id).status); assertEquals(CoursePlan.Status.QUEUED,store.find(second.id).status);
    }
    @Test public void lockingBeforeSubmissionDefersActiveAndQueuedPlans() {
        enableHelper(); BookingPreferences.waitForUnlock(context,true);
        CoursePlan first=plan(3600),second=plan(7200); alarm(first); alarm(second);
        locked(true); context.sendBroadcast(new Intent(Intent.ACTION_SCREEN_OFF)); shadowOf(Looper.getMainLooper()).idle();
        assertEquals(CoursePlan.Status.WAITING_UNLOCK,store.find(first.id).status); assertEquals(CoursePlan.Status.WAITING_UNLOCK,store.find(second.id).status);
        assertFalse(BookingAccessibilityService.running(first.id));
    }
    @Test public void disablingAutomaticOpeningPausesAttemptsThatHaveNotSubmitted() {
        enableHelper(); CoursePlan first=plan(3600),second=plan(7200); alarm(first); alarm(second);
        BookingPreferences.automaticOpen(context,false); BookingDispatcher.policyChanged(context);
        assertEquals(CoursePlan.Status.WAITING_USER,store.find(first.id).status); assertEquals(CoursePlan.Status.WAITING_USER,store.find(second.id).status);
        assertFalse(BookingAccessibilityService.running(first.id));
    }
    @Test public void clockChangeCannotBookBeforeTheOpeningWindow() {
        enableHelper(); BookingPreferences.waitForUnlock(context,true);
        CoursePlan future=plan(172800); store.put(future.withStatus(CoursePlan.Status.WAITING_UNLOCK)); BookingDispatcher.resumeAfterUnlock(context);
        assertEquals(CoursePlan.Status.PLANNED,store.find(future.id).status); assertNull(shadowOf(context).getNextStartedActivity());
        assertEquals(future.opensAt().toEpochMilli(),shadowOf(context.getSystemService(AlarmManager.class)).getNextScheduledAlarm().triggerAtTime);
    }
    @Test public void waitingPlanSurvivesBootWithoutAnAvailableHelper() {
        BookingPreferences.waitForUnlock(context,true); locked(true); CoursePlan plan=plan(3600); alarm(plan);
        new RestoreReceiver().onReceive(context,new Intent(Intent.ACTION_BOOT_COMPLETED));
        assertEquals(CoursePlan.Status.WAITING_UNLOCK,new PlanStore(context).find(plan.id).status);
    }
    @Test public void notificationActionLaunchesForegroundStartAndMainActivityConsumesIt() throws Exception {
        enableHelper(); BookingPreferences.automaticOpen(context,false); CoursePlan plan=plan(3600); alarm(plan);
        var notice=shadowOf(context.getSystemService(NotificationManager.class)).getAllNotifications().get(0);
        notice.actions[0].actionIntent.send(); Intent start=shadowOf(context).getNextStartedActivity();
        assertEquals(BookingDispatcher.START_ACTION,start.getAction()); assertEquals(plan.id,start.getData().getLastPathSegment());
        try(var controller=Robolectric.buildActivity(MainActivity.class,start).setup()) {
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(CoursePlan.Status.AUTOMATING,store.find(plan.id).status);
        }
    }
}
