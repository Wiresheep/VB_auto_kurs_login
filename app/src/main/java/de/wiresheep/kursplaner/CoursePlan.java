package de.wiresheep.kursplaner;

import java.time.*;
import java.util.UUID;

/** A real elapsed 24 hours, including daylight saving time changes. */
public final class CoursePlan {
    public static final ZoneId STUDIO_ZONE = ZoneId.of("Europe/Berlin");
    public enum Status { PLANNED, REMINDED, EXPIRED, QUEUED, AUTOMATING, ACTION_REQUIRED, VERIFY_REQUIRED, BOOKED, WAITING_UNLOCK, WAITING_USER }
    public final String id, course, studio;
    public final Instant startsAt;
    public final Status status;
    public final boolean automatic;
    public final String webOccurrenceId;
    public final int webStudioId;

    public CoursePlan(String id, String course, String studio, Instant startsAt, Status status) {
        this(id, course, studio, startsAt, status, false);
    }
    public CoursePlan(String id, String course, String studio, Instant startsAt, Status status, boolean automatic) {
        this(id, course, studio, startsAt, status, automatic, null, 0);
    }
    public CoursePlan(String id, String course, String studio, Instant startsAt, Status status, boolean automatic, String occurrenceId, int studioId) {
        this.webOccurrenceId = occurrenceId;
        this.webStudioId = studioId;
        if (occurrenceId != null && (!occurrenceId.matches("[0-9]{1,20}") || studioId <= 0))
            throw new IllegalArgumentException("Ungültige Web-Kurskennung.");
        this.automatic = automatic;
        this.id = UUID.fromString(id).toString();
        this.course = required(course, "Bitte einen Kurs eintragen.");
        this.studio = required(studio, "Bitte ein Studio eintragen.");
        this.startsAt = startsAt.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        this.status = status;
    }
    public static CoursePlan create(String course, String studio, LocalDate date, LocalTime time, Instant now) {
        LocalDateTime local = LocalDateTime.of(date, time);
        if (STUDIO_ZONE.getRules().getValidOffsets(local).isEmpty())
            throw new IllegalArgumentException("Diese Uhrzeit existiert wegen der Zeitumstellung nicht.");
        Instant start = local.atZone(STUDIO_ZONE).toInstant();
        if (!start.isAfter(now)) throw new IllegalArgumentException("Der Kurs muss in der Zukunft liegen.");
        return new CoursePlan(UUID.randomUUID().toString(), course, studio, start, Status.PLANNED);
    }
    private static String required(String text, String error) {
        if (text == null || text.trim().isEmpty()) throw new IllegalArgumentException(error);
        if (text.trim().length() > 120) throw new IllegalArgumentException("Maximal 120 Zeichen pro Feld.");
        return text.trim();
    }
    public CoursePlan withAutomatic(boolean enabled) { return new CoursePlan(id, course, studio, startsAt, status, enabled, webOccurrenceId, webStudioId); }
    public boolean isWeb() { return webOccurrenceId != null; }
    public CoursePlan withWebOccurrence(String id, int studio) {
        return new CoursePlan(this.id, course, this.studio, startsAt, status, automatic, id, studio);
    }
    public Instant opensAt() { return startsAt.minus(Duration.ofHours(24)); }
    public boolean isExpired(Instant now) { return !startsAt.isAfter(now); }
    public CoursePlan withStatus(Status next) { return new CoursePlan(id, course, studio, startsAt, next, automatic, webOccurrenceId, webStudioId); }
}
