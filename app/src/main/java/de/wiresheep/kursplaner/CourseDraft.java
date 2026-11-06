package de.wiresheep.kursplaner;

import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;

/** A reviewable form draft. Importing never saves a plan or submits a booking. */
final class CourseDraft {
    static final String ACTION="de.wiresheep.kursplaner.IMPORT_COURSE";
    final String course,studio;
    final LocalTime time;
    final LocalDate date;
    private CourseDraft(String course,String studio,LocalTime time,LocalDate date) {
        this.course=course; this.studio=studio; this.time=time; this.date=date;
    }
    private static final Pattern RANGE=Pattern.compile("(?i)^(?:(Montag|Dienstag|Mittwoch|Donnerstag|Freitag|Samstag|Sonntag)[, ]+)?(\\d{1,2}:\\d{2})\\s*[-–—]\\s*\\d{1,2}:\\d{2}$");
    private static final List<String> WEEKDAYS=Arrays.asList("montag","dienstag","mittwoch","donnerstag","freitag","samstag","sonntag");
    private static final class Label {
        final String text; final Rect bounds;
        Label(String text,Rect bounds) { this.text=text; this.bounds=bounds; }
    }
    static CourseDraft read(AccessibilityNodeInfo root,LocalDate calendarDate) {
        if(root==null || !VeniceBeach.PACKAGE.contentEquals(root.getPackageName()==null ? "" : root.getPackageName()))
            throw new IllegalArgumentException("Bitte die Kursdetailseite in VeniceBeach öffnen.");
        List<Label> labels=new ArrayList<>(); collect(root,labels,0);
        if(labels.stream().noneMatch(l -> l.text.equalsIgnoreCase("Im Studio teilnehmen") || l.text.equalsIgnoreCase("Vom Kurs abmelden")))
            throw new IllegalArgumentException("Öffne zuerst den gewünschten Kurs und drücke dann Kurs übernehmen.");
        if(labels.stream().allMatch(l -> !l.bounds.isEmpty())) labels.sort(Comparator
                .comparingInt((Label l) -> l.bounds.top).thenComparingInt(l -> l.bounds.left));
        int timeIndex=-1; Matcher match=null;
        for(int i=0;i<labels.size();i++) {
            Matcher candidate=RANGE.matcher(labels.get(i).text);
            if(candidate.matches()) {
                if(timeIndex!=-1) throw new IllegalArgumentException("Mehrere Kurszeiten erkannt. Bitte Wochenplan schließen und erneut übernehmen.");
                timeIndex=i; match=candidate;
            }
        }
        if(match==null) throw new IllegalArgumentException("Die Startzeit konnte nicht gelesen werden. Bitte die Kursdetails sichtbar lassen.");
        String name=null,studio=null,weekday=match.group(1);
        for(int i=timeIndex-1;i>=0;i--) {
            String text=labels.get(i).text;
            if(WEEKDAYS.contains(text.toLowerCase(Locale.GERMAN))) { if(weekday==null) weekday=text; continue; }
            if(usable(text)) { name=text; break; }
        }
        for(int i=timeIndex+1;i<labels.size();i++) {
            String text=labels.get(i).text;
            if(text.equalsIgnoreCase("Beschreibung")) break;
            if(usable(text)) { studio=text; break; }
        }
        if(name==null) name="";
        if(studio==null) studio="";
        if(!name.isEmpty() && name.equals(studio)) { name=""; studio=""; }
        LocalDate date=null;
        for(Label label:labels) { LocalDate full=parseDate(label.text); if(full!=null) { date=full; break; } }
        if(date==null) date=calendarDate;
        if(date!=null && weekday!=null && date.getDayOfWeek().getValue()!=WEEKDAYS.indexOf(weekday.toLowerCase(Locale.GERMAN))+1) date=null;
        return new CourseDraft(name,studio,LocalTime.parse(match.group(2),DateTimeFormatter.ofPattern("H:mm")),date);
    }
    private static boolean usable(String text) {
        String lower=text.toLowerCase(Locale.GERMAN);
        return meaningfulName(text) && text.length()<=120 && !text.matches("\\d+\\s*/\\s*\\d+") && parseDate(text)==null
                && !WEEKDAYS.contains(lower) && !Arrays.asList("kurse","kurs","kr","standort","ort","uhr","uhrzeit","studio","beschreibung",
                "body & mind","kraft & workout","mehr","teilnehmer","im studio teilnehmen","vom kurs abmelden").contains(lower);
    }
    static boolean meaningfulName(String text) {
        return text!=null && text.codePoints().anyMatch(Character::isLetter);
    }
    private static LocalDate parseDate(String text) {
        for(String format:Arrays.asList("d.M.yyyy","EEEE, d. MMMM yyyy","d. MMMM yyyy")) {
            try { return LocalDate.parse(text,DateTimeFormatter.ofPattern(format,Locale.GERMAN)); }
            catch(java.time.format.DateTimeParseException ignored) { }
        }
        return null;
    }
    private static void collect(AccessibilityNodeInfo node,List<Label> labels,int depth) {
        if(node==null || depth>35) return;
        String role=node.getClassName()==null ? "" : node.getClassName().toString();
        boolean image=role.endsWith("ImageView") || role.endsWith("ImageButton");
        CharSequence value=image ? null : node.getText(); if(!image && (value==null || value.toString().trim().isEmpty())) value=node.getContentDescription();
        if(node.isVisibleToUser() && value!=null) {
            String text=value.toString().replace('\u00a0',' ').trim().replaceAll("\\s+"," ");
            if(!text.isEmpty() && labels.stream().noneMatch(l -> l.text.equals(text))) {
                Rect bounds=new Rect(); node.getBoundsInScreen(bounds); labels.add(new Label(text,bounds));
            }
        }
        for(int i=0;i<node.getChildCount();i++) collect(node.getChild(i),labels,depth+1);
    }
    Intent intent(Context context) {
        Intent intent=new Intent(context,MainActivity.class).setAction(ACTION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("draft_course",course).putExtra("draft_studio",studio).putExtra("draft_time",time.toString());
        if(date!=null) intent.putExtra("draft_date",date.toString());
        return intent;
    }
}
