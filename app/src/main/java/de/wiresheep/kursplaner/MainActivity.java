package de.wiresheep.kursplaner;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class MainActivity extends Activity {
    private static final int INK=Color.rgb(27,49,44), TEAL=Color.rgb(19,117,103), MUTED=Color.rgb(92,110,104);
    private static final int BACKGROUND=Color.rgb(243,246,244), AMBER=Color.rgb(137,84,12);
    private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("EEE, dd. MMM",Locale.GERMAN);
    private static final DateTimeFormatter MOMENT=DateTimeFormatter.ofPattern("dd.MM.yyyy · HH:mm",Locale.GERMAN).withZone(CoursePlan.STUDIO_ZONE);
    private EditText course,studio;
    private CheckBox automatic;
    private Button dateButton,timeButton,saveButton;
    private TextView formTitle,formError,bookingHint;
    private LinearLayout plans,setup,form;
    private ScrollView scroll;
    private boolean dateReviewRequired;
    private String editingId,lastPlans="",lastSetup="";
    private LocalDate date=LocalDate.now(CoursePlan.STUDIO_ZONE).plusDays(1);
    private LocalTime time=LocalTime.of(18,0);
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable refresh=new Runnable() {
        @Override public void run() { renderPlans(); renderSetup(); handler.postDelayed(this,1500); }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if(state!=null) {
            date=LocalDate.parse(state.getString("date")); time=LocalTime.parse(state.getString("time"));
            dateReviewRequired=state.getBoolean("dateReviewRequired"); editingId=state.getString("editingId");
        }
        scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(BACKGROUND);
        LinearLayout root=column(); root.setPadding(dp(22),dp(20),dp(22),dp(32)); scroll.addView(root);
        root.setOnApplyWindowInsetsListener((v,insets) -> {
            int top,bottom;
            if(Build.VERSION.SDK_INT>=30) { android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()); top=bars.top; bottom=bars.bottom; }
            else { top=insets.getSystemWindowInsetTop(); bottom=insets.getSystemWindowInsetBottom(); }
            v.setPadding(dp(22),dp(20)+top,dp(22),dp(32)+bottom); return insets;
        });
        root.addView(text("Kursfenster",32,INK,true));
        root.addView(text("Deine nächsten Kurse im Blick.",16,MUTED,false));
        setup=card(root);
        LinearLayout actions=card(root);
        actions.addView(text("Deinen nächsten Kurs planen",21,INK,true));
        actions.addView(text("Übernimm den Kurs direkt aus VeniceBeach oder trage ihn selbst ein.",15,MUTED,false));
        primary(actions,"Kurs im Web auswählen (auch bei Displaysperre)",v -> startActivity(new Intent(this,WebBookingActivity.class)));
        primary(actions,"Kurs in VeniceBeach auswählen",v -> {
            if(!BookingAccessibilityService.pickCourse()) showError("Aktiviere den Buchungshelfer unter Einstellungen. Warte gegebenenfalls, bis eine laufende Anmeldung abgeschlossen ist.");
        });
        button(actions,"Kurs manuell eintragen",v -> { resetForm(); showForm(); });
        form=card(root); formTitle=text("Kurs planen",23,INK,true); form.addView(formTitle);
        formError=text("",14,AMBER,false); formError.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); formError.setVisibility(View.GONE); form.addView(formError);
        course=field(form,"Kursname",1002); studio=field(form,"Studio",1001);
        form.addView(text("Die Studioauswahl in VeniceBeach bleibt erhalten.",13,MUTED,false));
        form.addView(text("Datum und Beginn",14,INK,true));
        LinearLayout moments=new LinearLayout(this); moments.setOrientation(LinearLayout.HORIZONTAL); form.addView(moments);
        dateButton=action("",v -> pickDate()); timeButton=action("",v -> pickTime());
        LinearLayout.LayoutParams dateParams=new LinearLayout.LayoutParams(0,-2,1.7f); dateParams.setMargins(0,0,dp(8),0);
        moments.addView(dateButton,dateParams); moments.addView(timeButton,new LinearLayout.LayoutParams(0,-2,1));
        automatic=new CheckBox(this); automatic.setText(R.string.automatic_option); automatic.setTextColor(INK); automatic.setMinHeight(dp(48));
        automatic.setChecked(defaultAutomatic()); automatic.setOnCheckedChangeListener((b,checked) -> updateBookingHint()); form.addView(automatic);
        bookingHint=text("",14,MUTED,false); form.addView(bookingHint);
        saveButton=primary(form,"Plan speichern",v -> save());
        button(form,"Abbrechen",v -> { resetForm(); form.setVisibility(View.GONE); });
        form.setVisibility(View.GONE);
        root.addView(text("Deine Kurse",23,INK,true)); plans=column(); root.addView(plans);
        button(root,"Einstellungen & Hilfe",v -> showSettings());
        root.addView(text("Kursfenster "+BuildConfig.VERSION_NAME+" · Unabhängige App für VeniceBeach\nDeine Kurspläne bleiben auf diesem Gerät.",12,MUTED,false));
        setContentView(scroll);
        if(state!=null) {
            course.setText(state.getString("course","")); studio.setText(state.getString("studio",""));
            automatic.setChecked(state.getBoolean("automatic"));
            if(state.getBoolean("formVisible")) form.setVisibility(View.VISIBLE);
        } else studio.setText(getPreferences(MODE_PRIVATE).getString("lastStudio",""));
        formTitle.setText(editingId==null ? "Kurs planen" : "Kurs bearbeiten");
        saveButton.setText(editingId==null ? "Plan speichern" : "Änderungen speichern");
        updateDateLabels(); consumeCourseDraft(); consumeBookingRequest();
    }
    private boolean defaultAutomatic() { return BookingAccessibilityService.available() && getPreferences(MODE_PRIVATE).getBoolean("automaticDefault",true); }
    private void resetForm() {
        editingId=null; dateReviewRequired=false; date=LocalDate.now(CoursePlan.STUDIO_ZONE).plusDays(1); time=LocalTime.of(18,0);
        course.setText(""); studio.setText(getPreferences(MODE_PRIVATE).getString("lastStudio",""));
        course.setError(null); studio.setError(null); formError.setVisibility(View.GONE);
        automatic.setChecked(defaultAutomatic()); formTitle.setText(R.string.form_new_title); saveButton.setText(R.string.save_plan); updateDateLabels();
    }
    private void showForm() { form.setVisibility(View.VISIBLE); handler.post(() -> scroll.smoothScrollTo(0,form.getTop())); }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); consumeCourseDraft(); consumeBookingRequest(); }
    private void consumeBookingRequest() {
        Intent intent=getIntent(); if(intent==null || !BookingDispatcher.START_ACTION.equals(intent.getAction())) return;
        intent.setAction(null); String id=intent.getData()==null ? null : intent.getData().getLastPathSegment();
        if(id!=null) handler.post(() -> startNow(id));
    }
    private void startNow(String id) {
        CoursePlan pending=new PlanStore(this).find(id);
        if(pending==null || !pending.automatic || !BookingDispatcher.pending(pending.status)) { renderPlans(); return; }
        if(!pending.isWeb() && !BookingAccessibilityService.available()) {
            Toast.makeText(this,"Bitte den Buchungshelfer aktivieren und danach Jetzt anmelden wählen.",Toast.LENGTH_LONG).show(); showSettings(); return;
        }
        BookingDispatcher.dispatch(this,id,true); renderPlans();
    }
    private void consumeCourseDraft() {
        Intent intent=getIntent(); if(intent==null || !CourseDraft.ACTION.equals(intent.getAction())) return; intent.setAction(null);
        try {
            LocalTime importedTime=LocalTime.parse(intent.getStringExtra("draft_time"));
            String importedDate=intent.getStringExtra("draft_date"); LocalDate parsed=importedDate==null ? LocalDate.now(CoursePlan.STUDIO_ZONE) : LocalDate.parse(importedDate);
            resetForm(); course.setText(intent.getStringExtra("draft_course")); studio.setText(intent.getStringExtra("draft_studio"));
            time=importedTime; date=parsed; dateReviewRequired=importedDate==null;
            if(!CourseDraft.meaningfulName(course.getText().toString())) course.setError("Bitte den Kursnamen ergänzen.");
            if(!CourseDraft.meaningfulName(studio.getText().toString())) studio.setError("Bitte das Studio ergänzen.");
            updateDateLabels(); showForm();
            if(dateReviewRequired || course.getError()!=null || studio.getError()!=null) {
                formError.setText(R.string.import_review_hint); formError.setVisibility(View.VISIBLE);
            } else Toast.makeText(this,"Kurs übernommen. Angaben prüfen und speichern.",Toast.LENGTH_LONG).show();
        } catch(RuntimeException ex) { showError("Der Kurs konnte nicht übernommen werden. Du kannst ihn auch manuell eintragen."); }
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString("date",date.toString()); out.putString("time",time.toString()); out.putBoolean("dateReviewRequired",dateReviewRequired);
        out.putString("editingId",editingId); out.putBoolean("formVisible",form.getVisibility()==View.VISIBLE);
        out.putString("course",course.getText().toString()); out.putString("studio",studio.getText().toString()); out.putBoolean("automatic",automatic.isChecked());
        super.onSaveInstanceState(out);
    }
    @Override protected void onResume() {
        super.onResume();
        try { PlanScheduler.restore(this); renderSetup(); renderPlans(); handler.removeCallbacks(refresh); handler.postDelayed(refresh,1500); }
        catch(IllegalStateException ex) { showError(ex.getMessage()); }
    }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
    private void renderSetup() {
        boolean helper=BookingAccessibilityService.available(),exact=PlanScheduler.exactAllowed(this),notifications=PlanScheduler.notificationsAllowed(this);
        boolean web=new WebAccount(this).configured();
        String signature=web+":"+helper+":"+exact+":"+notifications+":"+BookingPreferences.waitForUnlock(this)+":"+BookingPreferences.automaticOpen(this); if(signature.equals(lastSetup)) return; lastSetup=signature;
        setup.removeAllViews();
        if(web) {
            setup.addView(text(exact ? "Webzugang eingerichtet" : "Zeitgenaue Alarme fehlen",17,exact ? TEAL : AMBER,true));
            setup.addView(text("Webpläne werden auch bei gesperrtem Display angemeldet. Ältere App-Pläne benötigen weiterhin den Buchungshelfer und ein entsperrtes Handy.",14,MUTED,false));
            if(!exact || !notifications) button(setup,"Webbuchung einrichten",v -> startActivity(new Intent(this,WebBookingActivity.class)));
            return;
        }
        setup.addView(text(helper && exact ? "Buchungshelfer aktiv" : "Einrichtung abschließen",17,helper && exact ? TEAL : AMBER,true));
        setup.addView(text(helper && exact ? (!BookingPreferences.automaticOpen(this) ? "Du startest bereitstehende Anmeldungen über Jetzt anmelden."
                : BookingPreferences.waitForUnlock(this) ? "Gesperrtes Handy? Die Anmeldung wartet auf das nächste Entsperren." : "Lass dein Handy zur Anmeldung eingeschaltet und entsperrt.")
                : "Richte den Buchungshelfer ein, damit deine Kurse automatisch angemeldet werden können.",14,MUTED,false));
        if(!helper || !exact) button(setup,"Einrichtung öffnen",v -> showSettings());
        else if(!notifications) button(setup,"Benachrichtigungen aktivieren",v -> requestNotifications());
    }
    private void showSettings() {
        ScrollView content=new ScrollView(this); LinearLayout body=column(); body.setPadding(dp(20),dp(8),dp(20),dp(20)); content.addView(body);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Einstellungen & Hilfe").setView(content).setPositiveButton("Fertig",null).create();
        body.addView(text("Automatische Webbuchung",18,INK,true));
        button(body,"Webzugang & Kurspläne",v -> { dialog.dismiss(); startActivity(new Intent(this,WebBookingActivity.class)); });
        body.addView(text("Für Webpläne ist weder Entsperren noch der Buchungshelfer erforderlich. Die folgenden Einstellungen gelten für die bisherige App-Bedienung.",14,MUTED,false));
        body.addView(text("Automatische Anmeldung per App-Bedienung",18,INK,true));
        body.addView(text("VeniceBeach muss angemeldet sein. Zum Buchen wird die App kurz auf dem entsperrten Bildschirm bedient.",14,MUTED,false));
        body.addView(text(BookingAccessibilityService.available() ? "Buchungshelfer ist aktiviert" : "Buchungshelfer ist noch nicht aktiviert",14,INK,true));
        button(body,"Buchungshelfer einrichten",v -> { dialog.dismiss(); startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); });
        if(!PlanScheduler.exactAllowed(this)) button(body,"Zeitgenaue Anmeldung erlauben",v -> {
            dialog.dismiss(); startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:"+getPackageName())));
        });
        if(!PlanScheduler.notificationsAllowed(this)) button(body,"Benachrichtigungen erlauben",v -> { dialog.dismiss(); requestNotifications(); });
        body.addView(text("Entsperren & App-Wechsel",18,INK,true));
        Switch unlock=new Switch(this); unlock.setText(R.string.wait_for_unlock); unlock.setMinHeight(dp(56));
        unlock.setChecked(BookingPreferences.waitForUnlock(this)); body.addView(unlock);
        body.addView(text("Ist das Handy beim Start gesperrt oder der Bildschirm aus, wartet der Plan bis zum Entsperren. Begonnene Kurse werden nicht mehr angemeldet.",14,MUTED,false));
        unlock.setOnCheckedChangeListener((view,checked) -> {
            BookingPreferences.waitForUnlock(this,checked); BookingDispatcher.policyChanged(this); renderPlans(); renderSetup(); updateBookingHint();
        });
        Switch opening=new Switch(this); opening.setText(R.string.automatic_open); opening.setMinHeight(dp(56));
        opening.setChecked(BookingPreferences.automaticOpen(this)); body.addView(opening);
        body.addView(text("Ein: VeniceBeach öffnet sich zur Anmeldung, auch nach dem Entsperren. Aus: Andere Apps bleiben ungestört; du startest über Jetzt anmelden. Unsichtbares Buchen im Hintergrund ist mit der Bildschirmbedienung nicht möglich.",14,MUTED,false));
        opening.setOnCheckedChangeListener((view,checked) -> {
            BookingPreferences.automaticOpen(this,checked); BookingDispatcher.policyChanged(this); renderPlans(); renderSetup(); updateBookingHint();
        });
        if(Build.VERSION.SDK_INT>=29) {
            body.addView(text("App-Start nach dem Entsperren",18,INK,true));
            body.addView(text(Settings.canDrawOverlays(this) ? "Die zusätzliche Android-Freigabe für den App-Start ist aktiv."
                    : "Falls VeniceBeach erst nach einem Tippen auf die Benachrichtigung startet, kannst du die zusätzliche Android-Freigabe erlauben. Android nennt sie Über anderen Apps einblenden. Sie erlaubt den App-Start aus dem Hintergrund. Dafür zeigt Kursfenster kein zusätzliches schwebendes Fenster an.",14,MUTED,false));
            if(!Settings.canDrawOverlays(this)) button(body,"App-Start nach Entsperren freigeben",v -> {
                dialog.dismiss();
                try { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName()))); }
                catch(ActivityNotFoundException ex) { showError("Bitte in den Android-Einstellungen unter Spezieller App-Zugriff → Über anderen Apps einblenden Kursfenster freigeben."); }
            });
        }
        body.addView(text("Kalenderleiste",18,INK,true));
        body.addView(text(CalendarCalibration.available(this) ? "Die Tagespositionen sind eingerichtet." : "Einrichten, falls der Tageswechsel nicht funktioniert.",14,MUTED,false));
        button(body,"Kalenderleiste einrichten",v -> {
            dialog.dismiss(); if(!BookingAccessibilityService.calibrate()) showError("Aktiviere zuerst den Buchungshelfer und warte eine laufende Anmeldung ab.");
        });
        button(body,"Kalendereinrichtung löschen",v -> { CalendarCalibration.clear(this); dialog.dismiss(); Toast.makeText(this,"Kalendereinrichtung gelöscht",Toast.LENGTH_SHORT).show(); });
        body.addView(text("So funktioniert es",18,INK,true));
        body.addView(text("Wähle deine Studios in VeniceBeach selbst aus. Die Anmeldung wird 24 Stunden vor Beginn bereitgestellt. Bereits offene Fenster starten nach dem Speichern, sofern das automatische Öffnen erlaubt ist.",14,MUTED,false));
        body.addView(text("Für die Tagesbestätigung darf der Buchungshelfer das Kalenderbild kurz im Arbeitsspeicher prüfen. Bilder werden weder gespeichert noch übertragen. Deine Pläne bleiben lokal.",14,MUTED,false));
        body.addView(text("Termine gelten in deutscher Studiozeit. Bei der doppelten Herbst-Uhrzeit gilt das erste Vorkommen. Entfernen eines Plans storniert keine Anmeldung in VeniceBeach.",13,MUTED,false));
        body.addView(text("Version "+BuildConfig.VERSION_NAME+" · Keine offizielle VeniceBeach-App",12,MUTED,false)); dialog.show();
    }
    private void requestNotifications() {
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED
                && !getPreferences(MODE_PRIVATE).getBoolean("notificationAsked",false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notificationAsked",true).apply(); requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},7);
        } else startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()));
    }
    @Override public void onRequestPermissionsResult(int code,String[] names,int[] grants) {
        super.onRequestPermissionsResult(code,names,grants); PlanScheduler.restore(this); lastSetup=""; renderSetup();
    }
    private void save() {
        course.setError(null); studio.setError(null); formError.setVisibility(View.GONE);
        boolean valid=true;
        if(!CourseDraft.meaningfulName(course.getText().toString())) { course.setError("Bitte einen Kursnamen eingeben."); valid=false; }
        if(!CourseDraft.meaningfulName(studio.getText().toString())) { studio.setError("Bitte ein Studio eingeben."); valid=false; }
        if(!valid) { formError.setText(R.string.missing_fields_hint); formError.setVisibility(View.VISIBLE); return; }
        try {
            if(dateReviewRequired) throw new IllegalArgumentException("Bitte das Datum des Kurses auswählen.");
            CoursePlan plan=CoursePlan.create(course.getText().toString(),studio.getText().toString(),date,time,Instant.now()).withAutomatic(automatic.isChecked());
            if(plan.automatic && !BookingAccessibilityService.available()) throw new IllegalArgumentException("Bitte den Buchungshelfer unter Einstellungen aktivieren oder die automatische Anmeldung abwählen.");
            PlanStore store=new PlanStore(this); String replacing=editingId;
            if(replacing!=null) {
                CoursePlan saved=store.find(replacing);
                if(saved==null || saved.isWeb() || !BookingDispatcher.pending(saved.status) || BookingAccessibilityService.running(replacing))
                    throw new IllegalArgumentException("Dieser Plan kann gerade nicht geändert werden. Bitte den aktuellen Status prüfen.");
                plan=new CoursePlan(replacing,plan.course,plan.studio,plan.startsAt,CoursePlan.Status.PLANNED,plan.automatic);
            }
            final CoursePlan candidate=plan;
            if(store.all().stream().anyMatch(p -> !p.id.equals(candidate.id) && p.startsAt.equals(candidate.startsAt)
                    && p.studio.equalsIgnoreCase(candidate.studio) && p.course.equalsIgnoreCase(candidate.course)))
                throw new IllegalArgumentException("Dieser Kurs ist bereits geplant.");
            store.put(plan); if(replacing!=null) PlanScheduler.cancel(this,replacing); PlanScheduler.schedule(this,plan);
            getPreferences(MODE_PRIVATE).edit().putString("lastStudio",plan.studio).putBoolean("automaticDefault",plan.automatic).apply();
            ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(course.getWindowToken(),0);
            resetForm(); form.setVisibility(View.GONE); renderPlans(); scroll.smoothScrollTo(0,0);
            Toast.makeText(this,"Kursplan gespeichert",Toast.LENGTH_SHORT).show();
        } catch(IllegalArgumentException | IllegalStateException ex) { formError.setText(ex.getMessage()); formError.setVisibility(View.VISIBLE); }
    }
    private void editPlan(CoursePlan plan) {
        resetForm(); editingId=plan.id;
        course.setText(CourseDraft.meaningfulName(plan.course) ? plan.course : "");
        studio.setText(CourseDraft.meaningfulName(plan.studio) ? plan.studio : "");
        var start=plan.startsAt.atZone(CoursePlan.STUDIO_ZONE); date=start.toLocalDate(); time=start.toLocalTime(); automatic.setChecked(plan.automatic);
        formTitle.setText(R.string.form_edit_title); saveButton.setText(R.string.save_changes); updateDateLabels(); showForm();
    }
    private String status(CoursePlan plan,Instant now) {
        if(plan.status==CoursePlan.Status.BOOKED) return "Angemeldet";
        if(plan.status==CoursePlan.Status.PLANNED && (!CourseDraft.meaningfulName(plan.course) || !CourseDraft.meaningfulName(plan.studio))) return "Angaben ergänzen";
        if(plan.isExpired(now)) return "Kurs hat begonnen";
        switch(plan.status) {
            case WAITING_UNLOCK: return "Wartet aufs Entsperren";
            case WAITING_USER: return "Bereit zur Anmeldung";
            case AUTOMATING: return "Anmeldung läuft …";
            case QUEUED: return "Wartet auf Anmeldung";
            case VERIFY_REQUIRED: return (BookingAccessibilityService.running(plan.id) || WebBookingService.running(plan.id)) ? "Bestätigung wird geprüft …" : "Anmeldung bitte prüfen";
            case ACTION_REQUIRED: return "Anmeldung bitte prüfen";
            case REMINDED: return "Erinnerung gesendet";
            default: if(plan.isWeb()) return plan.opensAt().isAfter(now) ? "Automatische Webanmeldung geplant" : "Webanmeldefenster geöffnet";
                return plan.opensAt().isAfter(now) ? (plan.automatic ? (BookingPreferences.automaticOpen(this) ? "Automatische Anmeldung geplant" : "Anmeldung vorgemerkt") : "Erinnerung geplant") : "Anmeldefenster geöffnet";
        }
    }
    private void renderPlans() {
        try {
            java.util.List<CoursePlan> items=new PlanStore(this).all(); Instant now=Instant.now(); StringBuilder signature=new StringBuilder();
            for(CoursePlan plan:items) signature.append(plan.id).append(plan.course).append(plan.studio).append(plan.startsAt).append(plan.automatic).append(status(plan,now));
            String current=items.isEmpty() ? "empty" : signature.toString(); if(current.equals(lastPlans)) return; lastPlans=current;
            plans.removeAllViews();
            if(items.isEmpty()) {
                LinearLayout empty=card(plans); empty.addView(text("Platz für deinen nächsten Kurs",19,INK,true));
                empty.addView(text("Wähle oben einen Kurs aus. Hier siehst du anschließend den Plan und den Anmeldestatus.",15,MUTED,false));
            }
            for(CoursePlan plan:items) {
                LinearLayout row=card(plans); boolean attention=plan.status==CoursePlan.Status.ACTION_REQUIRED || (plan.status==CoursePlan.Status.VERIFY_REQUIRED && !BookingAccessibilityService.running(plan.id));
                TextView badge=text(status(plan,now),12,attention ? AMBER : TEAL,true);
                badge.setPadding(dp(10),dp(6),dp(10),dp(6)); badge.setBackground(background(attention ? Color.rgb(255,243,218) : Color.rgb(229,244,237),12,0)); row.addView(badge);
                row.addView(text(CourseDraft.meaningfulName(plan.course) ? plan.course : "Kursname ergänzen",22,INK,true));
                row.addView(text(CourseDraft.meaningfulName(plan.studio) ? plan.studio : "Studio ergänzen",14,MUTED,false));
                row.addView(text(MOMENT.format(plan.startsAt),17,INK,true));
                if(plan.isWeb()) row.addView(text("Webbuchung · auch bei gesperrtem Display",13,TEAL,true));
                if(plan.status==CoursePlan.Status.PLANNED) row.addView(text(plan.isWeb() ? "Webanmeldung ab "+MOMENT.format(plan.opensAt()) : (plan.automatic ? (BookingPreferences.automaticOpen(this) ? "Automatische Anmeldung ab " : "Anmeldung bereit ab ") : "Erinnerung ab ")+MOMENT.format(plan.opensAt()),13,MUTED,false));
                if(attention) row.addView(text("Bitte den Anmeldestatus in VeniceBeach kontrollieren.",14,AMBER,false));
                if(plan.status==CoursePlan.Status.WAITING_UNLOCK || plan.status==CoursePlan.Status.WAITING_USER) {
                    row.addView(text(plan.status==CoursePlan.Status.WAITING_UNLOCK ? "Der Plan wartet auf ein entsperrtes Handy." : "Starte, wenn VeniceBeach für die Anmeldung geöffnet werden darf.",14,MUTED,false));
                    primary(row,"Jetzt anmelden",v -> startNow(plan.id));
                } else if(plan.status==CoursePlan.Status.PLANNED && (!CourseDraft.meaningfulName(plan.course) || !CourseDraft.meaningfulName(plan.studio)))
                    primary(row,"Angaben ergänzen",v -> editPlan(plan));
                else if(!plan.isExpired(now)) button(row,attention ? "Anmeldung prüfen" : "VeniceBeach öffnen",v -> openVenice());
                button(row,"Details",v -> showPlanDetails(plan.id));
            }
        } catch(IllegalStateException ex) { plans.removeAllViews(); plans.addView(text(ex.getMessage(),15,AMBER,false)); }
    }
    private void showPlanDetails(String id) {
        PlanStore store=new PlanStore(this); CoursePlan plan=store.find(id); if(plan==null) return;
        ScrollView content=new ScrollView(this); LinearLayout body=column(); body.setPadding(dp(20),dp(8),dp(20),dp(16)); content.addView(body);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(CourseDraft.meaningfulName(plan.course) ? plan.course : "Kursdetails").setView(content).setPositiveButton("Fertig",null).create();
        body.addView(text(status(plan,Instant.now()),16,TEAL,true));
        body.addView(text((CourseDraft.meaningfulName(plan.studio) ? plan.studio : "Studio noch nicht ergänzt")+"\nKursbeginn: "+MOMENT.format(plan.startsAt)+"\nAnmeldung ab: "+MOMENT.format(plan.opensAt()),15,INK,false));
        if(BookingDispatcher.pending(plan.status) && !BookingAccessibilityService.running(id) && !plan.isWeb()) button(body,"Angaben bearbeiten",v -> { dialog.dismiss(); editPlan(plan); });
        if(plan.status==CoursePlan.Status.WAITING_UNLOCK || plan.status==CoursePlan.Status.WAITING_USER)
            primary(body,"Jetzt anmelden",v -> { dialog.dismiss(); startNow(id); });
        if(plan.isWeb() && !WebBookingService.running(id)) {
            if(plan.status==CoursePlan.Status.ACTION_REQUIRED) button(body,"Webanmeldung erneut versuchen",v -> { dialog.dismiss(); WebBookingService.start(this,id); });
            if(plan.status==CoursePlan.Status.VERIFY_REQUIRED) button(body,"Teilnahme im Web prüfen",v -> { dialog.dismiss(); WebBookingService.start(this,id); });
            button(body,"Webkursplan öffnen",v -> startActivity(new Intent(this,WebBookingActivity.class).putExtra("studioId",plan.webStudioId).putExtra("studioName",plan.studio)));
        }
        button(body,"In VeniceBeach öffnen",v -> { dialog.dismiss(); openVenice(); });
        button(body,"Plan entfernen",v -> {
            dialog.dismiss(); new AlertDialog.Builder(this).setTitle("Plan entfernen?")
                    .setMessage("Eine bereits gebuchte Teilnahme in VeniceBeach bleibt bestehen. Eine laufende Buchung kann noch abgeschlossen werden.")
                    .setNegativeButton("Behalten",null).setPositiveButton("Entfernen",(d,w) -> { new PlanStore(this).remove(id); PlanScheduler.cancel(this,id); renderPlans(); }).show();
        });
        String diagnostic=store.diagnostic(id);
        if(!diagnostic.isEmpty()) {
            LinearLayout debug=column(); debug.setVisibility(View.GONE); body.addView(debug);
            debug.addView(text(diagnostic,13,MUTED,false));
            button(debug,"Diagnose kopieren",v -> {
                getSystemService(android.content.ClipboardManager.class).setPrimaryClip(android.content.ClipData.newPlainText("Kursfenster Diagnose",
                        "Kursfenster "+BuildConfig.VERSION_NAME+" · "+MOMENT.format(plan.startsAt)+"\n"+new PlanStore(this).diagnostic(id)));
                Toast.makeText(this,"Diagnose kopiert",Toast.LENGTH_SHORT).show();
            });
            button(body,"Technische Details anzeigen",v -> debug.setVisibility(View.VISIBLE));
        }
        dialog.show();
    }
    private void openVenice() { if(!VeniceBeach.open(this)) showError("VeniceBeach konnte nicht geöffnet werden. Bitte die App installieren und dort anmelden."); }
    private void pickDate() { new DatePickerDialog(this,(view,y,m,d) -> { date=LocalDate.of(y,m+1,d); dateReviewRequired=false; updateDateLabels(); },date.getYear(),date.getMonthValue()-1,date.getDayOfMonth()).show(); }
    private void pickTime() { new TimePickerDialog(this,(view,h,m) -> { time=LocalTime.of(h,m); updateDateLabels(); },time.getHour(),time.getMinute(),true).show(); }
    private void updateDateLabels() {
        dateButton.setText(dateReviewRequired ? getString(R.string.date_review_label,DATE.format(date)) : DATE.format(date));
        timeButton.setText(getString(R.string.time_label,DateTimeFormatter.ofPattern("HH:mm",Locale.GERMAN).format(time))); updateBookingHint();
    }
    private void updateBookingHint() {
        if(bookingHint==null) return;
        if(dateReviewRequired) { bookingHint.setText(R.string.choose_import_date_hint); return; }
        try {
            Instant opens=LocalDateTime.of(date,time).atZone(CoursePlan.STUDIO_ZONE).toInstant().minus(Duration.ofHours(24));
            bookingHint.setText(opens.isAfter(Instant.now()) ? (automatic.isChecked() ? (BookingPreferences.automaticOpen(this) ? "Automatische Anmeldung am " : "Anmeldung bereit am ") : "Erinnerung am ")+MOMENT.format(opens)
                    : automatic.isChecked() ? (BookingPreferences.automaticOpen(this) ? "Das Anmeldefenster ist offen. Die Anmeldung startet nach dem Speichern."
                            : "Das Anmeldefenster ist offen. Nach dem Speichern startest du über Jetzt anmelden.") : "Das Anmeldefenster ist offen. Du erhältst eine Erinnerung nach dem Speichern.");
        } catch(DateTimeException ex) { bookingHint.setText(R.string.check_date_time_hint); }
    }
    private void showError(String message) { new AlertDialog.Builder(this).setMessage(message).setPositiveButton("OK",null).show(); }
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout view=new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private GradientDrawable background(int color,int radius,int stroke) {
        GradientDrawable drawable=new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius)); if(stroke!=0) drawable.setStroke(dp(1),stroke); return drawable;
    }
    private LinearLayout card(LinearLayout parent) {
        LinearLayout view=column(); view.setPadding(dp(18),dp(16),dp(18),dp(16)); view.setBackground(background(Color.WHITE,20,0));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2); params.setMargins(0,dp(12),0,dp(12)); parent.addView(view,params); return view;
    }
    private TextView text(String value,int size,int color,boolean bold) {
        TextView view=new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); view.setPadding(0,dp(5),0,dp(5));
        if(bold) view.setTypeface(null,Typeface.BOLD); return view;
    }
    private EditText field(LinearLayout parent,String label,int id) {
        parent.addView(text(label,14,INK,true)); EditText view=new EditText(this); view.setId(id); view.setHint(label); view.setSingleLine(true);
        view.setTextColor(INK); view.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        view.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(120)}); view.setMinHeight(dp(52));
        parent.addView(view,new LinearLayout.LayoutParams(-1,-2)); return view;
    }
    private Button action(String label,View.OnClickListener listener) {
        Button view=new Button(this); view.setText(label); view.setAllCaps(false); view.setTextColor(TEAL); view.setTextSize(14); view.setMinHeight(dp(50));
        view.setPadding(dp(12),dp(10),dp(12),dp(10));
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(30,19,117,103)),background(Color.WHITE,12,Color.rgb(207,222,215)),background(Color.WHITE,12,0)));
        view.setOnClickListener(listener); return view;
    }
    private Button button(LinearLayout parent,String label,View.OnClickListener listener) {
        Button view=action(label,listener); LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2); params.topMargin=dp(8); parent.addView(view,params); return view;
    }
    private Button primary(LinearLayout parent,String label,View.OnClickListener listener) {
        Button view=button(parent,label,listener); view.setTextColor(Color.WHITE); view.setTypeface(null,Typeface.BOLD);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(45,255,255,255)),background(TEAL,12,0),background(Color.WHITE,12,0))); return view;
    }
}
