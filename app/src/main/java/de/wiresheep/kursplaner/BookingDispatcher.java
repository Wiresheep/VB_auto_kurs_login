package de.wiresheep.kursplaner;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.PowerManager;
import java.time.Instant;

/** Dispatches only attempts that have not submitted a booking click. Waiting survives process death. */
final class BookingDispatcher {
    static final String START_ACTION="de.wiresheep.kursplaner.START_BOOKING";
    private BookingDispatcher() {}
    static boolean pending(CoursePlan.Status status) {
        return status==CoursePlan.Status.PLANNED || status==CoursePlan.Status.WAITING_UNLOCK || status==CoursePlan.Status.WAITING_USER;
    }
    static boolean unlocked(Context context) {
        return !context.getSystemService(KeyguardManager.class).isKeyguardLocked()
                && context.getSystemService(PowerManager.class).isInteractive();
    }
    static void dispatch(Context context,String id,boolean userRequested) {
        PlanStore store=new PlanStore(context); CoursePlan plan=store.find(id);
        if(plan==null || !plan.automatic || !pending(plan.status)) return;
        Instant now=Instant.now();
        if(plan.isExpired(now)) { expire(context,plan); return; }
        if(plan.opensAt().isAfter(now)) {
            CoursePlan planned=plan.withStatus(CoursePlan.Status.PLANNED); store.put(planned); PlanScheduler.schedule(context,planned); return;
        }
        if(plan.isWeb()) { WebBookingService.start(context,id); return; }
        if(!unlocked(context)) {
            if(BookingPreferences.waitForUnlock(context)) waitForUnlock(context,plan);
            else if(!BookingPreferences.automaticOpen(context) || userRequested) waitForUser(context,plan,"Bitte das Handy entsperren und die Anmeldung starten.");
            else {
                CoursePlan stopped=plan.withStatus(CoursePlan.Status.ACTION_REQUIRED); store.put(stopped);
                store.diagnostic(id,"Handy gesperrt oder Bildschirm aus. Nachholen beim Entsperren ist ausgeschaltet.");
                PlanScheduler.notifyOutcome(context,stopped,false);
            }
            return;
        }
        if(!userRequested && !BookingPreferences.automaticOpen(context)) {
            waitForUser(context,plan,"Du entscheidest, wann VeniceBeach geöffnet wird. Jetzt anmelden startet die sichtbare Buchung."); return;
        }
        if(!BookingAccessibilityService.begin(context,plan))
            waitForUser(context,plan,"Bitte den Buchungshelfer aktivieren bzw. die Kursauswahl beenden. Danach Jetzt anmelden wählen.");
    }
    static void waitForUnlock(Context context,CoursePlan plan) {
        saveWaiting(context,plan,CoursePlan.Status.WAITING_UNLOCK,"Wartet auf ein entsperrtes, eingeschaltetes Handy. Noch kein Buchungsklick gesendet.");
    }
    static void waitForUser(Context context,CoursePlan plan,String reason) {
        saveWaiting(context,plan,CoursePlan.Status.WAITING_USER,reason);
    }
    private static void saveWaiting(Context context,CoursePlan plan,CoursePlan.Status status,String reason) {
        PlanStore store=new PlanStore(context);
        boolean changed=plan.status!=status || !reason.equals(store.diagnostic(plan.id));
        CoursePlan waiting=plan.withStatus(status); store.put(waiting); store.diagnostic(plan.id,reason);
        if(changed) notifyWaiting(context,waiting);
    }
    static void expire(Context context,CoursePlan plan) {
        PlanScheduler.cancel(context,plan.id); new PlanStore(context).put(plan.withStatus(CoursePlan.Status.EXPIRED));
    }
    static void resumeAfterUnlock(Context context) {
        if(!unlocked(context)) return;
        for(CoursePlan plan:new PlanStore(context).all()) {
            if(plan.status!=CoursePlan.Status.WAITING_UNLOCK) continue;
            if(!BookingPreferences.waitForUnlock(context)) waitForUser(context,plan,"Nachholen beim Entsperren wurde ausgeschaltet. Jetzt anmelden startet den Plan.");
            else dispatch(context,plan.id,false);
        }
    }
    static void policyChanged(Context context) {
        BookingAccessibilityService.policyChanged();
        for(CoursePlan plan:new PlanStore(context).all()) {
            if(!pending(plan.status)) continue;
            if(plan.isExpired(Instant.now())) expire(context,plan);
            else if(plan.status==CoursePlan.Status.WAITING_UNLOCK && !BookingPreferences.waitForUnlock(context))
                waitForUser(context,plan,"Nachholen beim Entsperren wurde ausgeschaltet. Jetzt anmelden startet den Plan.");
        }
    }
    static void notifyWaiting(Context context,CoursePlan plan) {
        PlanScheduler.createChannel(context); if(!PlanScheduler.notificationsAllowed(context)) return;
        Intent open=new Intent(context,MainActivity.class).setData(Uri.parse("kursfenster://plan/"+plan.id));
        Intent start=new Intent(context,MainActivity.class).setAction(START_ACTION).setData(open.getData());
        PendingIntent content=PendingIntent.getActivity(context,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent action=PendingIntent.getActivity(context,0,start,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        boolean unlocking=plan.status==CoursePlan.Status.WAITING_UNLOCK;
        String message=unlocking
                ? BookingPreferences.automaticOpen(context) ? "Nach dem Entsperren öffnet sich VeniceBeach für die Anmeldung." : "Nach dem Entsperren kannst du die Anmeldung starten. Andere Apps bleiben ungestört."
                : "Tippe auf Jetzt anmelden, wenn VeniceBeach für die Buchung geöffnet werden darf.";
        Notification notification=new Notification.Builder(context,PlanScheduler.CHANNEL).setSmallIcon(R.drawable.ic_course)
                .setContentTitle((unlocking ? "Wartet aufs Entsperren: " : "Anmeldung bereit: ")+plan.course)
                .setContentText(message).setStyle(new Notification.BigTextStyle().bigText(plan.studio+" · "+message))
                .setContentIntent(content).setOnlyAlertOnce(true).setAutoCancel(true)
                .addAction(new Notification.Action.Builder(null,"Jetzt anmelden",action).build()).build();
        try { context.getSystemService(NotificationManager.class).notify(plan.id,1,notification); } catch(SecurityException ignored) { }
    }
}
