package de.wiresheep.kursplaner;

import android.content.Context;
import org.json.*;
import java.time.Instant;
import java.util.*;

public final class PlanStore {
    static final Object LOCK = new Object();
    private final android.content.SharedPreferences preferences;
    public PlanStore(Context context) {
        preferences = context.getSharedPreferences("course_plans", Context.MODE_PRIVATE);
    }
    public List<CoursePlan> all() {
        List<CoursePlan> plans = new ArrayList<>();
        try {
            JSONArray data = new JSONArray(preferences.getString("plans", "[]"));
            for (int i = 0; i < data.length(); i++) {
                JSONObject o = data.getJSONObject(i);
                plans.add(new CoursePlan(o.getString("id"), o.getString("course"), o.getString("studio"),
                        Instant.ofEpochMilli(o.getLong("start")), CoursePlan.Status.valueOf(o.getString("status")), o.optBoolean("automatic",false), o.isNull("webOccurrenceId") ? null : o.getString("webOccurrenceId"), o.optInt("webStudioId",0)));
            }
        } catch (JSONException | IllegalArgumentException ex) {
            // Never overwrite data that could not be read.
            throw new IllegalStateException("Gespeicherte Kurse konnten nicht gelesen werden.", ex);
        }
        plans.sort(Comparator.comparing(p -> p.startsAt));
        return plans;
    }
    public CoursePlan find(String id) { return all().stream().filter(p -> p.id.equals(id)).findFirst().orElse(null); }
    public void put(CoursePlan plan) {
        synchronized(LOCK) {
            List<CoursePlan> plans = all();
            plans.removeIf(p -> p.id.equals(plan.id)); plans.add(plan); write(plans);
        }
    }
    public String diagnostic(String id) { return preferences.getString("diagnostic_"+id, ""); }
    public void diagnostic(String id, String text) {
        if (!text.equals(diagnostic(id))) preferences.edit().putString("diagnostic_"+id,text).apply();
    }
    public void remove(String id) {
        synchronized(LOCK) {
        List<CoursePlan> plans = all(); plans.removeIf(p -> p.id.equals(id)); write(plans);
        preferences.edit().remove("diagnostic_"+id).apply();
        }
    }
    private void write(List<CoursePlan> plans) {
        JSONArray data = new JSONArray();
        try {
            for (CoursePlan p : plans) {
                data.put(new JSONObject().put("id", p.id).put("course", p.course).put("studio", p.studio)
                        .put("start", p.startsAt.toEpochMilli()).put("status", p.status.name()).put("automatic",p.automatic).put("webOccurrenceId",p.webOccurrenceId == null ? JSONObject.NULL : p.webOccurrenceId).put("webStudioId",p.webStudioId));
            }
        } catch (JSONException ex) { throw new IllegalStateException(ex); }
        if (!preferences.edit().putString("plans", data.toString()).commit())
            throw new IllegalStateException("Der Kurs konnte nicht gespeichert werden.");
    }
}
