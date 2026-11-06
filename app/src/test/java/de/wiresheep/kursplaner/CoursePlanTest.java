package de.wiresheep.kursplaner;
import org.junit.Test;
import java.time.*;
import static org.junit.Assert.*;
public class CoursePlanTest {
    private static final Instant NOW=Instant.parse("2026-01-01T00:00:00Z");
    @Test public void ordinaryCourseOpensExactly24HoursBefore() {
        CoursePlan p=CoursePlan.create(" Yoga "," Mannheim ",LocalDate.of(2026,2,3),LocalTime.of(18,0),NOW);
        assertEquals(Instant.parse("2026-02-02T17:00:00Z"),p.opensAt());
        assertEquals("Yoga",p.course); assertEquals("Mannheim",p.studio);
    }
    @Test public void springTransitionUsesElapsedHours() {
        CoursePlan p=CoursePlan.create("Yoga","Studio",LocalDate.of(2026,3,29),LocalTime.of(10,0),NOW);
        assertEquals(LocalTime.of(9,0),p.opensAt().atZone(CoursePlan.STUDIO_ZONE).toLocalTime());
        assertEquals(Duration.ofHours(24),Duration.between(p.opensAt(),p.startsAt));
    }
    @Test public void autumnTransitionUsesElapsedHours() {
        CoursePlan p=CoursePlan.create("Yoga","Studio",LocalDate.of(2026,10,25),LocalTime.of(10,0),NOW);
        assertEquals(LocalTime.of(11,0),p.opensAt().atZone(CoursePlan.STUDIO_ZONE).toLocalTime());
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsMissingSpringHour() {
        CoursePlan.create("Yoga","Studio",LocalDate.of(2026,3,29),LocalTime.of(2,30),NOW);
    }
    @Test public void autumnOverlapUsesFirstOccurrence() {
        CoursePlan p=CoursePlan.create("Yoga","Studio",LocalDate.of(2026,10,25),LocalTime.of(2,30),NOW);
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"),p.startsAt);
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsPastCourse() {
        CoursePlan.create("Yoga","Studio",LocalDate.of(2025,12,31),LocalTime.NOON,NOW);
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsEmptyCourse() {
        CoursePlan.create(" ","Studio",LocalDate.of(2026,2,3),LocalTime.NOON,NOW);
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsEmptyStudio() {
        CoursePlan.create("Yoga"," ",LocalDate.of(2026,2,3),LocalTime.NOON,NOW);
    }
    @Test public void within24HoursIsStillValid() {
        CoursePlan p=CoursePlan.create("Yoga","Studio",LocalDate.of(2026,1,1),LocalTime.of(10,0),NOW);
        assertTrue(p.opensAt().isBefore(NOW)); assertFalse(p.isExpired(NOW));
        assertTrue(p.isExpired(p.startsAt));
    }
}
