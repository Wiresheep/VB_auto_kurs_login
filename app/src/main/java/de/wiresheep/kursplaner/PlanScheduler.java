package de.wiresheep.kursplaner;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import java.time.Instant;

public final class PlanScheduler {
    public static final String CHANNEL = "course_openings";
    private PlanScheduler() {}
    public static boolean exactAllowed(Context context) {
        return Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager.class).canScheduleExactAlarms();
    }
    public static boolean notificationsAllowed(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
        return manager.areNotificationsEnabled()
                && (channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE)
                && (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED);
    }
    public static void createChannel(Context context) {
        context.getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "Kursbuchung öffnet", NotificationManager.IMPORTANCE_HIGH));
    }
    private static PendingIntent alarmIntent(Context context, String id) {
        Intent intent = new Intent(context, BookingAlarmReceiver.class)
                .setData(Uri.parse("kursfenster://plan/" + id));
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    public static void cancel(Context context, String id) {
        context.getSystemService(AlarmManager.class).cancel(alarmIntent(context,id));
        context.getSystemService(NotificationManager.class).cancel(id, 1);
    }
    public static void schedule(Context context, CoursePlan plan) {
        if (plan.status != CoursePlan.Status.PLANNED) return;
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        long trigger = Math.max(System.currentTimeMillis() + 500, plan.opensAt().toEpochMilli());
        PendingIntent intent = alarmIntent(context, plan.id);
        if (exactAllowed(context)) {
            try { alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, intent); return; }
            catch (SecurityException ignored) { /* Permission may have changed after the check. */ }
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, intent);
    }
    public static void notifyOutcome(Context context, CoursePlan plan, boolean submitted) {
        createChannel(context);
        if(!notificationsAllowed(context)) return;
        Intent open=new Intent(context,MainActivity.class).setData(Uri.parse("kursfenster://plan/"+plan.id));
        PendingIntent action=PendingIntent.getActivity(context,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        boolean booked=plan.status==CoursePlan.Status.BOOKED;
        Notification notification=new Notification.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_course)
                .setContentTitle((booked ? "Anmeldung bestätigt: " : submitted ? "Buchungsversuch gesendet: " : "Automatik angehalten: ")+plan.course)
                .setContentText(booked ? "VeniceBeach zeigt Vom Kurs abmelden. Du bist angemeldet."
                        : submitted ? "Bitte Anmeldung in VeniceBeach prüfen. Erfolg noch nicht bestätigt."
                        : "Bitte manuell anmelden. Daten oder Gerätezustand konnten nicht sicher geprüft werden.")
                .setContentIntent(action).setAutoCancel(true).build();
        try { context.getSystemService(NotificationManager.class).notify(plan.id,1,notification); }
        catch(SecurityException ignored) { }
    }
    public static void restore(Context context) {
        createChannel(context);
        PlanStore store = new PlanStore(context);
        for (CoursePlan plan : store.all()) {
            if((plan.status==CoursePlan.Status.AUTOMATING || plan.status==CoursePlan.Status.QUEUED)
                    && !BookingAccessibilityService.running(plan.id)) {
                store.put(plan.withStatus(CoursePlan.Status.ACTION_REQUIRED));
            } else if (BookingDispatcher.pending(plan.status) && plan.isExpired(Instant.now())) {
                cancel(context, plan.id); store.put(plan.withStatus(CoursePlan.Status.EXPIRED));
            } else schedule(context, plan);
        }
        if(BookingAccessibilityService.available()) BookingDispatcher.resumeAfterUnlock(context);
    }
}
