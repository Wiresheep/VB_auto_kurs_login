package de.wiresheep.kursplaner;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Bitmap;
import android.view.Display;
import android.graphics.Rect;
import android.graphics.PointF;
import android.graphics.PixelFormat;
import android.view.WindowManager;
import android.view.Gravity;
import android.widget.Toast;
import android.widget.Button;
import android.widget.LinearLayout;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.*;
import android.view.accessibility.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Experimental, deliberately conservative UI driver. No passwords, fixed screen coordinates or success guessing. */
public final class BookingAccessibilityService extends AccessibilityService {
    private static BookingAccessibilityService connected;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private CoursePlan active;
    private final ArrayDeque<String> queue = new ArrayDeque<>();
    private final Runnable timeout = () -> {
        if(active!=null) report("Zeitlimit erreicht. "+new PlanStore(this).diagnostic(active.id));
        finish(false);
    };
    private enum Stage { PREPARE_HOME, OPEN_COURSES, SELECT_DATE, VERIFY_DATE, SELECT_COURSE, SUBMIT, CONFIRM_BOOKING }
    private Stage stage;
    private boolean studioTabOpened, backFromDetailsSent;
    private boolean homeTabRequested;
    private int homeBacks;
    private long nextHomeActionAt,homeStartedAt;
    private boolean launchObserved;
    private static final long APP_START_TIMEOUT_MS=12000;
    private java.time.LocalDate verifiedDate;
    private long deadline,attemptGeneration;
    private long dateClickedAt;
    private boolean dateTapPending, dateGestureUsed;
    private String dateInputHint = "";
    private boolean calibratedDateTap;
    private boolean dayImagePending, dayImageChecked, dayImageConfirmed;
    private String dayImageFailure = "";
    private CalendarCalibrationView calibrationView;
    private boolean calibrationRequested;
    private boolean pickerRequested, pickerCapturePending;
    private LinearLayout pickerView;
    private java.time.LocalDate pickerDate;
    private long pickerDeadline;
    private final Runnable pickerPoll=this::pollPicker;
    private static final int MAX_LIST_SCROLLS = 30;
    private int listScrolls, unchangedChecks;
    private long nextListActionAt;
    private String previousViewport;
    private final Runnable step = this::advance;
    private boolean screenReceiverRegistered;
    private int unlockChecksRemaining;
    private final Runnable unlockCheck=new Runnable() {
        @Override public void run() {
            if(connected!=BookingAccessibilityService.this) return;
            if(deviceReady()) { BookingDispatcher.resumeAfterUnlock(BookingAccessibilityService.this); return; }
            if(--unlockChecksRemaining>0) handler.postDelayed(this,500);
        }
    };
    private final BroadcastReceiver screenReceiver=new BroadcastReceiver() {
        @Override public void onReceive(Context context,Intent intent) {
            if(Intent.ACTION_USER_PRESENT.equals(intent.getAction()) || Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                handler.removeCallbacks(unlockCheck); unlockChecksRemaining=10; handler.postDelayed(unlockCheck,500);
            } else if(Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                handler.removeCallbacks(unlockCheck);
                if(active!=null && BookingPreferences.waitForUnlock(BookingAccessibilityService.this)) pauseForUnlock();
            }
        }
    };
    public static boolean running(String id) {
        return connected != null && ((connected.active != null && connected.active.id.equals(id))
                || connected.queue.contains(id));
    }
    public static boolean available() { return connected != null; }
    public static boolean begin(Context context, CoursePlan plan) {
        if (connected == null || connected.calibrationRequested || connected.pickerRequested || !plan.automatic || plan.isExpired(java.time.Instant.now())
                || plan.opensAt().isAfter(java.time.Instant.now()) || !connected.deviceReady()) return false;
        if (running(plan.id)) return true;
        CoursePlan saved = new PlanStore(context).find(plan.id);
        if (saved == null || !BookingDispatcher.pending(saved.status)) return false;
        new PlanStore(context).put(saved.withStatus(CoursePlan.Status.QUEUED));
        connected.queue.addLast(plan.id);
        connected.startNext();
        return true;
    }
    private boolean deviceReady() {
        return !getSystemService(KeyguardManager.class).isKeyguardLocked()
                && getSystemService(PowerManager.class).isInteractive();
    }
    private void startNext() {
        if (active != null) return;
        PlanStore store = new PlanStore(this);
        while (!queue.isEmpty()) {
            CoursePlan plan = store.find(queue.removeFirst());
            if (plan == null || plan.status != CoursePlan.Status.QUEUED) continue;
            if(plan.isExpired(java.time.Instant.now())) { BookingDispatcher.expire(this,plan); continue; }
            if(!deviceReady() && BookingPreferences.waitForUnlock(this)) {
                BookingDispatcher.waitForUnlock(this,plan); continue;
            }
            if (!deviceReady()) {
                store.put(plan.withStatus(CoursePlan.Status.ACTION_REQUIRED));
                PlanScheduler.notifyOutcome(this, plan, false);
                continue;
            }
            attemptGeneration++;
            active = plan; stage = Stage.PREPARE_HOME; homeTabRequested=false; homeBacks=0; nextHomeActionAt=0; homeStartedAt=SystemClock.elapsedRealtime(); studioTabOpened = false; backFromDetailsSent = false; verifiedDate = null;
            launchObserved=false;
            report("VeniceBeach mit neuem App-Task starten. Startansicht vorbereiten.");
            dateClickedAt = 0; dateTapPending = false; dateGestureUsed = false; calibratedDateTap = false; dateInputHint = "";
            dayImagePending = false; dayImageChecked = false; dayImageConfirmed = false; dayImageFailure = "";
            listScrolls = 0; unchangedChecks = 0; nextListActionAt = 0; previousViewport = null;
            deadline = SystemClock.elapsedRealtime() + 60000;
            store.put(plan.withStatus(CoursePlan.Status.AUTOMATING));
            handler.postDelayed(timeout, 60000);
            if (!VeniceBeach.restart(this)) { report("VeniceBeach konnte nicht neu gestartet werden."); finish(false); return; }
            handler.postDelayed(step, 800);
            return;
        }
    }
    private void stopAll() {
        PlanStore store = new PlanStore(this);
        while (!queue.isEmpty()) {
            CoursePlan plan = store.find(queue.removeFirst());
            if (plan != null && plan.status == CoursePlan.Status.QUEUED) {
                store.put(plan.withStatus(CoursePlan.Status.ACTION_REQUIRED));
                PlanScheduler.notifyOutcome(this, plan, false);
            }
        }
        finish(false);
    }
    @Override public void onServiceConnected() {
        connected=this;
        if(!screenReceiverRegistered) {
            IntentFilter filter=new IntentFilter(Intent.ACTION_USER_PRESENT); filter.addAction(Intent.ACTION_SCREEN_OFF); filter.addAction(Intent.ACTION_SCREEN_ON);
            // These three actions are protected system broadcasts. USER_PRESENT can originate
            // from SystemUI's separate UID, which RECEIVER_NOT_EXPORTED excludes on modern Android.
            if(Build.VERSION.SDK_INT>=33) registerReceiver(screenReceiver,filter,Context.RECEIVER_EXPORTED);
            else registerReceiver(screenReceiver,filter);
            screenReceiverRegistered=true;
        }
        handler.post(() -> { if(connected==this) BookingDispatcher.resumeAfterUnlock(this); });
    }
    static void policyChanged() {
        if(connected==null || BookingPreferences.automaticOpen(connected)) return;
        PlanStore store=new PlanStore(connected);
        while(!connected.queue.isEmpty()) {
            CoursePlan queued=store.find(connected.queue.removeFirst());
            if(queued!=null && queued.status==CoursePlan.Status.QUEUED)
                BookingDispatcher.waitForUser(connected,queued,"Automatisches Öffnen wurde ausgeschaltet. Jetzt anmelden startet den Plan.");
        }
        if(connected.active!=null) {
            CoursePlan current=store.find(connected.active.id);
            if(current!=null && current.status==CoursePlan.Status.AUTOMATING) {
                BookingDispatcher.waitForUser(connected,current,"Automatisches Öffnen wurde ausgeschaltet. Jetzt anmelden startet den Plan.");
                connected.finish(false);
            }
        }
    }
    private void pauseForUnlock() {
        if(active==null) return;
        CoursePlan saved=new PlanStore(this).find(active.id);
        if(saved!=null && saved.status==CoursePlan.Status.AUTOMATING && !saved.isExpired(java.time.Instant.now()))
            BookingDispatcher.waitForUnlock(this,saved);
        finish(false);
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if(pickerRequested && event.getEventType()==AccessibilityEvent.TYPE_VIEW_CLICKED) {
            AccessibilityNodeInfo root=veniceRoot();
            if(root!=null && isCourseOverview(root)) rememberPickerDay(root,event.getSource());
        }
        // Continuous content/scroll events must not postpone the polling loop forever.
        if (active != null && event.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if(VeniceBeach.PACKAGE.contentEquals(event.getPackageName()==null ? "" : event.getPackageName())) launchObserved=true;
            handler.removeCallbacks(step); handler.postDelayed(step,100);
        }
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        if(calibrationRequested) {
            dismissCalibration();
            Toast.makeText(this,"Darstellung geändert. Bitte die Kalenderleiste erneut einrichten.",Toast.LENGTH_LONG).show();
        }
    }
    @Override public void onInterrupt() { handler.removeCallbacks(unlockCheck); dismissCalibration(); cancelPicker(); stopAll(); }
    @Override public void onDestroy() {
        handler.removeCallbacks(unlockCheck);
        if(screenReceiverRegistered) { unregisterReceiver(screenReceiver); screenReceiverRegistered=false; }
        dismissCalibration(); cancelPicker(); stopAll(); if (connected==this) connected=null; super.onDestroy(); }
    private void finish(boolean confirmed) {
        handler.removeCallbacks(step); handler.removeCallbacks(timeout);
        String completed=active==null ? null : active.id;
        if(active!=null) {
            PlanStore store=new PlanStore(this); CoursePlan saved=store.find(active.id);
            if(saved!=null && (saved.status==CoursePlan.Status.AUTOMATING || saved.status==CoursePlan.Status.VERIFY_REQUIRED)) {
                CoursePlan.Status status=confirmed ? CoursePlan.Status.BOOKED
                        : saved.status==CoursePlan.Status.VERIFY_REQUIRED ? CoursePlan.Status.VERIFY_REQUIRED : CoursePlan.Status.ACTION_REQUIRED;
                if(confirmed) report("Anmeldung bestätigt: Vom Kurs abmelden wird angezeigt.");
                CoursePlan updated=saved.withStatus(status); store.put(updated);
                PlanScheduler.notifyOutcome(this,updated,status!=CoursePlan.Status.ACTION_REQUIRED);
            }
        }
        active=null; startNext();
        if(confirmed && active==null) handler.postDelayed(() -> closeConfirmedBooking(getRootInActiveWindow(),completed),600);
    }
    private void closeConfirmedBooking(AccessibilityNodeInfo root,String id) {
        CoursePlan plan=new PlanStore(this).find(id);
        if(connected==this && active==null && queue.isEmpty() && !calibrationRequested && !pickerRequested && deviceReady()
                && plan!=null && plan.status==CoursePlan.Status.BOOKED && root!=null
                && VeniceBeach.PACKAGE.contentEquals(root.getPackageName()==null ? "" : root.getPackageName()))
            performGlobalAction(GLOBAL_ACTION_HOME);
    }
    private void advance() {
        if (active==null) return;
        CoursePlan saved=new PlanStore(this).find(active.id);
        if (saved==null || (saved.status!=CoursePlan.Status.AUTOMATING
                && !(stage==Stage.CONFIRM_BOOKING && saved.status==CoursePlan.Status.VERIFY_REQUIRED))) { finish(false); return; }
        if(!deviceReady() && BookingPreferences.waitForUnlock(this)) { pauseForUnlock(); return; }
        if (SystemClock.elapsedRealtime()>deadline || active.isExpired(java.time.Instant.now()) || !deviceReady()) { finish(false); return; }
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if (root==null || !VeniceBeach.PACKAGE.contentEquals(root.getPackageName()==null ? "" : root.getPackageName())) {
            // startActivity can return normally even when Android blocks the background launch.
            if(!launchObserved && SystemClock.elapsedRealtime()-homeStartedAt>=APP_START_TIMEOUT_MS) {
                BookingDispatcher.waitForUser(this,saved,VeniceBeach.backgroundStartHint(this));
                finish(false); return;
            }
            handler.postDelayed(step,800); return;
        }
        launchObserved=true;
        processScreen(root);
        if (active != null) {
            handler.removeCallbacks(step); handler.postDelayed(step,800);
        }
    }
    private void processScreen(AccessibilityNodeInfo root) {
        switch(stage) {
            case PREPARE_HOME:
                prepareHome(root);
                break;
            case OPEN_COURSES:
                report("Kurskalender öffnen.");
                // An already open studio page must not depend on clicking its selected tab again.
                if (isCourseOverview(root)) stage = Stage.SELECT_DATE;
                else if ((has(root,"Mein Studio") || has(root,"Kurse zum Mitmachen")) && clickUnique(root,"MEHR"))
                    stage = Stage.SELECT_DATE;
                else if (!backFromDetailsSent && (has(root,"Vom Kurs abmelden") || has(root,"Im Studio teilnehmen") || has(root,"Zum Kalender hinzufügen"))) {
                    backFromDetailsSent=performGlobalAction(GLOBAL_ACTION_BACK);
                } else if (!studioTabOpened && clickUnique(root,"Mein Studio")) studioTabOpened = true;
                break;
            case SELECT_DATE:
                if (!isCourseOverview(root)) {
                    report("Warte auf Kurskalender. Erkennbare Kalenderfelder: "+calendarSummary(root));
                } else if (clickDate(root)) {
                    dateClickedAt=SystemClock.elapsedRealtime();
                    report("Kalendertag "+format("dd.MM.yyyy")+" angetippt. "+dateInputHint+" Warte auf Kursliste.");
                    stage = Stage.VERIFY_DATE;
                } else {
                    report("Kalendertag "+format("dd.MM.yyyy")+" nicht eindeutig oder nicht klickbar. "+dateInputHint+" Felder: "+calendarSummary(root));
                }
                break;
            case VERIFY_DATE:
                if(dateTapPending) { report("Warte auf Abschluss des Antippens von "+format("EE dd")+"."); break; }
                AccessibilityNodeInfo day = dateTarget(root);
                // The user owns the studio filter; never require a particular studio in its label.
                boolean conflicting=conflictingDaySelected(root,day);
                boolean settled=SystemClock.elapsedRealtime()-dateClickedAt>=1200;
                if(conflicting && settled && !dateGestureUsed && ((day!=null && tapDateCell(day)) || tapCalibratedDate(root))) {
                    dateClickedAt=SystemClock.elapsedRealtime();
                    report("Ein anderer Tag ist noch markiert. Tippe den Zieltag direkt an.");
                    break;
                }
                boolean semanticConfirmed=day!=null && markedSelected(day,0) && !conflicting;
                if(isCourseOverview(root) && (semanticConfirmed || dayImageConfirmed)) {
                    verifiedDate=active.startsAt.atZone(CoursePlan.STUDIO_ZONE).toLocalDate();
                    stage=Stage.SELECT_COURSE;
                    report("Zieltag "+format("EE dd.MM.yyyy")+" bestätigt. Suche Kurs und Uhrzeit.");
                } else if(settled && !dayImagePending) {
                    if(dayImageChecked) {
                        if(dayImageFailure.isEmpty() && !dayImageConfirmed && !dateGestureUsed && ((day!=null && tapDateCell(day)) || tapCalibratedDate(root))) {
                            dateClickedAt=SystemClock.elapsedRealtime();
                            report("Tageswechsel noch nicht bestätigt. Tippe den Zieltag direkt an.");
                        } else {
                            report(dayImageFailure.isEmpty()
                                    ? "Zieltag nicht markiert. Keine Kurssuche gestartet; Tageswechsel fehlgeschlagen."
                                    : dayImageFailure+" Keine Kurssuche gestartet.");
                            finish(false);
                        }
                    } else requestDayImage(root,day);
                } else {
                    report("Warte auf Bestätigung von "+format("EE dd.MM.yyyy")
                            +" · Datumsfeld: "+(day!=null)+" · anderer Tag markiert: "+conflicting);
                }
                break;
            case SELECT_COURSE:
                searchCourseList(root);
                break;
            case SUBMIT:
                report("Kursdetails prüfen · Kurs: "+has(root,active.course)+" · Studio: "+has(root,active.studio)
                        +" · Datum/Wochentag: "+(hasDate(root)||hasWeekday(root))+" · Uhrzeit: "+hasDetailTime(root)
                        +" · Buchungsbutton: "+has(root,"Im Studio teilnehmen"));
                if(detailMatches(root)) {
                    if(has(root,"Vom Kurs abmelden")) { finish(true); return; }
                    if(clickUnique(root,"Im Studio teilnehmen")) {
                        new PlanStore(this).put(active.withStatus(CoursePlan.Status.VERIFY_REQUIRED));
                        stage=Stage.CONFIRM_BOOKING;
                        deadline=SystemClock.elapsedRealtime()+15000;
                        handler.removeCallbacks(timeout); handler.postDelayed(timeout,15000);
                        report("Buchungsklick gesendet. Warte auf Vom Kurs abmelden.");
                    }
                }
                break;
            case CONFIRM_BOOKING:
                if(detailMatches(root) && has(root,"Vom Kurs abmelden")) { finish(true); return; }
                report("Buchungsklick gesendet. Warte auf Bestätigung: Vom Kurs abmelden.");
                break;
        }
    }
    private static boolean hasMainNavigation(AccessibilityNodeInfo root) {
        return has(root,"Community") && has(root,"Mein Studio") && (has(root,"Training") || has(root,"Mein Profil"));
    }
    private void prepareHome(AccessibilityNodeInfo root) {
        if(root==null || !VeniceBeach.PACKAGE.contentEquals(root.getPackageName()==null ? "" : root.getPackageName())) return;
        long now=SystemClock.elapsedRealtime(); if(now<nextHomeActionAt) return;
        if(hasMainNavigation(root)) {
            if(!homeTabRequested) {
                if(clickUnique(root,"Community")) {
                    homeTabRequested=true; nextHomeActionAt=now+1000;
                    report("Hauptnavigation erkannt. Community-Startansicht öffnen.");
                } else report("Hauptnavigation erkannt, aber Community konnte nicht geöffnet werden.");
                return;
            }
            if(isCourseOverview(root) || has(root,"Im Studio teilnehmen") || has(root,"Vom Kurs abmelden")) {
                report("Warte darauf, dass die offene Unterseite verlassen wird."); return;
            }
            stage=Stage.OPEN_COURSES;
            report("Startansicht vorbereitet. Öffne Mein Studio und den Kurskalender.");
            return;
        }
        if(has(root,"Anmelden") && (has(root,"Passwort") || has(root,"Passwort vergessen") || has(root,"E-Mail"))) {
            report("VeniceBeach verlangt eine Anmeldung. Bitte zuerst selbst einloggen."); finish(false); return;
        }
        if(now-homeStartedAt<5000 || startupLoading(root)) {
            report("VeniceBeach startet. Warte auf die Hauptnavigation."); return;
        }
        if(homeBacks>=6) {
            report("Startansicht nach sechs Rückschritten nicht erkannt. Keine Kursbuchung gestartet."); finish(false); return;
        }
        if(performGlobalAction(GLOBAL_ACTION_BACK)) {
            homeBacks++; nextHomeActionAt=now+1000;
            report("Offene VeniceBeach-Unterseite verlassen. Rückschritt "+homeBacks+" von 6.");
        } else { report("Offene VeniceBeach-Unterseite konnte nicht verlassen werden."); finish(false); }
    }
    private static boolean startupLoading(AccessibilityNodeInfo root) {
        if(hasProgress(root,0)) return true;
        StringBuilder text=new StringBuilder(); appendVisibleText(root,text,0);
        String content=normalized(text.toString().replace('|',' '));
        return content.codePoints().noneMatch(Character::isLetter)
                || content.matches("(venicebeach( fitness)?|laden[. …]*|wird geladen[. …]*|bitte warten[. …]*|loading[. …]*)");
    }
    private static boolean hasProgress(AccessibilityNodeInfo node,int depth) {
        if(node==null || depth>35) return false;
        if(node.isVisibleToUser() && normalized(node.getClassName()).endsWith("progressbar")) return true;
        for(int i=0;i<node.getChildCount();i++) if(hasProgress(node.getChild(i),depth+1)) return true;
        return false;
    }
    private boolean detailMatches(AccessibilityNodeInfo root) {
        return verifiedDate!=null && verifiedDate.equals(active.startsAt.atZone(CoursePlan.STUDIO_ZONE).toLocalDate())
                && !isCourseOverview(root) && (hasDate(root) || hasWeekday(root))
                && has(root,active.course) && has(root,active.studio) && hasDetailTime(root);
    }
    private Rect dayBounds(AccessibilityNodeInfo root,AccessibilityNodeInfo day) {
        Rect bounds=new Rect();
        if(day!=null) day.getBoundsInScreen(bounds);
        if(!bounds.isEmpty()) return bounds;
        if(!isCourseOverview(root) || !calibratedDateTap) return null;
        java.time.LocalDate today=java.time.LocalDate.now(CoursePlan.STUDIO_ZONE);
        PointF current=CalendarCalibration.point(this,today,today);
        PointF next=CalendarCalibration.point(this,today,today.plusDays(1));
        PointF target=CalendarCalibration.point(this,today,active.startsAt.atZone(CoursePlan.STUDIO_ZONE).toLocalDate());
        if(current==null || next==null || target==null) return null;
        float radius=(next.x-current.x)*.4f;
        return new Rect(Math.round(target.x-radius),Math.round(target.y-radius),
                Math.round(target.x+radius),Math.round(target.y+radius));
    }
    private void requestDayImage(AccessibilityNodeInfo root,AccessibilityNodeInfo day) {
        Rect bounds=dayBounds(root,day);
        if(bounds==null || bounds.isEmpty()) {
            dayImageChecked=true; dayImageFailure="Position des Tagesreiters unbekannt. Bitte Kalenderleiste einrichten."; return;
        }
        if(Build.VERSION.SDK_INT<30) {
            dayImageChecked=true; dayImageFailure="Android meldet die Tagesauswahl nicht. Visuelle Prüfung benötigt Android 11 oder neuer."; return;
        }
        dayImagePending=true;
        final String planId=active.id;
        final long inputTime=dateClickedAt;
        final long imageAttempt=attemptGeneration;
        final android.util.DisplayMetrics before=CalendarCalibration.screen(this);
        report("Prüfe sichtbare Markierung von "+format("EE dd.MM.yyyy")+". Noch keine Kurssuche.");
        try { takeScreenshot(Display.DEFAULT_DISPLAY,getMainExecutor(),new TakeScreenshotCallback() {
            private boolean current() {
                return active!=null && active.id.equals(planId) && stage==Stage.VERIFY_DATE && dateClickedAt==inputTime && attemptGeneration==imageAttempt;
            }
            @Override public void onSuccess(ScreenshotResult result) {
                Bitmap hardware=null,image=null;
                try {
                    if(!current()) return;
                    dayImagePending=false;
                    AccessibilityNodeInfo fresh=getRootInActiveWindow();
                    if(fresh==null || !VeniceBeach.PACKAGE.contentEquals(fresh.getPackageName()==null ? "" : fresh.getPackageName())
                            || !isCourseOverview(fresh) || !deviceReady()) return;
                    var currentDisplay=CalendarCalibration.screen(BookingAccessibilityService.this);
                    if(before.widthPixels!=currentDisplay.widthPixels || before.heightPixels!=currentDisplay.heightPixels) {
                        dayImageChecked=true; dayImageFailure="Bildschirmdarstellung geändert. Bitte neu starten."; return;
                    }
                    hardware=Bitmap.wrapHardwareBuffer(result.getHardwareBuffer(),result.getColorSpace());
                    if(hardware==null) {
                        dayImageChecked=true; dayImageFailure="Kalenderbild konnte nicht gelesen werden."; return;
                    }
                    image=hardware.copy(Bitmap.Config.ARGB_8888,false);
                    applyDayImage(image,bounds);
                } catch(RuntimeException ex) {
                    if(current()) { dayImagePending=false; dayImageChecked=true; dayImageFailure="Kalenderbild konnte nicht geprüft werden."; }
                } finally {
                    if(image!=null) image.recycle();
                    if(hardware!=null) hardware.recycle();
                    result.getHardwareBuffer().close();
                }
            }
            @Override public void onFailure(int errorCode) {
                if(!current()) return;
                dayImagePending=false; dayImageChecked=true;
                dayImageFailure="Kalenderbild-Prüfung von Android abgelehnt ("+errorCode+"). Buchungsassistenz aus- und wieder einschalten.";
            }
        }); } catch(RuntimeException ex) {
            dayImagePending=false; dayImageChecked=true;
            dayImageFailure="Kalenderbild-Prüfung nicht verfügbar. Buchungsassistenz neu aktivieren.";
        }
        handler.postDelayed(() -> {
            if(active!=null && active.id.equals(planId) && stage==Stage.VERIFY_DATE
                    && dateClickedAt==inputTime && attemptGeneration==imageAttempt && dayImagePending) {
                dayImagePending=false; dayImageChecked=true;
                dayImageFailure="Keine Antwort auf die Kalenderbild-Prüfung erhalten.";
            }
        },3000);
    }
    private void applyDayImage(Bitmap image,Rect bounds) {
        var display=CalendarCalibration.screen(this);
        dayImageConfirmed=SelectedDayImage.selected(image,bounds,display.widthPixels,display.heightPixels);
        dayImageChecked=true;
    }
    private void searchCourseList(AccessibilityNodeInfo root) {
        long now=SystemClock.elapsedRealtime();
        if(now<nextListActionAt) return;
        // If the calendar remains visible, stop if its selected day was changed externally.
        AccessibilityNodeInfo day=dateTarget(root);
        if(day!=null && conflictingDaySelected(root,day)) { report("Kalendertag wurde gewechselt. Bitte neu starten."); finish(false); return; }
        Set<AccessibilityNodeInfo> matches=matchingCourseRows(root,active.course,format("HH:mm"));
        if(matches.size()>1) { report("Mehrere Kurse mit gleichem Namen und gleicher Uhrzeit sichtbar."); finish(false); return; }
        AccessibilityNodeInfo course=matches.isEmpty() ? null : matches.iterator().next();
        if(course!=null) {
            report("Passenden Kurs gefunden. Kursdetails öffnen.");
            if(course.performAction(AccessibilityNodeInfo.ACTION_CLICK)) stage=Stage.SUBMIT;
            return;
        }
        AccessibilityNodeInfo list=courseList(root);
        if(list==null) { report("Kurs nicht sichtbar; keine eindeutige scrollbar erkennbare Kursliste gefunden."); return; }
        StringBuilder visible=new StringBuilder();
        appendVisibleText(list,visible,0);
        String viewport=visible.toString();
        if(viewport.equals(previousViewport)) {
            // A successful scroll action does not guarantee that the contents have changed yet.
            if(++unchangedChecks>=3) { report("Kursliste bewegt sich nicht weiter. Kurs nicht gefunden."); finish(false); }
            else nextListActionAt=now+500;
            return;
        }
        unchangedChecks=0;
        if(listScrolls>=MAX_LIST_SCROLLS) { report("Suchgrenze nach 30 Scrollschritten erreicht."); finish(false); return; }
        previousViewport=viewport;
        int action=list.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN)
                ? AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId()
                : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
        if(!list.performAction(action)) { report("Listenende erreicht oder Scrollbefehl abgelehnt. Kurs nicht gefunden."); finish(false); return; }
        listScrolls++;
        report("Suche Kurs in der Liste · Scrollschritt "+listScrolls);
        nextListActionAt=now+1000; // Let scrolling and recycled course cards settle before clicking.
    }
    private static AccessibilityNodeInfo courseList(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> lists=new ArrayList<>();
        collectCourseLists(root,lists,0);
        return lists.size()==1 ? lists.get(0) : null;
    }
    private static void collectCourseLists(AccessibilityNodeInfo node,List<AccessibilityNodeInfo> lists,int depth) {
        if(node==null || depth>35) return;
        int before=lists.size();
        for(int i=0;i<node.getChildCount();i++) collectCourseLists(node.getChild(i),lists,depth+1);
        // Prefer an inner course list to an outer page scroll container.
        if(lists.size()!=before || !node.isVisibleToUser() || !node.isEnabled() || !node.isScrollable()) return;
        if(normalized(node.getClassName()).contains("horizontal")) return;
        AccessibilityNodeInfo.CollectionInfo info=node.getCollectionInfo();
        if(info!=null && info.getRowCount()==1 && info.getColumnCount()>1) return;
        if(hasCourseTime(node,0)) lists.add(node);
    }
    private static boolean hasCourseTime(AccessibilityNodeInfo node,int depth) {
        if(node==null || depth>35) return false;
        for(CharSequence label:new CharSequence[]{node.getText(),node.getContentDescription()}) {
            if(node.isVisibleToUser() && normalized(label).matches("\\d{2}:\\d{2}(\\s*[-–—]\\s*\\d{2}:\\d{2})?")) return true;
        }
        for(int i=0;i<node.getChildCount();i++) if(hasCourseTime(node.getChild(i),depth+1)) return true;
        return false;
    }
    private static void appendVisibleText(AccessibilityNodeInfo node,StringBuilder text,int depth) {
        if(node==null || depth>35) return;
        if(node.isVisibleToUser()) text.append(normalized(node.getText())).append('|')
                .append(normalized(node.getContentDescription())).append('\n');
        for(int i=0;i<node.getChildCount();i++) appendVisibleText(node.getChild(i),text,depth+1);
    }
    public static boolean pickCourse() {
        if(connected==null || connected.active!=null || connected.calibrationRequested || connected.pickerRequested || !connected.deviceReady()) return false;
        if(!VeniceBeach.open(connected)) return false;
        connected.pickerRequested=true; connected.pickerDate=null;
        connected.pickerDeadline=SystemClock.elapsedRealtime()+300000;
        connected.handler.postDelayed(connected.pickerPoll,800);
        return true;
    }
    private AccessibilityNodeInfo veniceRoot() {
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root!=null && VeniceBeach.PACKAGE.contentEquals(root.getPackageName()==null ? "" : root.getPackageName())) return root;
        for(AccessibilityWindowInfo window:getWindows()) {
            AccessibilityNodeInfo candidate=window.getRoot();
            if(window.isFocused() && candidate!=null && VeniceBeach.PACKAGE.contentEquals(candidate.getPackageName()==null ? "" : candidate.getPackageName())) return candidate;
        }
        return null;
    }
    private void pollPicker() {
        if(!pickerRequested) return;
        if(!deviceReady() || SystemClock.elapsedRealtime()>pickerDeadline) { cancelPicker(); return; }
        AccessibilityNodeInfo root=veniceRoot();
        if(root!=null && !pickerCapturePending) {
            if(pickerView==null) showPicker();
            if(isCourseOverview(root)) rememberPickerDay(root,null);
        }
        handler.postDelayed(pickerPoll,800);
    }
    private void rememberPickerDay(AccessibilityNodeInfo root,AccessibilityNodeInfo clicked) {
        java.time.LocalDate today=java.time.LocalDate.now(CoursePlan.STUDIO_ZONE),selected=null;
        boolean dateFieldClicked=false;
        if(clicked!=null) {
            StringBuilder text=new StringBuilder(); appendVisibleText(clicked,text,0);
            String label=normalized(text.toString().replace('|',' '));
            dateFieldClicked=label.matches("(mo|di|mi|do|fr|sa|so)\\.?([ ,]*\\d{1,2})?") || label.matches("\\d{1,2}");
            if(dateFieldClicked) pickerDate=null;
        }
        for(int i=0;i<7;i++) {
            java.time.LocalDate date=today.plusDays(i);
            if(clicked!=null) {
                AccessibilityNodeInfo node=clicked;
                for(int level=0;node!=null && level<3 && node!=root && !node.isScrollable();level++,node=node.getParent()) {
                    boolean[] parts={false,false};
                    String week=DateTimeFormatter.ofPattern("EE",Locale.GERMAN).format(date).replace(".","");
                    if(dayCellLabels(node,week,date.getDayOfMonth(),parts,0) && parts[0] && parts[1]) { pickerDate=date; return; }
                }
            }
            AccessibilityNodeInfo day=shortDay(root,date);
            if(day!=null && markedSelected(day,0)) {
                if(selected!=null) { pickerDate=null; return; }
                selected=date;
            }
        }
        if(!dateFieldClicked && selected!=null) pickerDate=selected;
    }
    private void showPicker() {
        pickerView=new LinearLayout(this); pickerView.setOrientation(LinearLayout.HORIZONTAL);
        Button capture=new Button(this); capture.setText(R.string.picker_capture); capture.setAllCaps(false);
        capture.setOnClickListener(v -> capturePickedCourse()); pickerView.addView(capture);
        Button cancel=new Button(this); cancel.setText(R.string.cancel); cancel.setAllCaps(false);
        cancel.setOnClickListener(v -> cancelPicker()); pickerView.addView(cancel);
        WindowManager.LayoutParams params=new WindowManager.LayoutParams(-2,-2,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity=Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.y=Math.round(90*getResources().getDisplayMetrics().density);
        try { getSystemService(WindowManager.class).addView(pickerView,params); }
        catch(RuntimeException ex) { cancelPicker(); Toast.makeText(this,"Kursauswahl konnte nicht eingeblendet werden.",Toast.LENGTH_LONG).show(); }
    }
    private void removePickerView() {
        if(pickerView!=null) {
            try { getSystemService(WindowManager.class).removeView(pickerView); } catch(IllegalArgumentException ignored) { }
            pickerView=null;
        }
    }
    private void capturePickedCourse() {
        if(!pickerRequested || pickerCapturePending) return;
        pickerCapturePending=true; removePickerView();
        handler.postDelayed(() -> {
            if(!pickerRequested) return;
            try {
                CourseDraft draft=CourseDraft.read(veniceRoot(),pickerDate);
                cancelPicker(); startActivity(draft.intent(this));
            } catch(IllegalArgumentException ex) {
                pickerCapturePending=false;
                Toast.makeText(this,ex.getMessage(),Toast.LENGTH_LONG).show();
                if(pickerRequested) showPicker();
            }
        },200);
    }
    private void cancelPicker() {
        pickerRequested=false; pickerCapturePending=false; handler.removeCallbacks(pickerPoll); removePickerView();
    }
    public static boolean calibrate() {
        if(connected==null || connected.active!=null || connected.calibrationRequested || connected.pickerRequested || !connected.deviceReady()) return false;
        if(!VeniceBeach.open(connected)) return false;
        connected.calibrationRequested=true;
        connected.prepareCalibration(0);
        return true;
    }
    private void prepareCalibration(int tries) {
        handler.postDelayed(() -> {
            if(!calibrationRequested) return;
            if(!deviceReady() || tries>=15) {
                dismissCalibration();
                Toast.makeText(this,"Bitte den Kurskalender in VeniceBeach öffnen und die Einrichtung erneut starten.",Toast.LENGTH_LONG).show();
                return;
            }
            AccessibilityNodeInfo root=getRootInActiveWindow();
            if(root!=null && VeniceBeach.PACKAGE.contentEquals(root.getPackageName()==null ? "" : root.getPackageName())) {
                if(isCourseOverview(root)) { showCalibration(); return; }
                if(has(root,"Mein Studio") || has(root,"Kurse zum Mitmachen")) {
                    if(!clickUnique(root,"MEHR")) clickUnique(root,"Mein Studio");
                }
            }
            prepareCalibration(tries+1);
        },500);
    }
    private void showCalibration() {
        calibrationView=new CalendarCalibrationView(this,new CalendarCalibrationView.Listener() {
            public void finished(PointF today,PointF tomorrow) {
                try {
                    CalendarCalibration.save(BookingAccessibilityService.this,today,tomorrow);
                    Toast.makeText(BookingAccessibilityService.this,"Kalenderleiste eingerichtet. Zurück zu Kursfenster, um einen Plan zu starten.",Toast.LENGTH_LONG).show();
                } catch(IllegalArgumentException | IllegalStateException ex) {
                    Toast.makeText(BookingAccessibilityService.this,ex.getMessage(),Toast.LENGTH_LONG).show();
                }
                dismissCalibration();
            }
            public void cancelled() { dismissCalibration(); }
        });
        WindowManager.LayoutParams params=new WindowManager.LayoutParams(-1,-1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity=Gravity.TOP | Gravity.START;
        try { getSystemService(WindowManager.class).addView(calibrationView,params); }
        catch(RuntimeException ex) {
            dismissCalibration(); Toast.makeText(this,"Die Kalendereinrichtung konnte nicht angezeigt werden.",Toast.LENGTH_LONG).show();
        }
    }
    private void dismissCalibration() {
        if(calibrationView!=null) {
            try { getSystemService(WindowManager.class).removeView(calibrationView); }
            catch(IllegalArgumentException ignored) { }
            calibrationView=null;
        }
        calibrationRequested=false;
    }
    private void report(String text) {
        if(active!=null) new PlanStore(this).diagnostic(active.id,text);
    }
    private static boolean isCourseOverview(AccessibilityNodeInfo root) {
        if(root==null) return false;
        for(CharSequence label:new CharSequence[]{root.getText(),root.getContentDescription()}) {
            String value=normalized(label);
            if(root.isVisibleToUser() && (value.matches("ausgewähltes studio\\s*:.*")
                    || value.matches("ausgewählte studios\\s*:.*")
                    || value.equals("ausgewähltes studio") || value.equals("ausgewählte studios"))) return true;
        }
        for(int i=0;i<root.getChildCount();i++) if(isCourseOverview(root.getChild(i))) return true;
        return false;
    }
    private static String calendarSummary(AccessibilityNodeInfo root) {
        List<String> labels=new ArrayList<>(); calendarLabels(root,labels,0);
        return labels.isEmpty() ? "keine lesbaren Datumsfelder" : String.join("; ",labels);
    }
    private static void calendarLabels(AccessibilityNodeInfo node,List<String> labels,int depth) {
        if(node==null || depth>35 || labels.size()>=10) return;
        for(CharSequence label:new CharSequence[]{node.getText(),node.getContentDescription()}) {
            String value=normalized(label);
            if(node.isVisibleToUser() && !labels.contains(value) && (value.matches("\\d{1,2}")
                    || value.matches("(mo|di|mi|do|fr|sa|so)\\.?([ ,]+\\d{1,2})?")
                    || value.matches("\\d{1,2}\\.\\d{1,2}\\.\\d{4}"))) labels.add(value);
        }
        for(int i=0;i<node.getChildCount();i++) calendarLabels(node.getChild(i),labels,depth+1);
    }
    private boolean conflictingDaySelected(AccessibilityNodeInfo root,AccessibilityNodeInfo target) {
        if(markedSelected(target,0)) return false;
        // Inspect only date cells, not selected studio filters or course cards.
        java.time.LocalDate today=java.time.LocalDate.now(CoursePlan.STUDIO_ZONE);
        for(int i=0;i<7;i++) {
            AccessibilityNodeInfo other=shortDay(root,today.plusDays(i));
            if(!today.plusDays(i).equals(active.startsAt.atZone(CoursePlan.STUDIO_ZONE).toLocalDate())
                    && other!=null && !other.equals(target) && markedSelected(other,0)) return true;
        }
        return false;
    }
    private boolean clickDate(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo target=dateTarget(root);
        dateInputHint="";
        if(target!=null && markedSelected(target,0)) { dateInputHint="Zieltag bereits markiert."; return true; }
        if(CalendarCalibration.available(this) && tapCalibratedDate(root)) return true;
        if(target==null) return tapCalibratedDate(root);
        if(!target.isEnabled()) { dateInputHint="Tagesreiter ist deaktiviert."; return false; }
        AccessibilityNodeInfo button=target.isClickable() ? target : null;
        if(button!=null && button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            dateInputHint="Android-Klick angenommen."; return true;
        }
        return tapDateCell(target) || tapCalibratedDate(root);
    }
    private boolean tapCalibratedDate(AccessibilityNodeInfo root) {
        if(active==null || !isCourseOverview(root)) return false;
        java.time.LocalDate today=java.time.LocalDate.now(CoursePlan.STUDIO_ZONE);
        PointF point=CalendarCalibration.point(this,today,active.startsAt.atZone(CoursePlan.STUDIO_ZONE).toLocalDate());
        if(point==null) { dateInputHint+=" Kalenderleiste in Kursfenster einrichten."; return false; }
        if(dispatchDateTap(point)) {
            calibratedDateTap=true;
            dateInputHint="Antippen der von dir eingerichteten Position für "+format("EE dd")+".";
            return true;
        }
        return false;
    }
    private boolean tapDateCell(AccessibilityNodeInfo target) {
        if(dateGestureUsed || dateTapPending || active==null || !target.isVisibleToUser() || !target.isEnabled()) return false;
        Rect bounds=new Rect(); target.getBoundsInScreen(bounds);
        android.util.DisplayMetrics display=CalendarCalibration.screen(this);
        if(bounds.isEmpty() || bounds.left<0 || bounds.top<0 || bounds.right>display.widthPixels
                || bounds.bottom>display.heightPixels || bounds.width()>display.widthPixels/2
                || bounds.height()>display.heightPixels/3) {
            dateInputHint="Keine eindeutige sichtbare Position des Tagesreiters."; return false;
        }
        return dispatchDateTap(new PointF(bounds.exactCenterX(),bounds.exactCenterY()));
    }
    private boolean dispatchDateTap(PointF point) {
        if(dateGestureUsed || dateTapPending || active==null || !deviceReady()) return false;
        Path path=new Path(); path.moveTo(point.x,point.y);
        GestureDescription gesture=new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path,0,80)).build();
        final String planId=active.id;
        final long gestureAttempt=attemptGeneration;
        dateTapPending=true; dateGestureUsed=true;
        dayImageChecked=false; dayImageConfirmed=false; dayImageFailure="";
        boolean accepted=dispatchGesture(gesture,new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription description) {
                if(active==null || !active.id.equals(planId) || attemptGeneration!=gestureAttempt) return;
                dateTapPending=false; dateClickedAt=SystemClock.elapsedRealtime();
                dayImagePending=false; dayImageChecked=false; dayImageConfirmed=false; dayImageFailure="";
            }
            @Override public void onCancelled(GestureDescription description) {
                if(active==null || !active.id.equals(planId) || attemptGeneration!=gestureAttempt) return;
                dateTapPending=false; report("Antippen des Tagesreiters wurde von Android abgebrochen."); finish(false);
            }
        },handler);
        if(!accepted) { dateTapPending=false; dateInputHint="Android hat das direkte Antippen abgelehnt. Bedienungshilfe neu aktivieren."; }
        else dateInputHint="Direktes Antippen des erkannten Tagesreiters.";
        return accepted;
    }
    private AccessibilityNodeInfo dateTarget(AccessibilityNodeInfo root) {
        String[] dates={format("dd.MM.yyyy"),format("d.M.yyyy"),format("EEEE, d. MMMM yyyy"),format("d. MMMM yyyy")};
        for(String date:dates) {
            Set<AccessibilityNodeInfo> targets=new HashSet<>();
            for(AccessibilityNodeInfo label:find(root,date)) {
                AccessibilityNodeInfo target=clickable(label);
                if(target!=null) targets.add(target);
            }
            if(targets.size()==1) return targets.iterator().next();
        }
        // Short dates are accepted only in a contiguous window anchored on today.
        java.time.LocalDate today = java.time.LocalDate.now(CoursePlan.STUDIO_ZONE);
        java.time.LocalDate target = active.startsAt.atZone(CoursePlan.STUDIO_ZONE).toLocalDate();
        if (target.isBefore(today) || target.isAfter(today.plusDays(1))) return null;
        if(shortDay(root,today)==null) return null;
        // Do not require a third, unrelated day to be present and clickable.
        return shortDay(root,target);
    }
    private static boolean markedSelected(AccessibilityNodeInfo node,int depth) {
        if(node==null || depth>3 || !node.isVisibleToUser()) return false;
        if(node.isSelected() || (node.isCheckable() && node.isChecked())) return true;
        for(int i=0;i<node.getChildCount();i++) if(markedSelected(node.getChild(i),depth+1)) return true;
        return false;
    }
    private boolean hasWeekday(AccessibilityNodeInfo root) {
        return has(root,format("EEEE")) || hasCombinedDayTime(root);
    }
    private boolean hasDetailTime(AccessibilityNodeInfo root) {
        return hasStartTime(root,format("HH:mm")) || hasCombinedDayTime(root);
    }
    private boolean hasCombinedDayTime(AccessibilityNodeInfo root) {
        if(root==null) return false;
        String prefix=normalized(format("EEEE"))+" ";
        for(CharSequence label:new CharSequence[]{root.getText(),root.getContentDescription()}) {
            String text=normalized(label);
            if(root.isVisibleToUser() && text.startsWith(prefix)
                    && startTimeMatches(text.substring(prefix.length()),format("HH:mm"))) return true;
        }
        for(int i=0;i<root.getChildCount();i++) if(hasCombinedDayTime(root.getChild(i))) return true;
        return false;
    }
    private static AccessibilityNodeInfo shortDay(AccessibilityNodeInfo root, java.time.LocalDate date) {
        String weekday = DateTimeFormatter.ofPattern("EE",Locale.GERMAN).format(date).replace(".","");
        Set<AccessibilityNodeInfo> targets = new HashSet<>();
        collectShortDays(root, weekday, date.getDayOfMonth(), targets, 0);
        return targets.size()==1 ? targets.iterator().next() : null;
    }
    private static void collectShortDays(AccessibilityNodeInfo node, String weekday, int day,
                                          Set<AccessibilityNodeInfo> targets, int depth) {
        if(node==null || depth>35) return;
        int before=targets.size();
        for(int i=0;i<node.getChildCount();i++) collectShortDays(node.getChild(i),weekday,day,targets,depth+1);
        if(targets.size()!=before || !node.isVisibleToUser() || node.isScrollable()) return;
        // Keep the smallest cell containing this weekday AND number. A whole calendar strip
        // must never become a target just because it contains the two labels somewhere.
        boolean[] parts={false,false};
        if(dayCellLabels(node,weekday,day,parts,0) && parts[0] && parts[1]) targets.add(node);
    }
    private static boolean dayCellLabels(AccessibilityNodeInfo node,String weekday,int day,boolean[] parts,int depth) {
        if(node==null || depth>6) return false;
        String padded=String.format(Locale.GERMAN,"%02d",day);
        String week=normalized(weekday);
        for(CharSequence label:new CharSequence[]{node.getText(),node.getContentDescription()}) {
            String value=normalized(label).replaceAll("[.,]"," ").trim().replaceAll("\\s+"," ");
            if(!node.isVisibleToUser() || value.isEmpty()) continue;
            if(value.equals(week)) parts[0]=true;
            else if(value.equals(""+day) || value.equals(padded)) parts[1]=true;
            else if(value.matches(java.util.regex.Pattern.quote(week)+"\\s*0?"+day)) { parts[0]=true; parts[1]=true; }
            else return false;
        }
        for(int i=0;i<node.getChildCount();i++) if(!dayCellLabels(node.getChild(i),weekday,day,parts,depth+1)) return false;
        return true;
    }
    private static boolean hasStartTime(AccessibilityNodeInfo root,String time) {
        if(root==null) return false;
        if(root.isVisibleToUser() && (startTimeMatches(root.getText(),time) || startTimeMatches(root.getContentDescription(),time))) return true;
        for(int i=0;i<root.getChildCount();i++) if(hasStartTime(root.getChild(i),time)) return true;
        return false;
    }
    private static boolean startTimeMatches(CharSequence label,String time) {
        String value=normalized(label);
        return value.equals(time) || value.matches(java.util.regex.Pattern.quote(time)+"\\s*[-–—]\\s*\\d{2}:\\d{2}");
    }
    private String format(String pattern) {
        return DateTimeFormatter.ofPattern(pattern,Locale.GERMAN).withZone(CoursePlan.STUDIO_ZONE).format(active.startsAt);
    }
    private boolean hasDate(AccessibilityNodeInfo root) {
        return has(root,format("dd.MM.yyyy")) || has(root,format("d.M.yyyy"))
                || has(root,format("EEEE, d. MMMM yyyy")) || has(root,format("d. MMMM yyyy"));
    }
    private static String normalized(CharSequence text) {
        return text==null ? "" : text.toString().replace('\u00a0',' ').replace('\u202f',' ').trim().replaceAll("\\s+"," ").toLowerCase(Locale.GERMAN);
    }
    private static boolean matches(AccessibilityNodeInfo n,String text) {
        return normalized(n.getText()).equals(normalized(text)) || normalized(n.getContentDescription()).equals(normalized(text));
    }
    private static List<AccessibilityNodeInfo> find(AccessibilityNodeInfo root,String text) {
        List<AccessibilityNodeInfo> found=new ArrayList<>(); collect(root,text,found,0); return found;
    }
    private static void collect(AccessibilityNodeInfo node,String text,List<AccessibilityNodeInfo> found,int depth) {
        if(node==null || depth>35) return;
        if(node.isVisibleToUser() && matches(node,text)) found.add(node);
        for(int i=0;i<node.getChildCount();i++) collect(node.getChild(i),text,found,depth+1);
    }
    private static boolean has(AccessibilityNodeInfo root,String text) { return !find(root,text).isEmpty(); }
    private static AccessibilityNodeInfo clickable(AccessibilityNodeInfo node) {
        for(int i=0;node!=null && i<5;i++,node=node.getParent()) {
            if(node.isScrollable()) return null;
            if(node.isClickable() && node.isEnabled() && node.isVisibleToUser()) return node;
        }
        return null;
    }
    private static boolean clickUnique(AccessibilityNodeInfo root,String text) {
        Set<AccessibilityNodeInfo> targets = new HashSet<>();
        for (AccessibilityNodeInfo node : find(root,text)) {
            AccessibilityNodeInfo target=clickable(node);
            if (target!=null) targets.add(target);
        }
        return targets.size()==1 && targets.iterator().next().performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }
    private static AccessibilityNodeInfo findCourseRow(AccessibilityNodeInfo root,String name,String time) {
        Set<AccessibilityNodeInfo> rows=matchingCourseRows(root,name,time);
        return rows.size()==1 ? rows.iterator().next() : null;
    }
    private static Set<AccessibilityNodeInfo> matchingCourseRows(AccessibilityNodeInfo root,String name,String time) {
        List<AccessibilityNodeInfo> titles=find(root,name);
        Set<AccessibilityNodeInfo> rows=new LinkedHashSet<>();
        for(AccessibilityNodeInfo title:titles) {
            AccessibilityNodeInfo row=title;
            for(int i=0;row!=null && i<4;i++,row=row.getParent()) {
                if(row.isScrollable()) break;
                if(hasStartTime(row,time) && find(row,name).size()==1 && row.isClickable() && row.isEnabled()) {
                    rows.add(row); break;
                }
            }
        }
        return rows;
    }
}
