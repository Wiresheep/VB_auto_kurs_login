package de.wiresheep.kursplaner;

import android.app.*;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
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
    private static final int INK=Color.rgb(27,49,44), TEAL=Color.rgb(19,117,103), MUTED=Color.rgb(92,110,104);
    private static final int BACKGROUND=Color.rgb(243,246,244), LINE=Color.rgb(214,226,220), SOFT=Color.rgb(229,244,237);
    private LinearLayout body,list;
    private ScrollView scroll;
    private TextView message,dayLabel,accountStatus;
    private ProgressBar loading;
    private Button studioButton,dayButton,loadButton;
    private WidgetClient client=new WidgetClient();
    private WidgetClient.Studio selectedStudio=new WidgetClient.Studio(WidgetClient.GRUENSTADT,"VeniceBeach Grünstadt");
    private LocalDate day=LocalDate.now(CoursePlan.STUDIO_ZONE).plusDays(1);
    private boolean busy;
    private WebAccount account;
    private static final DateTimeFormatter DAY=DateTimeFormatter.ofPattern("EEE, dd. MMM yyyy",Locale.GERMAN);
    private static final DateTimeFormatter CLOCK=DateTimeFormatter.ofPattern("HH:mm",Locale.GERMAN).withZone(CoursePlan.STUDIO_ZONE);
    private static final DateTimeFormatter MOMENT=DateTimeFormatter.ofPattern("EEE, dd.MM.yyyy HH:mm",Locale.GERMAN).withZone(CoursePlan.STUDIO_ZONE);
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if(state!=null) day=LocalDate.parse(state.getString("day",day.toString()));
        android.content.SharedPreferences selection=getSharedPreferences("web_selection",MODE_PRIVATE);
        int studioId=state==null ? selection.getInt("studioId",selectedStudio.id) : state.getInt("studioId",selectedStudio.id);
        String studioName=state==null ? selection.getString("studioName",selectedStudio.name) : state.getString("studioName",selectedStudio.name);
        if(state==null && getIntent().hasExtra("studioId")) {
            studioId=getIntent().getIntExtra("studioId",studioId); studioName=getIntent().getStringExtra("studioName");
        }
        try { selectedStudio=new WidgetClient.Studio(studioId,studioName); }
        catch(IllegalArgumentException ignored) { /* Keep the initial default for invalid local preferences. */ }
        account=new WebAccount(this);
        scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(BACKGROUND);
        body=column(); body.setPadding(dp(22),dp(12),dp(22),dp(28));
        body.setOnApplyWindowInsetsListener((v,insets) -> {
            int top,bottom;
            if(Build.VERSION.SDK_INT>=30) { android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars()); top=bars.top; bottom=bars.bottom; }
            else { top=insets.getSystemWindowInsetTop(); bottom=insets.getSystemWindowInsetBottom(); }
            v.setPadding(dp(22),dp(12)+top,dp(22),dp(28)+bottom); return insets;
        });
        scroll.addView(body); setContentView(scroll);
        Button back=button(body,"Zurück zu deinen Plänen",this::finish);
        back.setBackgroundColor(Color.TRANSPARENT); back.setGravity(Gravity.START|Gravity.CENTER_VERTICAL); back.setPadding(0,0,0,0);
        TextView eyebrow=label(body,"KURSFENSTER  /  WEBBUCHUNG",11); eyebrow.setLetterSpacing(.12f); eyebrow.setTextColor(TEAL);
        TextView title=label(body,"Dein nächster Kurs.",30); title.setTypeface(null,Typeface.BOLD);
        label(body,"Studio und Tag wählen.\nDein Training vorausplanen.",16).setTextColor(MUTED);
        TextView badge=label(body,"Anmeldung 24 h vorher · auch bei Displaysperre",12);
        badge.setTextColor(TEAL); badge.setBackground(background(SOFT,10,0)); badge.setPadding(dp(12),dp(10),dp(12),dp(10));
        LinearLayout selectionCard=card(body);
        sectionLabel(selectionCard,"STUDIO");
        studioButton=button(selectionCard,selectedStudio.name,this::chooseStudio);
        studioButton.setGravity(Gravity.START|Gravity.CENTER_VERTICAL); studioButton.setTextColor(INK); studioButton.setTextSize(17);
        studioButton.setCompoundDrawablesRelativeWithIntrinsicBounds(0,0,R.drawable.ic_expand_more,0);
        studioButton.setCompoundDrawablePadding(dp(8));
        sectionLabel(selectionCard,"DEIN TAG");
        dayLabel=label(selectionCard,DAY.format(day),19); dayLabel.setTypeface(null,Typeface.BOLD);
        dayButton=button(selectionCard,"Tag auswählen",() -> new DatePickerDialog(this,(v,y,m,d) -> {
            if(!busy) { day=LocalDate.of(y,m+1,d); dayLabel.setText(DAY.format(day)); list.removeAllViews(); message.setText("Neuer Tag ausgewählt. Lade die passenden Kurse."); }
        },day.getYear(),day.getMonthValue()-1,day.getDayOfMonth()).show());
        loadButton=button(selectionCard,"Kurse laden",this::loadCourses); primary(loadButton);
        LinearLayout resultHeader=new LinearLayout(this); resultHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView coursesTitle=label(resultHeader,"Deine Kursauswahl",21); coursesTitle.setTypeface(null,Typeface.BOLD);
        coursesTitle.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));
        loading=new ProgressBar(this); loading.setIndeterminateTintList(ColorStateList.valueOf(TEAL)); loading.setVisibility(View.GONE);
        resultHeader.addView(loading,new LinearLayout.LayoutParams(dp(24),dp(24))); body.addView(resultHeader);
        message=label(body,"Lade die Kurse für dein Studio und deinen Wunschtag.",14); message.setTextColor(MUTED);
        message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        list=column(); body.addView(list);
        LinearLayout access=card(body);
        accountStatus=label(access,"",16); accountStatus.setTypeface(null,Typeface.BOLD); updateAccountStatus();
        label(access,"Dein Webzugang und die Android-Freigaben für automatische Anmeldungen.",14).setTextColor(MUTED);
        button(access,"Webzugang & Einstellungen",this::showWebSettings);
        label(body,"Deine Kurspläne bleiben auf diesem Gerät.",12).setTextColor(MUTED);
    }
    private void updateAccountStatus() {
        if(accountStatus!=null) {
            accountStatus.setText(account.configured()?"Webzugang eingerichtet":"Webzugang noch einrichten");
            accountStatus.setTextColor(account.configured()?TEAL:INK);
        }
    }
    private void showWebSettings() {
        ScrollView content=new ScrollView(this); LinearLayout settings=column(); settings.setPadding(dp(22),dp(12),dp(22),dp(16)); content.addView(settings);
        TextView title=label(settings,"Bereit für deine Anmeldung",21); title.setTypeface(null,Typeface.BOLD);
        label(settings,account.configured()?"Dein Webzugang ist auf diesem Gerät gespeichert.":"Richte deinen Webzugang ein, um Kurse automatisch anmelden zu lassen.",15).setTextColor(MUTED);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Webzugang & Einstellungen").setView(content).setPositiveButton("Fertig",null).create();
        button(settings,"Webzugang einrichten",() -> { dialog.dismiss(); loginDialog(); });
        sectionLabel(settings,"ANDROID-FREIGABEN");
        label(settings,PlanScheduler.exactAllowed(this)?"Zeitgenaue Alarme sind erlaubt.":"Erlaube zeitgenaue Alarme für den geplanten Buchungsstart.",14).setTextColor(MUTED);
        button(settings,"Zeitgenaue Alarme erlauben",() -> {
            if(Build.VERSION.SDK_INT>=31) startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:"+getPackageName())));
            else message.setText("Zeitgenaue Alarme sind verfügbar.");
        });
        button(settings,"Benachrichtigungen erlauben",() -> {
            if(Build.VERSION.SDK_INT>=33) requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},17);
            else startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()));
        });
        sectionLabel(settings,"ZUGANGSDATEN");
        label(settings,"Deine Zugangsdaten bleiben verschlüsselt auf diesem Smartphone.",14).setTextColor(MUTED);
        button(settings,"Webzugang auf diesem Gerät löschen",() -> {
            if(busy) { message.setText("Bitte den laufenden Vorgang abwarten."); return; }
            new AlertDialog.Builder(this).setTitle("Webzugang löschen?")
                .setMessage("Geplante Webbuchungen bleiben gespeichert, können bis zur erneuten Einrichtung aber nicht ausgeführt werden. Bereits gesendete Buchungen können noch abgeschlossen werden.")
                .setNegativeButton("Behalten",null).setPositiveButton("Löschen",(d,w) -> {
                    account.clear(); updateAccountStatus(); message.setText("Webzugang gelöscht."); dialog.dismiss();
                }).show();
        });
        dialog.show();
    }
    @Override protected void onResume() { super.onResume(); updateAccountStatus(); }
    private void setBusy(boolean value) {
        busy=value;
        if(loading!=null) loading.setVisibility(value?View.VISIBLE:View.GONE);
        if(loadButton!=null) { loadButton.setEnabled(!value); loadButton.setText(value?"Bitte warten …":"Kurse laden"); }
        if(studioButton!=null) studioButton.setEnabled(!value);
        if(dayButton!=null) dayButton.setEnabled(!value);
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString("day",day.toString()); out.putInt("studioId",selectedStudio.id); out.putString("studioName",selectedStudio.name);
        super.onSaveInstanceState(out);
    }
    private void chooseStudio() {
        if(busy) return;
        setBusy(true); message.setText("Studios werden geladen …");
        executor.execute(() -> {
            try {
                List<WidgetClient.Studio> studios=client.studios();
                handler.post(() -> {
                    setBusy(false); if(!alive()) return;
                    if(studios.isEmpty()) { message.setText("Keine Studios mit Webbuchung verfügbar."); return; }
                    String[] names=new String[studios.size()]; int checked=-1;
                    for(int i=0;i<studios.size();i++) {
                        names[i]=studios.get(i).name; if(studios.get(i).id==selectedStudio.id) checked=i;
                    }
                    message.setText("Studio auswählen. Deine bestehenden Pläne behalten ihr Studio.");
                    new AlertDialog.Builder(this).setTitle("Studio auswählen")
                        .setSingleChoiceItems(names,checked,(dialog,which) -> {
                            if(busy) return;
                            WidgetClient.Studio chosen=studios.get(which);
                            selectedStudio=chosen; studioButton.setText(chosen.name); list.removeAllViews();
                            getSharedPreferences("web_selection",MODE_PRIVATE).edit().putInt("studioId",chosen.id).putString("studioName",chosen.name).apply();
                            message.setText(chosen.name+" ausgewählt. Bitte Kurse laden."); dialog.dismiss();
                        }).setNegativeButton("Abbrechen",null).show();
                });
            } catch(Exception ex) {
                handler.post(() -> { setBusy(false); if(alive()) message.setText("Studios nicht erreichbar. Bitte Internet prüfen und die Studioauswahl erneut öffnen."); });
            }
        });
    }
    private boolean alive() { return !isFinishing() && !isDestroyed(); }
    private void loginDialog() {
        if(busy) return;
        LinearLayout fields=column(); fields.setPadding(dp(22),dp(12),dp(22),dp(12));
        label(fields,"Für automatische Buchungen werden E-Mail und Passwort mit Android Keystore verschlüsselt auf diesem Gerät gespeichert. Kursfenster meldet sich beim Buchungsstart erneut an. Verwende deine VeniceBeach-App-Zugangsdaten.",15);
        EditText email=new EditText(this); email.setHint("E-Mail"); email.setSingleLine(true); email.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS); email.setSaveEnabled(false); styleInput(email); fields.addView(email);
        EditText password=new EditText(this); password.setHint("Passwort"); password.setSingleLine(true); password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD); password.setSaveEnabled(false); styleInput(password); fields.addView(password);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Webzugang speichern").setView(fields).setNegativeButton("Abbrechen",null).setPositiveButton("Prüfen & speichern",null).create();
        dialog.setOnShowListener(d -> {
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String address=email.getText().toString().trim(),secret=password.getText().toString();
                if(address.isEmpty() || secret.isEmpty()) { password.setError("Bitte E-Mail und Passwort eingeben."); return; }
                password.setText(""); dialog.dismiss(); setBusy(true); message.setText("Webzugang wird geprüft …");
                executor.execute(() -> {
                    boolean ok;
                    try { new WidgetClient().login(address,secret); account.save(address,secret); ok=true; }
                    catch(Exception ex) { ok=false; }
                    boolean success=ok;
                    handler.post(() -> { setBusy(false); if(alive()) { updateAccountStatus(); message.setText(success?"Webzugang geprüft und verschlüsselt gespeichert.":"Webzugang konnte nicht geprüft oder gespeichert werden. Bitte Zugangsdaten, Mitgliedschaft und Internet prüfen."); } });
                });
            });
        });
        dialog.show();
    }
    private void loadCourses() {
        if(busy) return;
        LocalDate selected=day; WidgetClient.Studio studio=selectedStudio; setBusy(true); list.removeAllViews(); message.setText("Kurse werden geladen …");
        executor.execute(() -> {
            try {
                List<JSONObject> courses=client.courses(selected,studio.id);
                handler.post(() -> {
                    setBusy(false); if(!alive() || selectedStudio.id!=studio.id || !day.equals(selected)) return;
                    message.setText("Kurs zum Planen antippen. Das Laden bucht noch nichts."); int count=0;
                    try {
                        for(JSONObject course:courses) {
                            Instant start=Instant.ofEpochSecond(course.getLong("start_at"));
                            if(course.getInt("studioId")!=studio.id || !start.isAfter(Instant.now())
                                || !start.atZone(CoursePlan.STUDIO_ZONE).toLocalDate().equals(selected)
                                || !course.isNull("canceled_at") || course.getBoolean("private") || course.getBoolean("is_livestream") || !course.getBoolean("enableJoin")) continue;
                            if(course.getLong("join_open_prior_start_seconds")!=86400) continue;
                            WidgetClient.joinPath(course);
                            courseButton(course,start,studio); count++;
                        }
                        if(count==0) message.setText("Keine unterstützten zukünftigen Kurse an diesem Tag gefunden.");
                    } catch(Exception ex) { list.removeAllViews(); message.setText("Die Kursdaten konnten nicht sicher gelesen werden."); }
                });
            } catch(Exception ex) { handler.post(() -> { setBusy(false); if(alive()) message.setText("Kursplan nicht erreichbar. Bitte Internet prüfen und erneut laden."); }); }
        });
    }
    private void confirmPlan(JSONObject course,WidgetClient.Studio studio) {
        if(studio.id!=selectedStudio.id) { message.setText("Das Studio wurde geändert. Bitte Kurse neu laden."); return; }
        if(!account.configured()) { message.setText("Bitte zuerst den Webzugang einrichten."); return; }
        if(!PlanScheduler.exactAllowed(this)) { message.setText("Bitte zuerst zeitgenaue Alarme erlauben."); return; }
        try {
            if(course.getInt("studioId")!=studio.id) throw new IllegalArgumentException("Kurs gehört zu einem anderen Studio.");
            CoursePlan plan=new CoursePlan(UUID.randomUUID().toString(),course.getString("name"),studio.name,
                    Instant.ofEpochSecond(course.getLong("start_at")),CoursePlan.Status.PLANNED,true,course.getString("id"),course.getInt("studioId"));
            if(plan.isExpired(Instant.now())) { message.setText("Dieser Kurs hat bereits begonnen. Bitte neu laden."); return; }
            new AlertDialog.Builder(this).setTitle(plan.course).setMessage("Studio: "+plan.studio+"\nKursbeginn: "+MOMENT.format(plan.startsAt)
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
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout view=new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private GradientDrawable background(int color,int radius,int stroke) {
        GradientDrawable drawable=new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius));
        if(stroke!=0) drawable.setStroke(dp(1),stroke); return drawable;
    }
    private LinearLayout card(LinearLayout parent) {
        LinearLayout view=column(); view.setPadding(dp(18),dp(16),dp(18),dp(18)); view.setBackground(background(Color.WHITE,20,0));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2); params.setMargins(0,dp(16),0,dp(14)); parent.addView(view,params); return view;
    }
    private TextView label(LinearLayout parent,String text,int size) {
        TextView view=new TextView(this); view.setText(text); view.setTextSize(size); view.setTextColor(INK);
        view.setPadding(0,dp(5),0,dp(5)); view.setLineSpacing(dp(2),1); parent.addView(view); return view;
    }
    private void sectionLabel(LinearLayout parent,String value) {
        TextView view=label(parent,value,11); view.setTypeface(null,Typeface.BOLD); view.setTextColor(MUTED); view.setLetterSpacing(.1f);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2); params.topMargin=dp(6); view.setLayoutParams(params);
    }
    private Button button(LinearLayout parent,String text,Runnable action) {
        Button view=new Button(this); view.setText(text); view.setTextSize(14); view.setTextColor(TEAL); view.setAllCaps(false); view.setMinHeight(dp(50));
        view.setPadding(dp(14),dp(12),dp(14),dp(12)); view.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(30,19,117,103)),background(Color.WHITE,12,LINE),null));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2); params.topMargin=dp(8); parent.addView(view,params);
        view.setOnClickListener(v -> action.run()); return view;
    }
    private void primary(Button view) {
        view.setTextColor(Color.WHITE); view.setTypeface(null,Typeface.BOLD);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(40,255,255,255)),background(TEAL,12,0),null));
    }
    private void styleInput(EditText view) {
        view.setTextColor(INK); view.setTextSize(16); view.setMinHeight(dp(54)); view.setPadding(dp(14),dp(12),dp(14),dp(12));
        view.setBackground(background(BACKGROUND,12,LINE));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2); params.topMargin=dp(12); view.setLayoutParams(params);
    }
    private void courseButton(JSONObject course,Instant start,WidgetClient.Studio studio) throws Exception {
        String name=course.getString("name");
        String timing=CLOCK.format(start);
        if(!course.isNull("end_at")) timing+=" – "+CLOCK.format(Instant.ofEpochSecond(course.getLong("end_at")));
        String title=name+" · "+timing;
        String value=title+"\nKurs planen  →";
        SpannableString styled=new SpannableString(value);
        styled.setSpan(new StyleSpan(Typeface.BOLD),0,name.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        styled.setSpan(new RelativeSizeSpan(1.3f),0,name.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        styled.setSpan(new ForegroundColorSpan(MUTED),name.length(),title.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        styled.setSpan(new ForegroundColorSpan(TEAL),title.length()+1,value.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        Button view=button(list,value,() -> confirmPlan(course,studio)); view.setText(styled); view.setTextColor(INK);
        view.setGravity(Gravity.START|Gravity.CENTER_VERTICAL); view.setPadding(dp(18),dp(18),dp(18),dp(18));
        view.setLineSpacing(dp(9),1); view.setMinHeight(dp(100));
        view.setContentDescription(name+", "+timing+", "+studio.name+", Kurs planen");
    }
    @Override protected void onDestroy() { executor.shutdownNow(); super.onDestroy(); }
}
