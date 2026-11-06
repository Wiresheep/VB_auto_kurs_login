package de.wiresheep.kursplaner;

import android.view.accessibility.AccessibilityNodeInfo;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import static org.robolectric.util.ReflectionHelpers.ClassParameter.from;

/** Synthetic accessibility trees verify that ambiguous courses cannot be submitted. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class BookingMatchingTest {
    private AccessibilityNodeInfo node(String text, boolean clickable) {
        AccessibilityNodeInfo n = AccessibilityNodeInfo.obtain();
        n.setText(text); n.setVisibleToUser(true); n.setEnabled(true); n.setClickable(clickable);
        shadowOf(n).setOnPerformActionListener((action, args) -> true);
        return n;
    }
    private void child(AccessibilityNodeInfo parent, AccessibilityNodeInfo child) {
        shadowOf(parent).addChild(child);
    }
    private AccessibilityNodeInfo row(String name, String time) {
        AccessibilityNodeInfo row = node("", true);
        child(row, node(name, false)); child(row, node(time, false)); return row;
    }
    private AccessibilityNodeInfo findRow(AccessibilityNodeInfo root, String name, String time) {
        return ReflectionHelpers.callStaticMethod(BookingAccessibilityService.class, "findCourseRow",
                from(AccessibilityNodeInfo.class, root), from(String.class, name), from(String.class, time));
    }
    private boolean click(AccessibilityNodeInfo root, String label) {
        return ReflectionHelpers.callStaticMethod(BookingAccessibilityService.class, "clickUnique",
                from(AccessibilityNodeInfo.class, root), from(String.class, label));
    }
    @Test public void sameCourseAtDifferentTimesSelectsOnlyRequestedSession() {
        AccessibilityNodeInfo root = node("", false), wanted = row("Yoga", "18:00");
        child(root, row("Yoga", "17:00")); child(root, wanted);
        assertEquals(wanted, findRow(root, "Yoga", "18:00"));
    }
    @Test public void sameNameAndTimeTwiceIsAmbiguous() {
        AccessibilityNodeInfo root = node("", false);
        child(root, row("Yoga", "18:00")); child(root, row("Yoga", "18:00"));
        assertNull(findRow(root, "Yoga", "18:00"));
    }
    @Test public void timeFromAnotherCardCannotQualifyCourse() {
        AccessibilityNodeInfo root = node("", false); root.setScrollable(true);
        child(root, row("Yoga", "17:00")); child(root, row("Pilates", "18:00"));
        assertNull(findRow(root, "Yoga", "18:00"));
    }
    @Test public void bookingButtonMustMatchExactlyAndBeEnabled() {
        AccessibilityNodeInfo root = node("", false), button = node("Im Studio teilnehmen", true);
        child(root, button);
        assertFalse(click(root, "teilnehmen"));
        button.setEnabled(false); assertFalse(click(root, "Im Studio teilnehmen"));
        button.setEnabled(true); assertTrue(click(root, "Im Studio teilnehmen"));
        assertEquals(1, shadowOf(button).getPerformedActions().size());
    }
    @Test public void duplicateBookingButtonsAreNeverClicked() {
        AccessibilityNodeInfo root = node("", false), first = node("Im Studio teilnehmen", true);
        child(root, first); child(root, node("Im Studio teilnehmen", true));
        assertFalse(click(root, "Im Studio teilnehmen"));
        assertTrue(shadowOf(first).getPerformedActions().isEmpty());
    }
    @Test public void screenshotTimeRangesMatchStartTimeOnly() {
        AccessibilityNodeInfo root=node("",false), wanted=row("Pilates","10:00 – 11:00");
        child(root,wanted);
        assertEquals(wanted,findRow(root,"Pilates","10:00"));
        assertNull(findRow(root,"Pilates","11:00"));
    }
    @Test public void repeatedLabelsOnSameButtonAreOneClickTarget() {
        AccessibilityNodeInfo label=node("MEHR",false), button=node("MEHR",true);
        child(button,label); assertTrue(click(button,"MEHR"));
        assertEquals(1,shadowOf(button).getPerformedActions().size());
    }

}
