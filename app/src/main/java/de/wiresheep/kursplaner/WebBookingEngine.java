package de.wiresheep.kursplaner;

import android.content.Context;
import org.json.JSONObject;
import java.io.IOException;
import java.time.*;
import java.util.List;
import java.util.function.BooleanSupplier;

/** A durable VERIFY_REQUIRED marker is committed before any booking POST. */
final class WebBookingEngine {
    interface Gateway {
        void login(String email,String password) throws Exception;
        List<JSONObject> courses(LocalDate day,int studio) throws Exception;
        void join(JSONObject occurrence) throws Exception;
    }
    static JSONObject find(CoursePlan plan,List<JSONObject> courses) throws Exception {
        JSONObject match=null;
        for(JSONObject course:courses) if(plan.webOccurrenceId.equals(course.getString("id"))) {
            if(match!=null) throw new IOException("Doppelte Kurskennung.");
            if(course.getInt("studioId")!=plan.webStudioId || course.getLong("start_at")!=plan.startsAt.getEpochSecond()
                    || !plan.course.equals(course.getString("name"))) throw new IOException("Kursdaten wurden geändert.");
            match=course;
        }
        if(match==null) throw new IOException("Kurs nicht mehr im Kursplan.");
        return match;
    }
    static void eligible(JSONObject course,Instant now) throws Exception {
        if(!course.isNull("canceled_at") || course.getBoolean("private") || course.getBoolean("is_livestream")
                || !course.getBoolean("enableJoin")) throw new IOException("Kurs ist nicht buchbar.");
        if(course.getLong("join_open_prior_start_seconds")!=86400) throw new IOException("Abweichendes Anmeldefenster.");
        Instant start=Instant.ofEpochSecond(course.getLong("start_at"));
        if(now.isBefore(start.minusSeconds(86400)) || !now.isBefore(start)) throw new IOException("Anmeldefenster nicht offen.");
        if(!course.isNull("attendees_count") && !course.isNull("max_participants")
                && course.getInt("attendees_count")>=course.getInt("max_participants")) throw new IOException("Kurs ist voll.");
        for(String period:new String[]{"day","week"}) {
            String count=period.equals("day")?"joins_today":"joins_weekly";
            if(!course.isNull("max_joins_per_"+period) && !course.isNull(count)
                    && course.getInt(count)>=course.getInt("max_joins_per_"+period)) throw new IOException("Buchungslimit erreicht.");
        }
        WidgetClient.joinPath(course);
    }
    static void run(Context context,String id,Gateway gateway,String[] credentials,BooleanSupplier accountCurrent) {
        PlanStore store=new PlanStore(context);
        CoursePlan plan=store.find(id);
        if(plan==null || !plan.isWeb()) return;
        boolean verifyOnly=plan.status==CoursePlan.Status.VERIFY_REQUIRED;
        if(!verifyOnly && plan.status!=CoursePlan.Status.PLANNED && plan.status!=CoursePlan.Status.ACTION_REQUIRED
                && plan.status!=CoursePlan.Status.WAITING_USER) return;
        if(!verifyOnly && plan.isExpired(Instant.now())) { update(context,plan,CoursePlan.Status.EXPIRED,"Kurs hat begonnen."); return; }
        if(!verifyOnly && plan.opensAt().isAfter(Instant.now())) {
            CoursePlan planned=plan.withStatus(CoursePlan.Status.PLANNED); store.put(planned); PlanScheduler.schedule(context,planned); return;
        }
        boolean submitted=verifyOnly;
        long deadline=android.os.SystemClock.elapsedRealtime()+90000;
        try {
            if(!verifyOnly) update(context,plan,CoursePlan.Status.AUTOMATING,"Webzugang wird geprüft. Noch keine Buchung gesendet.");
            gateway.login(credentials[0],credentials[1]);
            LocalDate day=plan.startsAt.atZone(CoursePlan.STUDIO_ZONE).toLocalDate();
            JSONObject occurrence=find(plan,gateway.courses(day,plan.webStudioId));
            if(occurrence.getBoolean("joined")) { update(context,plan,CoursePlan.Status.BOOKED,"Webzugang bestätigt die Teilnahme (joined=true)."); return; }
            if(verifyOnly) { update(context,plan,CoursePlan.Status.VERIFY_REQUIRED,"Teilnahme nicht bestätigt. Es wird keine zweite Buchung gesendet. Bitte im Webkursplan prüfen."); return; }
            eligible(occurrence,Instant.now());
            synchronized(PlanStore.LOCK) {
                CoursePlan current=store.find(id);
                if(current==null || current.status!=CoursePlan.Status.AUTOMATING || !accountCurrent.getAsBoolean()
                        || Thread.currentThread().isInterrupted() || android.os.SystemClock.elapsedRealtime()>deadline)
                    throw new IOException("Plan oder Webzugang wurde geändert.");
                store.put(plan.withStatus(CoursePlan.Status.VERIFY_REQUIRED));
                submitted=true;
                store.diagnostic(id,"Webbuchung wird gesendet. Bei unklarer Antwort ausschließlich Teilnahme prüfen; nicht erneut senden.");
            }
            // Even an exception can mean that the provider received the request. Never retry POST.
            try { gateway.join(occurrence); } catch(Exception ignored) { }
            JSONObject confirmed=find(plan,gateway.courses(day,plan.webStudioId));
            if(!confirmed.getBoolean("joined")) throw new IOException("Teilnahme nicht bestätigt.");
            update(context,plan,CoursePlan.Status.BOOKED,"Webzugang bestätigt die Teilnahme (joined=true).");
        } catch(Exception ex) {
            // No raw response, URL, password or token is copied into diagnostics.
            update(context,plan,submitted?CoursePlan.Status.VERIFY_REQUIRED:CoursePlan.Status.ACTION_REQUIRED,
                    submitted?"Webbuchung nicht bestätigt. Bitte Teilnahme prüfen. Keine automatische Wiederholung."
                    :"Webanmeldung angehalten, bevor eine Buchung gesendet wurde. Webzugang, Netz und Kursdaten prüfen.");
        }
    }
    private static void update(Context context,CoursePlan original,CoursePlan.Status status,String reason) {
        synchronized(PlanStore.LOCK) {
            PlanStore store=new PlanStore(context);
            if(store.find(original.id)==null) return;
            CoursePlan result=original.withStatus(status); store.put(result); store.diagnostic(result.id,reason);
            if(status==CoursePlan.Status.BOOKED || status==CoursePlan.Status.ACTION_REQUIRED || status==CoursePlan.Status.VERIFY_REQUIRED)
                PlanScheduler.notifyOutcome(context,result,status==CoursePlan.Status.VERIFY_REQUIRED);
        }
    }
}
