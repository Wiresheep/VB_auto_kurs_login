package de.wiresheep.kursplaner;

import android.app.*;
import android.content.*;
import java.time.Instant;

/** Dispatches optional automation or a reminder; neither implies a confirmed booking. */
public final class BookingAlarmReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent.getData() == null) return;
        String id = intent.getData().getLastPathSegment();
        PlanStore store = new PlanStore(context);
        CoursePlan plan = store.find(id);
        if (plan == null || plan.status != CoursePlan.Status.PLANNED) return;
        Instant now = Instant.now();
        if (plan.isExpired(now)) { store.put(plan.withStatus(CoursePlan.Status.EXPIRED)); return; }
        if (plan.opensAt().isAfter(now)) { PlanScheduler.schedule(context, plan); return; }
        if(plan.automatic) { BookingDispatcher.dispatch(context,plan.id,false); return; }
        PlanScheduler.createChannel(context);
        if (!PlanScheduler.notificationsAllowed(context)) return;
        Intent open = new Intent(context, MainActivity.class).setData(intent.getData());
        PendingIntent content = PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(context, PlanScheduler.CHANNEL)
                .setSmallIcon(de.wiresheep.kursplaner.R.drawable.ic_course)
                .setContentTitle("Anmeldefenster offen: " + plan.course)
                .setContentText(plan.studio + " · Bitte in VeniceBeach anmelden. Noch nicht gebucht.")
                .setStyle(new Notification.BigTextStyle().bigText(plan.studio
                        + " · Der Kurs kann jetzt gebucht werden. Öffne VeniceBeach und bestätige die Anmeldung dort."))
                .setContentIntent(content).setAutoCancel(true).build();
        try {
            context.getSystemService(NotificationManager.class).notify(plan.id, 1, notification);
            if (!plan.automatic) store.put(plan.withStatus(CoursePlan.Status.REMINDED));
        } catch (SecurityException ignored) { /* Retain PLANNED so permission recovery can retry. */ }
    }
}
