package de.wiresheep.kursplaner;
import android.app.*;
import android.content.*;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.*;
import java.time.*;
import java.util.UUID;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class AndroidWorkflowTest {
    private Application context;
    private PlanStore store;
    @Before public void setup() {
        context=RuntimeEnvironment.getApplication(); store=new PlanStore(context);
        context.getSharedPreferences("course_plans",0).edit().clear().commit();
        PlanScheduler.createChannel(context);
    }
    private CoursePlan plan(long startsInSeconds) {
        return new CoursePlan(UUID.randomUUID().toString(),"Yoga","Mannheim",Instant.now().plusSeconds(startsInSeconds),CoursePlan.Status.PLANNED);
    }
    private void deliver(CoursePlan p) {
        new BookingAlarmReceiver().onReceive(context,new Intent().setData(Uri.parse("kursfenster://plan/"+p.id)));
    }
    @Test public void persistentPlansSurviveStoreRecreationAndCanBeRemoved() {
        CoursePlan p=plan(90000); store.put(p);
        assertEquals(p.startsAt,new PlanStore(context).find(p.id).startsAt);
        store.remove(p.id); assertTrue(new PlanStore(context).all().isEmpty());
    }
    @Test public void schedulesAtOpeningAndCancels() {
        CoursePlan p=plan(172800); store.put(p); PlanScheduler.schedule(context,p);
        ShadowAlarmManager alarms=shadowOf(context.getSystemService(AlarmManager.class));
        assertEquals(1,alarms.getScheduledAlarms().size());
        assertEquals(p.opensAt().toEpochMilli(),alarms.getNextScheduledAlarm().triggerAtTime);
        PlanScheduler.cancel(context,p.id); assertTrue(alarms.getScheduledAlarms().isEmpty());
    }
    @Test public void duePlanSendsReminderOnlyOnce() {
        CoursePlan p=plan(3600); store.put(p); deliver(p);
        assertEquals(CoursePlan.Status.REMINDED,store.find(p.id).status);
        ShadowNotificationManager notifications=shadowOf(context.getSystemService(NotificationManager.class));
        assertEquals(1,notifications.size());
        Notification notification=notifications.getAllNotifications().get(0);
        assertTrue(notification.extras.getString(Notification.EXTRA_TEXT).contains("Noch nicht gebucht"));
        deliver(p); assertEquals(1,notifications.size());
    }
    @Test public void deletedPlanDoesNotNotify() {
        CoursePlan p=plan(3600); store.put(p); store.remove(p.id); deliver(p);
        assertEquals(0,shadowOf(context.getSystemService(NotificationManager.class)).size());
    }
    @Test public void earlyAlarmDoesNotNotifyAndIsRescheduled() {
        CoursePlan p=plan(172800); store.put(p); deliver(p);
        assertEquals(CoursePlan.Status.PLANNED,store.find(p.id).status);
        assertEquals(0,shadowOf(context.getSystemService(NotificationManager.class)).size());
        assertEquals(1,shadowOf(context.getSystemService(AlarmManager.class)).getScheduledAlarms().size());
    }
    @Test public void restoreExpiresPastPlansAndSchedulesFuturePlans() {
        CoursePlan past=plan(-60),future=plan(172800); store.put(past); store.put(future);
        new RestoreReceiver().onReceive(context,new Intent(Intent.ACTION_BOOT_COMPLETED));
        assertEquals(CoursePlan.Status.EXPIRED,store.find(past.id).status);
        assertEquals(CoursePlan.Status.PLANNED,store.find(future.id).status);
        assertEquals(1,shadowOf(context.getSystemService(AlarmManager.class)).getScheduledAlarms().size());
    }
    @Test public void blockedNotificationsDoNotClaimReminderWasSent() {
        shadowOf(context.getSystemService(NotificationManager.class)).setNotificationsEnabled(false);
        CoursePlan p=plan(3600); store.put(p); deliver(p);
        assertEquals(CoursePlan.Status.PLANNED,store.find(p.id).status);
        assertEquals(0,shadowOf(context.getSystemService(NotificationManager.class)).size());
    }
    @Test public void activityCanSaveCourseAndRestoreAfterRecreation() {
        try (var controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity=controller.get();
            ((EditText)activity.findViewById(1001)).setText("Mannheim");
            ((EditText)activity.findViewById(1002)).setText("Pilates");
            Button save=findButton(activity.getWindow().getDecorView(),"Plan speichern");
            assertNotNull(save); save.performClick();
            assertEquals(1,store.all().size()); assertEquals("Pilates",store.all().get(0).course);
            controller.recreate();
            assertNotNull(findButton(controller.get().getWindow().getDecorView(),"Details"));
        }
    }
    @Test public void automaticChoiceIsPersistedAndPreservedOnStatusChange() {
        CoursePlan p=plan(3600).withAutomatic(true); store.put(p);
        CoursePlan restored=new PlanStore(context).find(p.id);
        assertTrue(restored.automatic);
        store.put(restored.withStatus(CoursePlan.Status.ACTION_REQUIRED));
        assertTrue(store.find(p.id).automatic);
    }
    @Test public void automaticPlanWithoutServiceWaitsForUserToEnableHelper() {
        CoursePlan p=plan(3600).withAutomatic(true); store.put(p); deliver(p);
        assertEquals(CoursePlan.Status.WAITING_USER,store.find(p.id).status);
        assertFalse(BookingAccessibilityService.available());
    }
    @Test public void interruptedAutomationIsNeverRetriedAfterRestore() {
        CoursePlan p=plan(3600).withAutomatic(true).withStatus(CoursePlan.Status.AUTOMATING); store.put(p);
        PlanScheduler.restore(context);
        assertEquals(CoursePlan.Status.ACTION_REQUIRED,store.find(p.id).status);
        assertTrue(shadowOf(context.getSystemService(AlarmManager.class)).getScheduledAlarms().isEmpty());
    }
    @Test public void submittedAttemptIsNeverScheduledAgain() {
        CoursePlan p=plan(3600).withAutomatic(true).withStatus(CoursePlan.Status.VERIFY_REQUIRED); store.put(p);
        PlanScheduler.restore(context); deliver(p);
        assertEquals(CoursePlan.Status.VERIFY_REQUIRED,store.find(p.id).status);
        assertEquals(0,shadowOf(context.getSystemService(NotificationManager.class)).size());
    }
    @Test public void missingTargetAppDoesNotPretendToOpen() { assertFalse(VeniceBeach.open(context)); }
    private Button findButton(View v,String text) {
        if(v instanceof Button && ((Button)v).getText().toString().equals(text)) return (Button)v;
        if(v instanceof ViewGroup) for(int i=0;i<((ViewGroup)v).getChildCount();i++) {
            Button b=findButton(((ViewGroup)v).getChildAt(i),text); if(b!=null) return b;
        }
        return null;
    }
}
