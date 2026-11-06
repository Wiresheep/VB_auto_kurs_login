package de.wiresheep.kursplaner;

import android.app.*;
import android.content.Intent;
import android.os.*;
import android.provider.Settings;
import android.net.Uri;
import android.view.WindowManager;
import android.widget.*;
import android.text.InputType;
import org.json.JSONObject;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/** Explicit web course selection; saving a plan is separate from reading the public schedule. */
public final class WebBookingActivity extends Activity {
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private LinearLayout body,list;
    private TextView message,dayLabel;
    private LocalDate day=LocalDate.now(CoursePlan.STUDIO_ZONE).plusDays(1);
    private boolean busy;
    private WebAccount account;
    private static final DateTimeFormatter MOMENT=DateTimeFormatter.ofPattern("EEE, dd.MM.yyyy HH:mm",Locale.GERMAN).withZone(CoursePlan.STUDIO_ZONE);
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if(state!=null) day=LocalDate.parse(state.getString("day",day.toString()));
        account=new WebAccount(this);
        ScrollView scroll=new ScrollView(this); body=new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(24,28,24,28);
        body.setOnApplyWindowInsetsListener((v,insets) -> {
            int top,bottom;
            if(Build.VERSION.SDK_INT>=30) { android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars()); top=bars.top; bottom=bars.bottom; }
            else { top=insets.getSystemWindowInsetTop(); bottom=insets.getSystemWindowInsetBottom(); }
            v.setPadding(24,28+top,24,28+bottom); return insets;
        });
        scroll.addView(body); setContentView(scroll);
        label(body,"Webbuchung · Grünstadt",24);
        label(body,"Die Anmeldung läuft auf diesem Smartphone, auch bei gesperrtem Display. Kursfenster benötigt dafür Internet und zeitgenaue Alarme.",16);
        label(body,"Neue Webintegration: Login und Buchung müssen zunächst auf deinem Gerät geprüft werden.",14);
        button(body,"Webzugang einrichten",this::loginDialog);
        button(body,"Webzugang auf diesem Gerät löschen",() -> {
            if(busy) { message.setText("Bitte den laufenden Vorgang abwarten."); return; }
            new AlertDialog.Builder(this).setMessage("Zugangsdaten löschen? Geplante Webbuchungen bleiben gespeichert, können bis zur erneuten Einrichtung aber nicht ausgeführt werden. Bereits gesendete Buchungen können noch abgeschlossen werden.")
                .setNegativeButton("Behalten",null).setPositiveButton("Löschen",(d,w) -> { account.clear(); message.setText("Webzugang gelöscht."); }).show();
        });
        button(body,"Zeitgenaue Alarme erlauben",() -> {
            if(Build.VERSION.SDK_INT>=31) startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:"+getPackageName())));
            else message.setText("Zeitgenaue Alarme sind verfügbar.");
        });
        button(body,"Benachrichtigungen erlauben",() -> {
            if(Build.VERSION.SDK_INT>=33) requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},17);
            else startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()));
        });
        label(body,"Kurs auswählen",21); dayLabel=label(body,day.toString(),18);
        button(body,"Tag auswählen",() -> new DatePickerDialog(this,(v,y,m,d) -> { if(!busy) { day=LocalDate.of(y,m+1,d); dayLabel.setText(day.toString()); list.removeAllViews(); } },day.getYear(),day.getMonthValue()-1,day.getDayOfMonth()).show());
        button(body,"Kurse laden",this::loadCourses);
        message=label(body,account.configured()?"Webzugang gespeichert.":"Bitte den Webzugang vor der automatischen Buchung einrichten.",15);
        list=new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); body.addView(list);
        button(body,"Zurück zu deinen Plänen",this::finish);
    }
    @Override protected void onSaveInstanceState(Bundle out) { out.putString("day",day.toString()); super.onSaveInstanceState(out); }
    private boolean alive() { return !isFinishing() && !isDestroyed(); }
    private void loginDialog() {
        if(busy) return;
        LinearLayout fields=new LinearLayout(this); fields.setOrientation(LinearLayout.VERTICAL); fields.setPadding(24,12,24,12);
        label(fields,"Für automatische Buchungen werden E-Mail und Passwort mit Android Keystore verschlüsselt auf diesem Gerät gespeichert. Kursfenster meldet sich beim Buchungsstart erneut an. Verwende deine VeniceBeach-App-Zugangsdaten.",15);
        EditText email=new EditText(this); email.setHint("E-Mail"); email.setSingleLine(true); email.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS); email.setSaveEnabled(false); fields.addView(email);
        EditText password=new EditText(this); password.setHint("Passwort"); password.setSingleLine(true); password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD); password.setSaveEnabled(false); fields.addView(password);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Webzugang speichern").setView(fields).setNegativeButton("Abbrechen",null).setPositiveButton("Prüfen & speichern",null).create();
        dialog.setOnShowListener(d -> {
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String address=email.getText().toString().trim(),secret=password.getText().toString();
                if(address.isEmpty() || secret.isEmpty()) { password.setError("Bitte E-Mail und Passwort eingeben."); return; }
                password.setText(""); dialog.dismiss(); busy=true; message.setText("Webzugang wird geprüft …");
                executor.execute(() -> {
                    boolean ok;
                    try { new WidgetClient().login(address,secret); account.save(address,secret); ok=true; }
                    catch(Exception ex) { ok=false; }
                    boolean success=ok;
                    handler.post(() -> { busy=false; if(alive()) message.setText(success?"Webzugang geprüft und verschlüsselt gespeichert.":"Webzugang konnte nicht geprüft oder gespeichert werden. Bitte Zugangsdaten, Mitgliedschaft und Internet prüfen."); });
                });
            });
        });
        dialog.show();
    }
    private void loadCourses() {
        if(busy) return;
        LocalDate selected=day; busy=true; list.removeAllViews(); message.setText("Kurse werden geladen …");
        executor.execute(() -> {
            try {
                List<JSONObject> courses=new WidgetClient().courses(selected,WidgetClient.GRUENSTADT);
                handler.post(() -> {
                    busy=false; if(!alive()) return;
                    message.setText("Kurs zum Planen antippen. Das Laden bucht noch nichts."); int count=0;
                    try {
                        for(JSONObject course:courses) {
                            Instant start=Instant.ofEpochSecond(course.getLong("start_at"));
                            if(course.getInt("studioId")!=WidgetClient.GRUENSTADT || !start.isAfter(Instant.now())
                                || !start.atZone(CoursePlan.STUDIO_ZONE).toLocalDate().equals(selected)
                                || !course.isNull("canceled_at") || course.getBoolean("private") || course.getBoolean("is_livestream") || !course.getBoolean("enableJoin")) continue;
                            if(course.getLong("join_open_prior_start_seconds")!=86400) continue;
                            WidgetClient.joinPath(course);
                            button(list,course.getString("name")+" · "+MOMENT.format(start),() -> confirmPlan(course)); count++;
                        }
                        if(count==0) message.setText("Keine unterstützten zukünftigen Kurse an diesem Tag gefunden.");
                    } catch(Exception ex) { list.removeAllViews(); message.setText("Die Kursdaten konnten nicht sicher gelesen werden."); }
                });
            } catch(Exception ex) { handler.post(() -> { busy=false; if(alive()) message.setText("Kursplan nicht erreichbar. Bitte Internet prüfen und erneut laden."); }); }
        });
    }
    private void confirmPlan(JSONObject course) {
        if(!account.configured()) { message.setText("Bitte zuerst den Webzugang einrichten."); return; }
        if(!PlanScheduler.exactAllowed(this)) { message.setText("Bitte zuerst zeitgenaue Alarme erlauben."); return; }
        try {
            CoursePlan plan=new CoursePlan(UUID.randomUUID().toString(),course.getString("name"),"VeniceBeach Grünstadt",
                    Instant.ofEpochSecond(course.getLong("start_at")),CoursePlan.Status.PLANNED,true,course.getString("id"),course.getInt("studioId"));
            if(plan.isExpired(Instant.now())) { message.setText("Dieser Kurs hat bereits begonnen. Bitte neu laden."); return; }
            new AlertDialog.Builder(this).setTitle(plan.course).setMessage("Kursbeginn: "+MOMENT.format(plan.startsAt)
                    +"\nAutomatische Webanmeldung: "+(plan.opensAt().isAfter(Instant.now())?MOMENT.format(plan.opensAt()):"sofort nach dem Speichern")
                    +"\n\nDie Buchung wird auch bei gesperrtem Display versucht. Eine Teilnahme gilt erst nach Bestätigung als gebucht.")
                .setNegativeButton("Abbrechen",null).setPositiveButton("Automatisch buchen lassen",(d,w) -> {
                    try { synchronized(PlanStore.LOCK) {
                        PlanStore store=new PlanStore(this);
                        if(store.all().stream().anyMatch(p -> plan.webOccurrenceId.equals(p.webOccurrenceId)
                            || (plan.startsAt.equals(p.startsAt) && plan.course.equals(p.course) && plan.studio.equals(p.studio)))) {
                            message.setText("Dieser Kurs ist bereits geplant."); return;
                        }
                        store.put(plan); PlanScheduler.schedule(this,plan); message.setText("Webbuchung geplant. Den Status findest du unter Deine Kurse.");
                    } } catch(IllegalStateException ex) { message.setText("Der Plan konnte nicht gespeichert werden."); }
                }).show();
        } catch(Exception ex) { message.setText("Der Kurs konnte nicht gespeichert werden. Bitte Kursdaten erneut laden."); }
    }
    private TextView label(LinearLayout parent,String text,int size) {
        TextView view=new TextView(this); view.setText(text); view.setTextSize(size); view.setPadding(0,12,0,12); parent.addView(view); return view;
    }
    private void button(LinearLayout parent,String text,Runnable action) { Button view=new Button(this); view.setText(text); view.setAllCaps(false); view.setOnClickListener(v -> action.run()); parent.addView(view); }
    @Override protected void onDestroy() { executor.shutdownNow(); super.onDestroy(); }
}
