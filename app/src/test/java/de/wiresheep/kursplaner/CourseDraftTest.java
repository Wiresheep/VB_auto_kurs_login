package de.wiresheep.kursplaner;

import android.app.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class CourseDraftTest {
    private AccessibilityNodeInfo screen(String... labels) {
        var root=AccessibilityNodeInfo.obtain(); root.setVisibleToUser(true); root.setPackageName(VeniceBeach.PACKAGE);
        for(String text:labels) {
            var child=AccessibilityNodeInfo.obtain(); child.setText(text); child.setVisibleToUser(true); shadowOf(root).addChild(child);
        }
        return root;
    }
    @Test public void screenshotDetailImportsNameStudioAndTimeWithoutGuessingDate() {
        var root=screen("Body & Mind","KR","Pilates","17/30","Mittwoch 10:00 - 11:00","VeniceBeach Grünstadt",
                "Beschreibung","Pilates ist ein ganzheitliches Training.","Zum Kalender hinzufügen","Im Studio teilnehmen");
        CourseDraft draft=CourseDraft.read(root,null);
        assertEquals("Pilates",draft.course); assertEquals("VeniceBeach Grünstadt",draft.studio);
        assertEquals(LocalTime.of(10,0),draft.time); assertNull(draft.date);
        assertTrue(shadowOf(root).getPerformedActions().isEmpty());
    }
    @Test public void splitWeekdayAndTimeUseTheUserSelectedCalendarDate() {
        var root=screen("Yoga","5/30","Donnerstag","08:15 – 09:45","Via Vital Schwetzingen Plus","Beschreibung","Im Studio teilnehmen");
        var date=LocalDate.of(2026,11,12); var draft=CourseDraft.read(root,date);
        assertEquals("Yoga",draft.course); assertEquals(date,draft.date); assertEquals(LocalTime.of(8,15),draft.time);
    }
    @Test public void mismatchedCalendarWeekdayRequiresDateReview() {
        var root=screen("Yoga","Donnerstag 08:15 – 09:45","VeniceBeach Grünstadt","Beschreibung","Im Studio teilnehmen");
        assertNull(CourseDraft.read(root,LocalDate.of(2026,11,13)).date);
    }
    @Test public void explicitFullDateCanBeImportedFromDetail() {
        var root=screen("Yoga","12.11.2026","Donnerstag 08:15 – 09:45","VeniceBeach Grünstadt","Beschreibung","Im Studio teilnehmen");
        assertEquals(LocalDate.of(2026,11,12),CourseDraft.read(root,null).date);
    }
    @Test public void courseListOrOtherAppsCannotBeImportedAsCourseDetails() {
        try { CourseDraft.read(screen("Ausgewählte Studios:","Yoga","08:15 - 09:45","VeniceBeach Grünstadt"),null); fail(); }
        catch(IllegalArgumentException expected) { }
        var root=screen("Yoga","08:15 - 09:45","VeniceBeach Grünstadt","Im Studio teilnehmen"); root.setPackageName("other.app");
        try { CourseDraft.read(root,null); fail(); } catch(IllegalArgumentException expected) { }
    }
    @Test public void multipleCourseTimesAreRejected() {
        try { CourseDraft.read(screen("Yoga","08:15 - 09:45","VeniceBeach Grünstadt","10:00 - 11:00","Im Studio teilnehmen"),null); fail(); }
        catch(IllegalArgumentException expected) { }
    }
    private Button button(View view,String text) {
        if(view instanceof Button && ((Button)view).getText().toString().equals(text)) return (Button)view;
        if(view instanceof ViewGroup) for(int i=0;i<((ViewGroup)view).getChildCount();i++) {
            Button found=button(((ViewGroup)view).getChildAt(i),text); if(found!=null) return found;
        }
        return null;
    }
    @Test public void importedDraftNeedsExplicitSavingAndMissingDateCannotBeSavedAfterRecreation() {
        var context=RuntimeEnvironment.getApplication(); context.getSharedPreferences("course_plans",0).edit().clear().commit();
        CourseDraft draft=CourseDraft.read(screen("Pilates","Mittwoch 10:00 - 11:00","VeniceBeach Grünstadt","Beschreibung","Im Studio teilnehmen"),null);
        try(var controller=Robolectric.buildActivity(MainActivity.class,draft.intent(context)).setup()) {
            var activity=controller.get();
            assertEquals("Pilates",((EditText)activity.findViewById(1002)).getText().toString());
            assertEquals("VeniceBeach Grünstadt",((EditText)activity.findViewById(1001)).getText().toString());
            assertTrue(new PlanStore(context).all().isEmpty());
            button(activity.getWindow().getDecorView(),"Plan speichern").performClick();
            assertTrue(new PlanStore(context).all().isEmpty());
            controller.recreate();
            button(controller.get().getWindow().getDecorView(),"Plan speichern").performClick();
            assertTrue(new PlanStore(context).all().isEmpty());
        }
    }
    @Test public void draftWithKnownFutureDateCanBeSavedOnlyByUser() {
        var context=RuntimeEnvironment.getApplication(); context.getSharedPreferences("course_plans",0).edit().clear().commit();
        LocalDate tomorrow=LocalDate.now(CoursePlan.STUDIO_ZONE).plusDays(1);
        String weekday=DateTimeFormatter.ofPattern("EEEE",Locale.GERMAN).format(tomorrow);
        CourseDraft draft=CourseDraft.read(screen("Yoga",weekday+" 08:15 - 09:45","VeniceBeach Grünstadt","Beschreibung","Im Studio teilnehmen"),tomorrow);
        try(var controller=Robolectric.buildActivity(MainActivity.class,draft.intent(context)).setup()) {
            assertTrue(new PlanStore(context).all().isEmpty());
            button(controller.get().getWindow().getDecorView(),"Plan speichern").performClick();
            assertEquals(1,new PlanStore(context).all().size());
            assertFalse(new PlanStore(context).all().get(0).automatic);
        }
    }
    @Test public void decorativeBananaAndFaceDoNotBecomeImportedNames() {
        var root=screen("Body & Mind","Pilates","😋","Mittwoch 10:00 - 11:00","🍌","VeniceBeach Grünstadt","Beschreibung","Im Studio teilnehmen");
        var draft=CourseDraft.read(root,null);
        assertEquals("Pilates",draft.course); assertEquals("VeniceBeach Grünstadt",draft.studio);
    }
    @Test public void privateUseIconGlyphsAreNotImportedAsNames() {
        var root=screen("Yoga","\ue192","Donnerstag 08:15 - 09:45","\uf3c5","VeniceBeach Grünstadt","Beschreibung","Im Studio teilnehmen");
        var draft=CourseDraft.read(root,null);
        assertEquals("Yoga",draft.course); assertEquals("VeniceBeach Grünstadt",draft.studio);
    }
    @Test public void imageDescriptionsCannotOverrideVisibleCourseAndStudioText() {
        var root=screen("Yoga","😛","08:15 - 09:45","🍌","VeniceBeach Grünstadt","Beschreibung","Im Studio teilnehmen");
        var clock=root.getChild(1); clock.setText(null); clock.setClassName("android.widget.ImageView"); clock.setContentDescription("Leckendes Gesicht");
        var location=root.getChild(3); location.setText(null); location.setClassName("android.widget.ImageView"); location.setContentDescription("Banane");
        var draft=CourseDraft.read(root,null);
        assertEquals("Yoga",draft.course); assertEquals("VeniceBeach Grünstadt",draft.studio);
    }
    @Test public void unreadableNamesLeaveEmptyFieldsInsteadOfEmojiGuesses() {
        var draft=CourseDraft.read(screen("😋","Mittwoch 10:00 - 11:00","🍌","Beschreibung","Im Studio teilnehmen"),null);
        assertEquals("",draft.course); assertEquals("",draft.studio); assertEquals(LocalTime.of(10,0),draft.time);
    }
    @Test public void meaningfulNameKeepsInternationalNamesAndActualTitleDecoration() {
        assertTrue(CourseDraft.meaningfulName("Yoga 🧘")); assertTrue(CourseDraft.meaningfulName("Étirements"));
        assertFalse(CourseDraft.meaningfulName("🍌")); assertFalse(CourseDraft.meaningfulName("😛"));
    }

}
