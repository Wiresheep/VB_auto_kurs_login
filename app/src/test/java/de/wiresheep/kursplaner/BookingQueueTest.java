package de.wiresheep.kursplaner;

import android.app.*;
import android.content.*;
import android.content.pm.ResolveInfo;
import android.os.PowerManager;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import java.time.Instant;
import java.util.UUID;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class BookingQueueTest {
    private Application context;
    private PlanStore store;
    private ServiceController<BookingAccessibilityService> controller;
    private BookingAccessibilityService service;
    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("course_plans", 0).edit().clear().commit();
        store = new PlanStore(context);
        CalendarCalibration.clear(context);
        shadowOf(context.getSystemService(PowerManager.class)).setIsInteractive(true);
        shadowOf(context.getSystemService(KeyguardManager.class)).setKeyguardLocked(false);
        var pm = shadowOf(context.getPackageManager());
        var component = new ComponentName(VeniceBeach.PACKAGE, VeniceBeach.PACKAGE + ".MainActivity");
        var info = pm.addActivityIfNotPresent(component);
        ResolveInfo resolve = new ResolveInfo(); resolve.activityInfo = info;
        pm.addResolveInfoForIntent(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_INFO)
                .setPackage(VeniceBeach.PACKAGE), resolve);
        controller = Robolectric.buildService(BookingAccessibilityService.class).create();
        service = controller.get(); service.onServiceConnected();
    }
    @After public void cleanup() { controller.destroy(); }
    private CoursePlan plan(long seconds) {
        CoursePlan p = new CoursePlan(UUID.randomUUID().toString(), "Yoga", "Mannheim",
                Instant.now().plusSeconds(seconds), CoursePlan.Status.PLANNED, true);
        store.put(p); return p;
    }
    private void finish() {
        ReflectionHelpers.callInstanceMethod(service, "finish", ReflectionHelpers.ClassParameter.from(boolean.class, false));
    }
    @Test public void simultaneousCoursesAreSerializedAndRetainedDuringRestore() {
        CoursePlan first = plan(3600), second = plan(3600);
        assertTrue(BookingAccessibilityService.begin(context, first));
        assertEquals(CoursePlan.Status.AUTOMATING, store.find(first.id).status);
        assertTrue(BookingAccessibilityService.begin(context, second));
        assertEquals(CoursePlan.Status.QUEUED, store.find(second.id).status);
        PlanScheduler.restore(context);
        assertEquals(CoursePlan.Status.QUEUED, store.find(second.id).status);
        finish();
        assertEquals(CoursePlan.Status.ACTION_REQUIRED, store.find(first.id).status);
        assertEquals(CoursePlan.Status.AUTOMATING, store.find(second.id).status);
    }
    @Test public void duplicateDeliveryDoesNotQueueAnotherAttempt() {
        CoursePlan first = plan(3600);
        assertTrue(BookingAccessibilityService.begin(context, first));
        assertTrue(BookingAccessibilityService.begin(context, first));
        finish();
        assertFalse(BookingAccessibilityService.running(first.id));
        assertEquals(CoursePlan.Status.ACTION_REQUIRED, store.find(first.id).status);
    }
    @Test public void deletedQueuedPlanIsSkipped() {
        CoursePlan first = plan(3600), second = plan(3600);
        BookingAccessibilityService.begin(context, first); BookingAccessibilityService.begin(context, second);
        store.remove(second.id); finish();
        assertNull(store.find(second.id));
        assertFalse(BookingAccessibilityService.running(second.id));
    }
    @Test public void disconnectMarksActiveAndQueuedPlansForManualAction() {
        CoursePlan first = plan(3600), second = plan(3600);
        BookingAccessibilityService.begin(context, first); BookingAccessibilityService.begin(context, second);
        service.onInterrupt();
        assertEquals(CoursePlan.Status.ACTION_REQUIRED, store.find(first.id).status);
        assertEquals(CoursePlan.Status.ACTION_REQUIRED, store.find(second.id).status);
        assertFalse(BookingAccessibilityService.running(second.id));
    }
    @Test public void lockingWhileQueuedPreventsNextLaunch() {
        CoursePlan first = plan(3600), second = plan(3600);
        BookingAccessibilityService.begin(context, first); BookingAccessibilityService.begin(context, second);
        shadowOf(context.getSystemService(KeyguardManager.class)).setKeyguardLocked(true);
        finish();
        assertEquals(CoursePlan.Status.ACTION_REQUIRED, store.find(second.id).status);
        assertFalse(BookingAccessibilityService.running(second.id));
    }
    @Test public void earlyAndExpiredPlansCannotBeStarted() {
        CoursePlan early = plan(172800), expired = plan(-1);
        assertFalse(BookingAccessibilityService.begin(context, early));
        assertFalse(BookingAccessibilityService.begin(context, expired));
        assertEquals(CoursePlan.Status.PLANNED, store.find(early.id).status);
    }
    @Test public void persistedQueueFromDeadProcessRequiresManualAction() {
        CoursePlan p = plan(3600);
        store.put(p.withStatus(CoursePlan.Status.QUEUED)); PlanScheduler.restore(context);
        assertEquals(CoursePlan.Status.ACTION_REQUIRED, store.find(p.id).status);
    }
    private android.view.accessibility.AccessibilityNodeInfo node(String text, boolean clickable,
            android.view.accessibility.AccessibilityNodeInfo... children) {
        var n=android.view.accessibility.AccessibilityNodeInfo.obtain();
        n.setText(text); n.setVisibleToUser(true); n.setEnabled(true); n.setClickable(clickable);
        shadowOf(n).setOnPerformActionListener((action,args) -> true);
        for(var child:children) shadowOf(n).addChild(child);
        return n;
    }
    private void screen(android.view.accessibility.AccessibilityNodeInfo root) {
        // Calendar/booking fixtures begin after the real startup navigation fixture.
        if("PREPARE_HOME".equals(ReflectionHelpers.getField(service,"stage").toString())) {
            var home=homeRoot(); rawScreen(home);
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(1100)); rawScreen(home);
        }
        rawScreen(root);
    }
    private android.view.accessibility.AccessibilityNodeInfo homeRoot() {
        var root=node("",false,node("Community",true),node("Training",true),node("Mein Studio",true),node("Mein Profil",true));
        root.setPackageName(VeniceBeach.PACKAGE); return root;
    }
    private void rawScreen(android.view.accessibility.AccessibilityNodeInfo root) {
        ReflectionHelpers.callInstanceMethod(service,"processScreen",
                ReflectionHelpers.ClassParameter.from(android.view.accessibility.AccessibilityNodeInfo.class,root));
    }
    private int clicks(android.view.accessibility.AccessibilityNodeInfo n) { return shadowOf(n).getPerformedActions().size(); }
    @Test public void studioPageGoesDirectlyToMoreWithoutSwitchingStudioFirst() {
        BookingAccessibilityService.begin(context,plan(3600));
        var more=node("MEHR",true); var tab=node("Mein Studio",true); var oldSelector=node("Studio wechseln",true);
        screen(node("",false,tab,more,oldSelector));
        assertEquals(1,clicks(more)); assertEquals(0,clicks(tab)); assertEquals(0,clicks(oldSelector));
        // A stale studio page must not cause another click while waiting for the calendar.
        screen(node("",false,tab,more,oldSelector)); assertEquals(1,clicks(more));
    }
    @Test public void homeOpensStudioTabOnlyOnceThenClicksMore() {
        BookingAccessibilityService.begin(context,plan(3600));
        var tab=node("Mein Studio",true);
        screen(node("",false,tab)); screen(node("",false,tab)); assertEquals(1,clicks(tab));
        var more=node("MEHR",true); screen(node("",false,tab,more)); assertEquals(1,clicks(more));
    }
    @Test public void calendarNeverOpensOrChangesUserStudioSelection() {
        CoursePlan p=plan(3600); BookingAccessibilityService.begin(context,p);
        var selector=node("",true,node("Ausgewählte Studios:",false),node("Mehrere Studios",false));
        var day=node(format(p,"dd.MM.yyyy"),true); day.setSelected(true);
        var course=node("",true,node(p.course,false),node(format(p,"HH:mm"),false));
        var overview=node("",false,selector,day,course);
        screen(overview); screen(overview); screen(overview); screen(overview);
        assertEquals(0,clicks(selector)); assertEquals(0,clicks(day)); assertEquals(1,clicks(course));
        assertEquals("SUBMIT",ReflectionHelpers.getField(service,"stage").toString());
    }
    @Test public void currentStudioSkipsPickerAndScreenshotCalendarChoosesDueDate() {
        CoursePlan p=plan(3600); BookingAccessibilityService.begin(context,p);
        var selector=node("",true,node("Ausgewähltes Studio:",false),node("Mannheim",false));
        java.time.LocalDate today=java.time.LocalDate.now(CoursePlan.STUDIO_ZONE);
        var dateNodes=new java.util.ArrayList<android.view.accessibility.AccessibilityNodeInfo>();
        for(int i=0;i<3;i++) {
            var date=today.plusDays(i);
            String label=java.time.format.DateTimeFormatter.ofPattern("EE\n dd",java.util.Locale.GERMAN).format(date);
            dateNodes.add(node(label,true));
        }
        var root=node("",false,selector,dateNodes.get(0),dateNodes.get(1),dateNodes.get(2));
        screen(root); screen(root); screen(root);
        int target=(int)java.time.temporal.ChronoUnit.DAYS.between(today,p.startsAt.atZone(CoursePlan.STUDIO_ZONE).toLocalDate());
        assertEquals(1,clicks(dateNodes.get(target))); assertEquals(0,clicks(selector));
    }
    @Test public void bareCalendarNumbersCannotSelectADay() {
        BookingAccessibilityService.begin(context,plan(3600));
        var selector=node("",true,node("Ausgewähltes Studio:",false),node("Mannheim",false));
        var day=node(String.valueOf(java.time.LocalDate.now(CoursePlan.STUDIO_ZONE).getDayOfMonth()),true);
        var root=node("",false,selector,day); screen(root); screen(root); screen(root);
        assertEquals(0,clicks(day));
        assertEquals("SELECT_DATE",ReflectionHelpers.getField(service,"stage").toString());
    }

    @Test public void studioPickerIsNeverOperatedEvenWhenAlreadyOpen() {
        BookingAccessibilityService.begin(context,plan(3600));
        var studio=node("Mannheim",true); var confirm=node("Auswählen",true); var all=node("Alle auswählen",true);
        var picker=node("",false,node("Studioauswahl",false),studio,confirm,all);
        screen(picker); screen(picker);
        assertEquals(0,clicks(studio)); assertEquals(0,clicks(confirm)); assertEquals(0,clicks(all));
    }
    private String format(CoursePlan p,String pattern) {
        return java.time.format.DateTimeFormatter.ofPattern(pattern,java.util.Locale.GERMAN)
                .withZone(CoursePlan.STUDIO_ZONE).format(p.startsAt);
    }
    private android.view.accessibility.AccessibilityNodeInfo calendar(CoursePlan p, boolean selected) {
        var day=node(format(p,"dd.MM.yyyy"),true); day.setSelected(selected);
        var course=node("",true,node(p.course,false),node(format(p,"HH:mm")+" - 23:59",false));
        return node("",false,node("",true,node("Ausgewähltes Studio:",false),node(p.studio,false)),day,course);
    }
    private android.view.accessibility.AccessibilityNodeInfo detail(CoursePlan p, String course, String studio,
            String weekday, String time, android.view.accessibility.AccessibilityNodeInfo button) {
        return node("",false,node(course,false),node(studio,false),node(weekday,false),node(time,false),button);
    }
    private void openCourse(CoursePlan p) {
        BookingAccessibilityService.begin(context,p);
        var root=calendar(p,true);
        screen(root); screen(root); screen(root); screen(root);
        assertEquals("SUBMIT",ReflectionHelpers.getField(service,"stage").toString());
    }
    @Test public void screenshotDetailBooksUsingConfirmedCalendarDateAndWeekday() {
        CoursePlan p=plan(3600); openCourse(p);
        var button=node("Im Studio teilnehmen",true);
        screen(detail(p,p.course,p.studio,format(p,"EEEE"),format(p,"HH:mm")+" - 23:59",button));
        assertEquals(1,clicks(button));
        assertEquals(CoursePlan.Status.VERIFY_REQUIRED,store.find(p.id).status);
        assertTrue(BookingAccessibilityService.running(p.id));
    }
    @Test public void detailRejectsMismatchedCourseStudioWeekdayAndTime() {
        CoursePlan p=plan(3600); openCourse(p);
        var button=node("Im Studio teilnehmen",true);
        screen(detail(p,"Anderer Kurs",p.studio,format(p,"EEEE"),format(p,"HH:mm"),button));
        screen(detail(p,p.course,"Anderes Studio",format(p,"EEEE"),format(p,"HH:mm"),button));
        screen(detail(p,p.course,p.studio,"Falscher Wochentag",format(p,"HH:mm"),button));
        screen(detail(p,p.course,p.studio,format(p,"EEEE"),"25:00",button));
        assertEquals(0,clicks(button));
        assertEquals(CoursePlan.Status.AUTOMATING,store.find(p.id).status);
    }
    @Test public void weekdayAndTimeMayBeCombinedInOneAccessibleLabel() {
        CoursePlan p=plan(3600); openCourse(p);
        var button=node("Im Studio teilnehmen",true);
        screen(node("",false,node(p.course,false),node(p.studio,false),
                node(format(p,"EEEE HH:mm")+" – 23:59",false),button));
        assertEquals(1,clicks(button));
        assertEquals(CoursePlan.Status.VERIFY_REQUIRED,store.find(p.id).status);
    }
    @Test public void unconfirmedCalendarDayCannotReachBookingSubmission() {
        CoursePlan p=plan(3600); BookingAccessibilityService.begin(context,p);
        var root=calendar(p,false);
        for(int i=0;i<6;i++) screen(root);
        assertEquals("VERIFY_DATE",ReflectionHelpers.getField(service,"stage").toString());
        var button=node("Im Studio teilnehmen",true);
        screen(detail(p,p.course,p.studio,format(p,"EEEE"),format(p,"HH:mm"),button));
        assertEquals(0,clicks(button));
    }

    private void prepareCourseSearch(CoursePlan p) {
        BookingAccessibilityService.begin(context,p);
        var root=calendar(p,true); screen(root); screen(root); screen(root);
        assertEquals("SELECT_COURSE",ReflectionHelpers.getField(service,"stage").toString());
    }
    private android.view.accessibility.AccessibilityNodeInfo scrollingList(String name,String time) {
        var list=node("",false,node("",true,node(name,false),node(time,false)));
        list.setScrollable(true); list.setClassName("androidx.recyclerview.widget.RecyclerView");
        list.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        return list;
    }
    private void afterScroll() { org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(1100)); }
    @Test public void scrollsSeveralPagesThenOpensCourseAndSubmitsOnDetailOnly() {
        CoursePlan p=plan(3600); prepareCourseSearch(p);
        var first=scrollingList("Früher Kurs","07:00 - 08:00");
        screen(node("",false,first)); assertEquals(1,clicks(first));
        // Repeated content events while scrolling must not trigger another action.
        screen(node("",false,first)); assertEquals(1,clicks(first));
        afterScroll();
        var second=scrollingList("Weiterer Kurs","09:00 - 10:00");
        screen(node("",false,second)); assertEquals(1,clicks(second));
        afterScroll();
        var wanted=node("",true,node(p.course,false),node(format(p,"HH:mm")+" - 23:59",false));
        var third=node("",false,wanted); third.setScrollable(true);
        screen(node("",false,third));
        assertEquals(1,clicks(wanted)); assertEquals(0,clicks(third));
        assertEquals(CoursePlan.Status.AUTOMATING,store.find(p.id).status);
        var button=node("Im Studio teilnehmen",true);
        screen(detail(p,p.course,p.studio,format(p,"EEEE"),format(p,"HH:mm"),button));
        assertEquals(1,clicks(button)); assertEquals(CoursePlan.Status.VERIFY_REQUIRED,store.find(p.id).status);
    }
    @Test public void listEndStopsWithoutClickingAnyCourseOrBooking() {
        CoursePlan p=plan(3600); prepareCourseSearch(p);
        var list=scrollingList("Anderer Kurs","07:00 - 08:00");
        shadowOf(list).setOnPerformActionListener((action,args) -> false);
        screen(node("",false,list));
        assertEquals(CoursePlan.Status.ACTION_REQUIRED,store.find(p.id).status);
        assertEquals(1,clicks(list));
    }
    @Test public void unchangedViewportDoesNotCauseEndlessScrollActions() {
        CoursePlan p=plan(3600); prepareCourseSearch(p);
        var list=scrollingList("Anderer Kurs","07:00 - 08:00"); var root=node("",false,list);
        screen(root);
        for(int i=0;i<3;i++) { afterScroll(); screen(root); }
        assertEquals(1,clicks(list));
        assertEquals(CoursePlan.Status.ACTION_REQUIRED,store.find(p.id).status);
    }
    @Test public void horizontalCalendarIsNeverScrolledInsteadOfCourses() {
        CoursePlan p=plan(3600); prepareCourseSearch(p);
        var horizontal=scrollingList("Anderer Kurs","07:00 - 08:00");
        horizontal.setClassName("android.widget.HorizontalScrollView");
        var vertical=scrollingList("Dritter Kurs","09:00 - 10:00");
        screen(node("",false,horizontal,vertical));
        assertEquals(0,clicks(horizontal)); assertEquals(1,clicks(vertical));
    }
    @Test public void visibleCourseIsOpenedWithoutScrolling() {
        CoursePlan p=plan(3600); prepareCourseSearch(p);
        var course=node("",true,node(p.course,false),node(format(p,"HH:mm"),false));
        var list=node("",false,course); list.setScrollable(true);
        screen(node("",false,list));
        assertEquals(1,clicks(course)); assertEquals(0,clicks(list));
    }
    @Test public void changedSelectedDayStopsListSearch() {
        CoursePlan p=plan(3600); prepareCourseSearch(p);
        var list=scrollingList("Anderer Kurs","07:00 - 08:00");
        var day=node(format(p,"dd.MM.yyyy"),true);
        var wrongDate=p.startsAt.atZone(CoursePlan.STUDIO_ZONE).toLocalDate().plusDays(1);
        var wrongDay=node(java.time.format.DateTimeFormatter.ofPattern("EE dd",java.util.Locale.GERMAN).format(wrongDate),true);
        wrongDay.setSelected(true);
        screen(node("",false,day,wrongDay,list));
        assertEquals(0,clicks(list)); assertEquals(CoursePlan.Status.ACTION_REQUIRED,store.find(p.id).status);
    }
    @Test public void ambiguousCourseMatchesAreNotHiddenByScrollingPastThem() {
        CoursePlan p=plan(3600); prepareCourseSearch(p);
        var first=node("",true,node(p.course,false),node(format(p,"HH:mm"),false));
        var second=node("",true,node(p.course,false),node(format(p,"HH:mm"),false));
        var list=node("",false,first,second); list.setScrollable(true);
        screen(node("",false,list));
        assertEquals(0,clicks(list)); assertEquals(0,clicks(first)); assertEquals(0,clicks(second));
        assertEquals(CoursePlan.Status.ACTION_REQUIRED,store.find(p.id).status);
    }

    @Test public void missingSelectionMetadataNeverStartsSearchJustBecauseTimeElapsed() {
        CoursePlan p=plan(3600); BookingAccessibilityService.begin(context,p);
        var root=calendar(p,false); screen(root); screen(root); screen(root);
        assertEquals("VERIFY_DATE",ReflectionHelpers.getField(service,"stage").toString());
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(1300));
        screen(root); screen(root);
        assertEquals(CoursePlan.Status.ACTION_REQUIRED,store.find(p.id).status);
    }
    @Test public void shortCalendarDoesNotRequireThreeVisibleDays() {
        CoursePlan p=plan(3600); BookingAccessibilityService.begin(context,p);
        var today=java.time.LocalDate.now(CoursePlan.STUDIO_ZONE);
        var target=p.startsAt.atZone(CoursePlan.STUDIO_ZONE).toLocalDate();
        var formatter=java.time.format.DateTimeFormatter.ofPattern("EE dd",java.util.Locale.GERMAN);
        var todayNode=node(formatter.format(today),true);
        var targetNode=target.equals(today) ? todayNode : node(formatter.format(target),true);
        var header=node("Ausgewählte Studios: Mehrere Studios",true);
        var root=targetNode==todayNode ? node("",false,header,todayNode) : node("",false,header,todayNode,targetNode);
        screen(root); screen(root);
        assertEquals(1,clicks(targetNode)); assertEquals(0,clicks(header));
    }
    @Test public void missingDayExplainsTheCalendarFailureAndDiagnosticSurvivesRecreation() {
        CoursePlan p=plan(3600); BookingAccessibilityService.begin(context,p);
        var root=node("",false,node("Ausgewähltes Studio:",true),node("MI.",false),node("04",false));
        screen(root); screen(root);
        String diagnostic=new PlanStore(context).diagnostic(p.id);
        assertTrue(diagnostic.contains("nicht eindeutig oder nicht klickbar"));
        assertTrue(diagnostic.contains("mi."));
        finish(); assertEquals(diagnostic,new PlanStore(context).diagnostic(p.id));
        store.remove(p.id); assertEquals("",store.diagnostic(p.id));
    }
    @Test public void explicitOtherSelectedDayBlocksMetadataFallback() {
        CoursePlan p=plan(3600); BookingAccessibilityService.begin(context,p);
        var correct=node(format(p,"dd.MM.yyyy"),true);
        var wrong=p.startsAt.atZone(CoursePlan.STUDIO_ZONE).toLocalDate().plusDays(1);
        var wrongDay=node(java.time.format.DateTimeFormatter.ofPattern("EE dd",java.util.Locale.GERMAN).format(wrong),true);
        wrongDay.setSelected(true);
        var root=node("",false,node("Ausgewähltes Studio:",false),correct,wrongDay,node("07:00 - 08:00",false));
        screen(root); screen(root);
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(1300));
        screen(root);
        assertEquals("VERIFY_DATE",ReflectionHelpers.getField(service,"stage").toString());
        screen(root);
        assertEquals(CoursePlan.Status.ACTION_REQUIRED,store.find(p.id).status);
    }

    private CoursePlan tomorrowPlan() {
        var start=java.time.LocalDate.now(CoursePlan.STUDIO_ZONE).plusDays(1).atStartOfDay(CoursePlan.STUDIO_ZONE).toInstant();
        CoursePlan p=new CoursePlan(UUID.randomUUID().toString(),"Yoga","Mannheim",start,CoursePlan.Status.PLANNED,true);
        store.put(p); return p;
    }
    private android.view.accessibility.AccessibilityNodeInfo dayTab(java.time.LocalDate date,boolean combined,boolean clickable) {
        String weekday=java.time.format.DateTimeFormatter.ofPattern("EE",java.util.Locale.GERMAN).format(date);
        String day=String.format(java.util.Locale.GERMAN,"%02d",date.getDayOfMonth());
        var tab=combined ? node(weekday+"\n"+day,clickable)
                : node("",clickable,node("",false,node(weekday,false)),node("",false,node(day,false)));
        tab.setBoundsInScreen(new android.graphics.Rect(80,100,140,150)); return tab;
    }
    @Test public void tomorrowTabWithSeparatedLabelsIsClickedBeforeOpeningCourse() {
        CoursePlan p=tomorrowPlan(); assertTrue(BookingAccessibilityService.begin(context,p));
        var today=dayTab(java.time.LocalDate.now(CoursePlan.STUDIO_ZONE),true,true); today.setSelected(true);
        var tomorrow=dayTab(p.startsAt.atZone(CoursePlan.STUDIO_ZONE).toLocalDate(),false,true);
        var targetCourse=node("",true,node(p.course,false),node(format(p,"HH:mm"),false));
        var root=node("",false,node("Ausgewählte Studios:",false),today,tomorrow,targetCourse);
        screen(root); screen(root);
        assertEquals(0,clicks(today)); assertEquals(1,clicks(tomorrow)); assertEquals(0,clicks(targetCourse));
        today.setSelected(false); tomorrow.setSelected(true); screen(root); screen(root);
        assertEquals(1,clicks(targetCourse));
        var button=node("Im Studio teilnehmen",true);
        screen(detail(p,p.course,p.studio,format(p,"EEEE"),format(p,"HH:mm"),button));
        assertEquals(1,clicks(button)); assertEquals(CoursePlan.Status.VERIFY_REQUIRED,store.find(p.id).status);
    }
    @Test public void nonClickableTomorrowTabUsesOneGestureAndWaitsForCompletion() {
        CoursePlan p=tomorrowPlan(); assertTrue(BookingAccessibilityService.begin(context,p));
        var today=dayTab(java.time.LocalDate.now(CoursePlan.STUDIO_ZONE),true,true); today.setSelected(true);
        var tomorrow=dayTab(p.startsAt.atZone(CoursePlan.STUDIO_ZONE).toLocalDate(),false,false);
        var root=node("",false,node("Ausgewähltes Studio:",false),today,tomorrow,node("07:00 - 08:00",false));
        var serviceShadow=shadowOf(service); serviceShadow.setCanDispatchGestures(true);
        screen(root); screen(root); screen(root);
        assertEquals(1,serviceShadow.getGesturesDispatched().size());
        assertEquals("VERIFY_DATE",ReflectionHelpers.getField(service,"stage").toString());
        var gesture=serviceShadow.getGesturesDispatched().get(0);
        today.setSelected(false); tomorrow.setSelected(true); gesture.callback().onCompleted(gesture.description());
        screen(root);
        assertEquals("SELECT_COURSE",ReflectionHelpers.getField(service,"stage").toString());
        assertEquals(1,serviceShadow.getGesturesDispatched().size());
    }
    @Test public void acknowledgedClickWithOldDayStillSelectedRetriesOnceByGesture() {
        CoursePlan p=tomorrowPlan(); BookingAccessibilityService.begin(context,p);
        var today=dayTab(java.time.LocalDate.now(CoursePlan.STUDIO_ZONE),true,true); today.setSelected(true);
        var tomorrow=dayTab(p.startsAt.atZone(CoursePlan.STUDIO_ZONE).toLocalDate(),true,true);
        var root=node("",false,node("Ausgewähltes Studio:",false),today,tomorrow,node("07:00 - 08:00",false));
        var serviceShadow=shadowOf(service); serviceShadow.setCanDispatchGestures(true);
        screen(root); screen(root);
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(1300)); screen(root); screen(root);
        assertEquals(1,clicks(tomorrow)); assertEquals(1,serviceShadow.getGesturesDispatched().size());
        var gesture=serviceShadow.getGesturesDispatched().get(0); gesture.callback().onCompleted(gesture.description());
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(1300)); screen(root);
        assertEquals("VERIFY_DATE",ReflectionHelpers.getField(service,"stage").toString());
        assertEquals(1,serviceShadow.getGesturesDispatched().size());
    }
    @Test public void cancelledCalendarTapStopsWithoutOpeningCourse() {
        CoursePlan p=tomorrowPlan(); BookingAccessibilityService.begin(context,p);
        var today=dayTab(java.time.LocalDate.now(CoursePlan.STUDIO_ZONE),true,true);
        var tomorrow=dayTab(p.startsAt.atZone(CoursePlan.STUDIO_ZONE).toLocalDate(),false,false);
        var root=node("",false,node("Ausgewähltes Studio:",false),today,tomorrow);
        var serviceShadow=shadowOf(service); serviceShadow.setCanDispatchGestures(true); screen(root); screen(root);
        var gesture=serviceShadow.getGesturesDispatched().get(0); gesture.callback().onCancelled(gesture.description());
        assertEquals(CoursePlan.Status.ACTION_REQUIRED,store.find(p.id).status);
        assertTrue(store.diagnostic(p.id).contains("abgebrochen"));
    }
    @Test public void calendarStripCannotBeMistakenForASingleDayCell() {
        CoursePlan p=tomorrowPlan(); BookingAccessibilityService.begin(context,p);
        var today=dayTab(java.time.LocalDate.now(CoursePlan.STUDIO_ZONE),true,true);
        var tomorrow=dayTab(p.startsAt.atZone(CoursePlan.STUDIO_ZONE).toLocalDate(),false,false);
        tomorrow.setBoundsInScreen(new android.graphics.Rect());
        var strip=node("",true,today,tomorrow); strip.setScrollable(true);
        var root=node("",false,node("Ausgewähltes Studio:",false),strip);
        var serviceShadow=shadowOf(service); serviceShadow.setCanDispatchGestures(true); screen(root); screen(root);
        assertEquals(0,clicks(strip)); assertTrue(serviceShadow.getGesturesDispatched().isEmpty());
        assertEquals("SELECT_DATE",ReflectionHelpers.getField(service,"stage").toString());
    }

    private void calibratePositions() {
        var metrics=CalendarCalibration.screen(context);
        CalendarCalibration.save(context,new android.graphics.PointF(metrics.widthPixels*.25f,metrics.heightPixels*.3f),
                new android.graphics.PointF(metrics.widthPixels*.42f,metrics.heightPixels*.3f));
    }
    @Test public void userCalibrationChangesToTomorrowWithoutAccessibleDayCellsThenBooksOnDetail() {
        calibratePositions(); CoursePlan p=tomorrowPlan(); assertTrue(BookingAccessibilityService.begin(context,p));
        var course=node("",true,node(p.course,false),node(format(p,"HH:mm"),false));
        var selector=node("Ausgewähltes Studio:",true);
        var root=node("",false,selector,course);
        var serviceShadow=shadowOf(service); serviceShadow.setCanDispatchGestures(true);
        screen(root); screen(root); screen(root);
        assertEquals(1,serviceShadow.getGesturesDispatched().size()); assertEquals(0,clicks(course));
        var gesture=serviceShadow.getGesturesDispatched().get(0);
        gesture.callback().onCompleted(gesture.description());
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(1300));
        applySelectedDayImage(root);
        screen(root); screen(root);
        assertEquals(1,clicks(course)); assertEquals(0,clicks(selector));
        var button=node("Im Studio teilnehmen",true);
        screen(detail(p,p.course,p.studio,format(p,"EEEE"),format(p,"HH:mm"),button));
        assertEquals(1,clicks(button)); assertEquals(CoursePlan.Status.VERIFY_REQUIRED,store.find(p.id).status);
    }
    @Test public void calibrationTakesPrecedenceOverAnIneffectiveNativeClick() {
        calibratePositions(); CoursePlan p=tomorrowPlan(); BookingAccessibilityService.begin(context,p);
        var today=dayTab(java.time.LocalDate.now(CoursePlan.STUDIO_ZONE),true,true);
        var tomorrow=dayTab(p.startsAt.atZone(CoursePlan.STUDIO_ZONE).toLocalDate(),true,true);
        var root=node("",false,node("Ausgewähltes Studio:",false),today,tomorrow);
        var serviceShadow=shadowOf(service); serviceShadow.setCanDispatchGestures(true);
        screen(root); screen(root);
        assertEquals(0,clicks(tomorrow)); assertEquals(1,serviceShadow.getGesturesDispatched().size());
    }
    @Test public void calibratedCoordinatesNeverOperateAStudioPicker() {
        calibratePositions(); CoursePlan p=tomorrowPlan(); BookingAccessibilityService.begin(context,p);
        var serviceShadow=shadowOf(service); serviceShadow.setCanDispatchGestures(true);
        screen(node("",false,node("Studioauswahl",false),node("Auswählen",true)));
        assertTrue(serviceShadow.getGesturesDispatched().isEmpty());
    }

    private void applySelectedDayImage(android.view.accessibility.AccessibilityNodeInfo root) {
        android.graphics.Rect bounds=ReflectionHelpers.callInstanceMethod(service,"dayBounds",
                ReflectionHelpers.ClassParameter.from(android.view.accessibility.AccessibilityNodeInfo.class,root),
                ReflectionHelpers.ClassParameter.from(android.view.accessibility.AccessibilityNodeInfo.class,null));
        assertNotNull(bounds);
        var display=CalendarCalibration.screen(context);
        var image=android.graphics.Bitmap.createBitmap(display.widthPixels,display.heightPixels,android.graphics.Bitmap.Config.ARGB_8888);
        var canvas=new android.graphics.Canvas(image); canvas.drawColor(android.graphics.Color.rgb(29,26,39));
        var paint=new android.graphics.Paint(); paint.setColor(android.graphics.Color.BLACK); canvas.drawRect(bounds,paint);
        paint.setColor(android.graphics.Color.CYAN); canvas.drawRect(bounds.left+2,bounds.top+2,bounds.left+10,bounds.top+10,paint);
        ReflectionHelpers.callInstanceMethod(service,"applyDayImage",
                ReflectionHelpers.ClassParameter.from(android.graphics.Bitmap.class,image),
                ReflectionHelpers.ClassParameter.from(android.graphics.Rect.class,bounds));
        image.recycle();
    }
    @Test public void completedCalibratedTapWithoutSelectionProofCannotOpenCourse() {
        calibratePositions(); CoursePlan p=tomorrowPlan(); BookingAccessibilityService.begin(context,p);
        var course=node("",true,node(p.course,false),node(format(p,"HH:mm"),false));
        var root=node("",false,node("Ausgewähltes Studio:",false),course);
        var serviceShadow=shadowOf(service); serviceShadow.setCanDispatchGestures(true);
        screen(root); screen(root);
        var gesture=serviceShadow.getGesturesDispatched().get(0); gesture.callback().onCompleted(gesture.description());
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(1300));
        screen(root); screen(root);
        assertEquals(0,clicks(course)); assertEquals(CoursePlan.Status.ACTION_REQUIRED,store.find(p.id).status);
    }

    @Test @Config(sdk=31) public void android12ScreenshotFailureCannotReleaseCourseSearch() {
        CoursePlan p=tomorrowPlan(); BookingAccessibilityService.begin(context,p);
        var today=dayTab(java.time.LocalDate.now(CoursePlan.STUDIO_ZONE),true,true);
        var tomorrow=dayTab(p.startsAt.atZone(CoursePlan.STUDIO_ZONE).toLocalDate(),true,true);
        var course=node("",true,node(p.course,false),node(format(p,"HH:mm"),false));
        var root=node("",false,node("Ausgewähltes Studio:",false),today,tomorrow,course);
        var serviceShadow=shadowOf(service); serviceShadow.setCanDispatchGestures(false);
        serviceShadow.setTakeScreenshotErrorCode(android.accessibilityservice.AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS);
        screen(root); screen(root);
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(1300));
        screen(root); shadowOf(android.os.Looper.getMainLooper()).idle(); screen(root);
        assertEquals(0,clicks(course)); assertEquals(CoursePlan.Status.ACTION_REQUIRED,store.find(p.id).status);
        assertTrue(store.diagnostic(p.id),store.diagnostic(p.id).contains("Kalenderbild-Prüfung von Android abgelehnt"));
    }

    private void homeFor(android.view.accessibility.AccessibilityNodeInfo root,String id) {
        ReflectionHelpers.callInstanceMethod(service,"closeConfirmedBooking",
                ReflectionHelpers.ClassParameter.from(android.view.accessibility.AccessibilityNodeInfo.class,root),
                ReflectionHelpers.ClassParameter.from(String.class,id));
    }
    @Test public void onlyUnregisterButtonConfirmsBookingAndAllowsReturningHome() {
        CoursePlan p=plan(3600); openCourse(p);
        var register=node("Im Studio teilnehmen",true);
        var waiting=detail(p,p.course,p.studio,format(p,"EEEE"),format(p,"HH:mm"),register);
        screen(waiting); screen(waiting);
        assertEquals(1,clicks(register)); assertEquals(CoursePlan.Status.VERIFY_REQUIRED,store.find(p.id).status);
        assertFalse(shadowOf(service).getGlobalActionsPerformed().contains(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME));
        var unregister=node("Vom Kurs abmelden",true);
        var confirmed=detail(p,p.course,p.studio,format(p,"EEEE"),format(p,"HH:mm"),unregister);
        confirmed.setPackageName(VeniceBeach.PACKAGE); screen(confirmed);
        assertEquals(CoursePlan.Status.BOOKED,store.find(p.id).status); assertEquals(0,clicks(unregister));
        homeFor(confirmed,p.id);
        assertTrue(shadowOf(service).getGlobalActionsPerformed().contains(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME));
    }
    @Test public void missingConfirmationKeepsSubmittedStateAndDoesNotCloseApp() {
        CoursePlan p=plan(3600); openCourse(p);
        screen(detail(p,p.course,p.studio,format(p,"EEEE"),format(p,"HH:mm"),node("Im Studio teilnehmen",true)));
        finish();
        assertEquals(CoursePlan.Status.VERIFY_REQUIRED,store.find(p.id).status);
        var root=node("",false); root.setPackageName(VeniceBeach.PACKAGE); homeFor(root,p.id);
        assertFalse(shadowOf(service).getGlobalActionsPerformed().contains(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME));
    }
    @Test public void queuedBookingPreventsHomeAfterFirstConfirmation() {
        CoursePlan first=plan(3600); openCourse(first); CoursePlan second=plan(7200);
        BookingAccessibilityService.begin(context,second);
        screen(detail(first,first.course,first.studio,format(first,"EEEE"),format(first,"HH:mm"),node("Im Studio teilnehmen",true)));
        var confirmed=detail(first,first.course,first.studio,format(first,"EEEE"),format(first,"HH:mm"),node("Vom Kurs abmelden",true));
        confirmed.setPackageName(VeniceBeach.PACKAGE); screen(confirmed); homeFor(confirmed,first.id);
        assertEquals(CoursePlan.Status.BOOKED,store.find(first.id).status);
        assertEquals(CoursePlan.Status.AUTOMATING,store.find(second.id).status);
        assertFalse(shadowOf(service).getGlobalActionsPerformed().contains(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME));
    }
    @Test public void confirmationForWrongStudioCannotMarkPlanBooked() {
        CoursePlan p=plan(3600); openCourse(p);
        screen(detail(p,p.course,p.studio,format(p,"EEEE"),format(p,"HH:mm"),node("Im Studio teilnehmen",true)));
        screen(detail(p,p.course,"Anderes Studio",format(p,"EEEE"),format(p,"HH:mm"),node("Vom Kurs abmelden",true)));
        assertEquals(CoursePlan.Status.VERIFY_REQUIRED,store.find(p.id).status);
    }
    @Test public void bookedPlanCannotCloseAnUnrelatedForegroundApp() {
        CoursePlan p=plan(3600); openCourse(p);
        screen(detail(p,p.course,p.studio,format(p,"EEEE"),format(p,"HH:mm"),node("Vom Kurs abmelden",true)));
        assertEquals(CoursePlan.Status.BOOKED,store.find(p.id).status);
        var other=node("",false); other.setPackageName("another.app"); homeFor(other,p.id);
        assertFalse(shadowOf(service).getGlobalActionsPerformed().contains(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME));
    }

    @Test public void unknownFutureTabDoesNotReuseThePreviouslySelectedDateForImport() {
        var today=java.time.LocalDate.now(CoursePlan.STUDIO_ZONE);
        var current=dayTab(today,true,true); current.setSelected(true);
        var root=node("",false,node("Ausgewähltes Studio:",false),current);
        ReflectionHelpers.callInstanceMethod(service,"rememberPickerDay",
                ReflectionHelpers.ClassParameter.from(android.view.accessibility.AccessibilityNodeInfo.class,root),
                ReflectionHelpers.ClassParameter.from(android.view.accessibility.AccessibilityNodeInfo.class,null));
        assertEquals(today,ReflectionHelpers.getField(service,"pickerDate"));
        var unknown=dayTab(today.plusDays(7),true,true);
        ReflectionHelpers.callInstanceMethod(service,"rememberPickerDay",
                ReflectionHelpers.ClassParameter.from(android.view.accessibility.AccessibilityNodeInfo.class,root),
                ReflectionHelpers.ClassParameter.from(android.view.accessibility.AccessibilityNodeInfo.class,unknown));
        assertNull(ReflectionHelpers.getField(service,"pickerDate"));
    }

    @Test public void lockingAfterSubmissionNeverDefersTheClickForAnotherAttempt() {
        BookingPreferences.waitForUnlock(context,true); CoursePlan p=plan(3600); openCourse(p);
        screen(detail(p,p.course,p.studio,format(p,"EEEE"),format(p,"HH:mm"),node("Im Studio teilnehmen",true)));
        shadowOf(context.getSystemService(KeyguardManager.class)).setKeyguardLocked(true);
        shadowOf(context.getSystemService(PowerManager.class)).setIsInteractive(false);
        context.sendBroadcast(new Intent(Intent.ACTION_SCREEN_OFF)); shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals(CoursePlan.Status.VERIFY_REQUIRED,store.find(p.id).status);
        assertFalse(BookingAccessibilityService.running(p.id));
        shadowOf(context.getSystemService(KeyguardManager.class)).setKeyguardLocked(false);
        shadowOf(context.getSystemService(PowerManager.class)).setIsInteractive(true);
        BookingDispatcher.resumeAfterUnlock(context);
        assertEquals(CoursePlan.Status.VERIFY_REQUIRED,store.find(p.id).status);
    }

    @Test public void oldGestureCallbackCannotCancelSamePlanAfterUnlockRestart() {
        BookingPreferences.waitForUnlock(context,true); CoursePlan p=tomorrowPlan(); BookingAccessibilityService.begin(context,p);
        var today=dayTab(java.time.LocalDate.now(CoursePlan.STUDIO_ZONE),true,true); today.setSelected(true);
        var tomorrow=dayTab(p.startsAt.atZone(CoursePlan.STUDIO_ZONE).toLocalDate(),false,false);
        var root=node("",false,node("Ausgewähltes Studio:",false),today,tomorrow);
        var serviceShadow=shadowOf(service); serviceShadow.setCanDispatchGestures(true); screen(root); screen(root);
        var old=serviceShadow.getGesturesDispatched().get(0);
        shadowOf(context.getSystemService(KeyguardManager.class)).setKeyguardLocked(true);
        shadowOf(context.getSystemService(PowerManager.class)).setIsInteractive(false);
        context.sendBroadcast(new Intent(Intent.ACTION_SCREEN_OFF)); shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals(CoursePlan.Status.WAITING_UNLOCK,store.find(p.id).status);
        shadowOf(context.getSystemService(KeyguardManager.class)).setKeyguardLocked(false);
        shadowOf(context.getSystemService(PowerManager.class)).setIsInteractive(true); BookingDispatcher.resumeAfterUnlock(context);
        assertEquals(CoursePlan.Status.AUTOMATING,store.find(p.id).status);
        old.callback().onCancelled(old.description());
        assertEquals(CoursePlan.Status.AUTOMATING,store.find(p.id).status); assertTrue(BookingAccessibilityService.running(p.id));
    }

    @Test public void unlockRestartReturnsFromAnUnsubmittedDetailToCalendarBeforeBooking() {
        BookingPreferences.waitForUnlock(context,true); CoursePlan p=plan(3600); openCourse(p);
        shadowOf(context.getSystemService(KeyguardManager.class)).setKeyguardLocked(true);
        shadowOf(context.getSystemService(PowerManager.class)).setIsInteractive(false);
        context.sendBroadcast(new Intent(Intent.ACTION_SCREEN_OFF)); shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals(CoursePlan.Status.WAITING_UNLOCK,store.find(p.id).status);
        shadowOf(context.getSystemService(KeyguardManager.class)).setKeyguardLocked(false);
        shadowOf(context.getSystemService(PowerManager.class)).setIsInteractive(true); BookingDispatcher.resumeAfterUnlock(context);
        var bookingButton=node("Im Studio teilnehmen",true);
        screen(detail(p,p.course,p.studio,format(p,"EEEE"),format(p,"HH:mm"),bookingButton));
        assertEquals(0,clicks(bookingButton));
        assertTrue(shadowOf(service).getGlobalActionsPerformed().contains(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK));
        assertEquals(CoursePlan.Status.AUTOMATING,store.find(p.id).status);
    }

    @Test public void bookingStartRequestsANewVeniceBeachTask() {
        CoursePlan p=plan(3600); assertTrue(BookingAccessibilityService.begin(context,p));
        Intent launch=shadowOf(context).getNextStartedActivity(); assertNotNull(launch);
        assertTrue((launch.getFlags() & Intent.FLAG_ACTIVITY_CLEAR_TASK)!=0);
        assertTrue((launch.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK)!=0);
        assertEquals("PREPARE_HOME",ReflectionHelpers.getField(service,"stage").toString());
    }
    @Test public void normalOpeningForBrowsingDoesNotClearTheTask() {
        assertTrue(VeniceBeach.open(context)); Intent launch=shadowOf(context).getNextStartedActivity();
        assertNotNull(launch); assertEquals(0,launch.getFlags() & Intent.FLAG_ACTIVITY_CLEAR_TASK);
    }
    @Test public void savedCalendarCannotSkipTheHomeResetGate() {
        CoursePlan p=plan(3600); BookingAccessibilityService.begin(context,p);
        var calendar=calendar(p,true); calendar.setPackageName(VeniceBeach.PACKAGE);
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(5100)); rawScreen(calendar);
        assertEquals("PREPARE_HOME",ReflectionHelpers.getField(service,"stage").toString());
        assertTrue(shadowOf(service).getGlobalActionsPerformed().contains(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK));
        for(int i=0;i<calendar.getChildCount();i++) assertEquals(0,clicks(calendar.getChild(i)));
    }
    @Test public void nestedScreensReturnToRootBeforeCommunityAndStudioNavigation() {
        CoursePlan p=plan(3600); BookingAccessibilityService.begin(context,p);
        var detail=detail(p,p.course,p.studio,format(p,"EEEE"),format(p,"HH:mm"),node("Im Studio teilnehmen",true)); detail.setPackageName(VeniceBeach.PACKAGE);
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(5100)); rawScreen(detail); rawScreen(detail);
        assertEquals(1,shadowOf(service).getGlobalActionsPerformed().size());
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(1100));
        var home=homeRoot(); var community=home.getChild(0); rawScreen(home);
        assertEquals(1,clicks(community)); assertEquals("PREPARE_HOME",ReflectionHelpers.getField(service,"stage").toString());
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(1100)); rawScreen(home);
        assertEquals("OPEN_COURSES",ReflectionHelpers.getField(service,"stage").toString());
        rawScreen(home); assertEquals(1,clicks(home.getChild(2)));
    }
    @Test public void unknownScreenStopsAfterBoundedBackNavigation() {
        CoursePlan p=plan(3600); BookingAccessibilityService.begin(context,p);
        var unknown=node("Unbekannte Unterseite",false); unknown.setPackageName(VeniceBeach.PACKAGE);
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(5100));
        for(int i=0;i<7;i++) { rawScreen(unknown); org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(1100)); }
        assertEquals(CoursePlan.Status.ACTION_REQUIRED,store.find(p.id).status);
        assertEquals(6,shadowOf(service).getGlobalActionsPerformed().size());
    }
    @Test public void startupNeverNavigatesBackInAnotherForegroundApp() {
        CoursePlan p=plan(3600); BookingAccessibilityService.begin(context,p);
        var other=node("Andere App",false); other.setPackageName("another.app"); rawScreen(other);
        assertTrue(shadowOf(service).getGlobalActionsPerformed().isEmpty());
        assertEquals("PREPARE_HOME",ReflectionHelpers.getField(service,"stage").toString());
    }
    @Test public void loginScreenStopsWithoutClickingLoginOrBooking() {
        CoursePlan p=plan(3600); BookingAccessibilityService.begin(context,p);
        var login=node("Anmelden",true); var root=node("",false,login,node("Passwort",false)); root.setPackageName(VeniceBeach.PACKAGE);
        rawScreen(root); assertEquals(0,clicks(login)); assertEquals(CoursePlan.Status.ACTION_REQUIRED,store.find(p.id).status);
    }
    @Test public void eachQueuedPlanReceivesItsOwnFreshStart() {
        CoursePlan first=plan(3600),second=plan(7200); BookingAccessibilityService.begin(context,first); BookingAccessibilityService.begin(context,second);
        Intent firstLaunch=shadowOf(context).getNextStartedActivity(); assertNotNull(firstLaunch); finish();
        Intent nextLaunch=shadowOf(context).getNextStartedActivity(); assertNotNull(nextLaunch);
        assertTrue((nextLaunch.getFlags() & Intent.FLAG_ACTIVITY_CLEAR_TASK)!=0);
        assertEquals(CoursePlan.Status.AUTOMATING,store.find(second.id).status);
        assertEquals("PREPARE_HOME",ReflectionHelpers.getField(service,"stage").toString());
    }

    @Test public void slowStartupAndSplashAreNotDismissedByBackActions() {
        CoursePlan p=plan(3600); BookingAccessibilityService.begin(context,p);
        var unknown=node("App wird gestartet",false); unknown.setPackageName(VeniceBeach.PACKAGE);
        rawScreen(unknown); assertTrue(shadowOf(service).getGlobalActionsPerformed().isEmpty());
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(6000));
        var splash=node("VeniceBeach",false); splash.setPackageName(VeniceBeach.PACKAGE); rawScreen(splash);
        var spinner=node("",false); spinner.setClassName("android.widget.ProgressBar");
        var loading=node("Verbindung",false,spinner); loading.setPackageName(VeniceBeach.PACKAGE); rawScreen(loading);
        assertTrue(shadowOf(service).getGlobalActionsPerformed().isEmpty());
        assertEquals("PREPARE_HOME",ReflectionHelpers.getField(service,"stage").toString());
    }

}
