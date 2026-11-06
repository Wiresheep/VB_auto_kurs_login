# Kursfenster – Kursplaner für VeniceBeach

Android-App zur Planung von Kursanmeldungen in
[VeniceBeach Fitness](https://play.google.com/store/apps/details?id=com.actinate.venicebeach)
(`com.actinate.venicebeach`). Unabhängiges Projekt, keine offizielle VeniceBeach-App.

**Stand 0.14: Kursplanung mit automatischer Anmeldung und Erinnerungen.**
Die Startseite zeigt die Kursauswahl, deine geplanten Kurse und verständliche Anmeldestatus.
Einrichtung, Kalenderpositionen und weiterführende Hinweise liegen unter **„Einstellungen & Hilfe“**.
Technische Diagnosen sind bei Bedarf in den Kursdetails verfügbar.
Die Kursübernahme überspringt dekorative Emojis, Symbolschriftzeichen und Bildbeschreibungen.
Nicht lesbare Namen bleiben leer und müssen ergänzt werden; die erkannten Zeitangaben bleiben erhalten.
Die APK liegt unter `releases/kursfenster-0.14.0-debug.apk`. Die installierte Version steht im
Seitenfuß sowie unter Einstellungen und wird automatisch in die Diagnose übernommen.
Die Bedienung ist automatisiert und anhand nativer Android-Vorschauen geprüft; die neue
Übernahme muss mit der tatsächlichen VeniceBeach-Oberfläche auf dem Gerät bestätigt werden.


## Android-App herunterladen

Die aktuelle installierbare Version ist **Kursfenster 0.14.0**:

- [APK herunterladen](downloads/kursfenster-0.14.0-debug.apk)
- [SHA-256-Prüfsumme](downloads/kursfenster-0.14.0-debug.apk.sha256)

Auf GitHub die APK-Datei öffnen und über **Download raw file** herunterladen.
Es handelt sich um die bisher getestete Debug-APK für Android, nicht um eine iOS-App.

## Definierter Start vor jeder Anmeldung

Vor jedem automatischen oder ausdrücklich gestarteten Buchungsversuch wird VeniceBeach über
seine Startaktivität mit `NEW_TASK | CLEAR_TASK` geöffnet. Android baut damit den App-Task und
seine Bildschirmhistorie neu auf. Das ist kein „Stopp erzwingen“ des Prozesses und löscht keine
App-Daten. Eine vom Anbieter selbst gespeicherte Navigation kann trotzdem wiederhergestellt
werden; deshalb folgt eine zusätzliche Vorbereitung der Oberfläche.

Die Assistenz wartet auf die Hauptnavigation mit Community, Mein Studio und Training/Mein Profil.
Sie wählt zunächst Community und beginnt erst danach den bisherigen Weg „Mein Studio → MEHR“.
Fehlt die Hauptnavigation, werden bis zu sechs offene Unterseiten mit „Zurück“ verlassen.
Zu Beginn gibt es eine Ladefrist; leere Startseiten, Branding und Ladeanzeigen werden nicht
vorschnell mit „Zurück“ geschlossen. Bei einer erkannten Login-Seite oder nicht erreichbarer
Hauptnavigation hält der Versuch an, bevor Kursdatum oder Buchungsbutton angeklickt werden.
Die Navigation greift ausschließlich auf das im Vordergrund erkannte VeniceBeach-Fenster zu.

Dieser Einstieg gilt auch für jeden weiteren Kurs in der Warteschlange und nach dem Entsperren.
Das normale Öffnen von VeniceBeach, die Kursübernahme und die Kalendereinrichtung verwenden
weiterhin den vorhandenen App-Task, damit manuelle Auswahlen erhalten bleiben. Nach einem Update
bitte insbesondere einen Buchungsstart aus zuvor geöffneten Kursdetails auf dem S10+ testen.

## Gesperrtes Handy und ungestörte Nutzung

Die Bildschirmbedienung benötigt einen entsperrten, eingeschalteten Bildschirm und VeniceBeach
im Vordergrund. Eine sichere Bildschirmsperre wird nicht umgangen. Unsichtbares Buchen, während
du eine andere App bedienst, ist mit dieser Integration nicht möglich; dafür wäre eine geeignete
Buchungsschnittstelle erforderlich.

Unter **„Einstellungen & Hilfe → Entsperren & App-Wechsel“** stehen zwei unabhängige Schalter:

- **Beim Entsperren nachholen** ist zunächst aus. Eingeschaltet: Ist das Handy zum Anmeldestart
  gesperrt oder der Bildschirm aus, bleibt der automatische Plan dauerhaft als „Wartet aufs
  Entsperren“ gespeichert. Nach dem Entsperren wird er erneut geprüft und kann gestartet werden.
- **VeniceBeach automatisch öffnen** ist zunächst an und erhält das bisherige Verhalten.
  Eingeschaltet darf VeniceBeach zur Anmeldung in den Vordergrund wechseln, auch direkt nach
  dem Entsperren. Ausgeschaltet bleiben andere Apps ungestört. Der Plan zeigt „Bereit zur
  Anmeldung“ und eine Benachrichtigung mit **„Jetzt anmelden“**. Erst diese Aktion bzw. der
  gleichnamige Button im Kursplan öffnet VeniceBeach für die sichtbare Buchung.

Für automatisches Nachholen beide Schalter einschalten. Für ungestörte Nutzung das automatische
Öffnen ausschalten und die Anmeldung zu einem passenden Zeitpunkt selbst starten. Die zweite
Variante ist kein unsichtbarer Hintergrundmodus; während der eigentlichen Buchung benötigt
VeniceBeach den Bildschirm. Ein bereits geöffnetes oder gesendetes Buchungsverfahren wird
nicht als neue Anmeldung wiederholt.

Wartende Pläne bleiben nach Prozessneustart und Boot erhalten. Das Entsperr-Ereignis wird vom
aktiven Buchungshelfer empfangen; beim erneuten Verbinden werden wartende Pläne ebenfalls geprüft.
Ein bloßes Einschalten des noch gesperrten Bildschirms startet keine Buchung. Bereits begonnene
Kurse werden verworfen. Bei mehreren wartenden Kursen wird nacheinander gebucht. Der Wechsel
auf einen manuellen Start pausiert noch nicht abgesendete Versuche; ein bereits gesendeter Klick
bleibt zur Prüfung markiert und wird beim Entsperren niemals erneut ausgeführt.

Der Buchungshelfer muss aktiviert bleiben. „Stopp erzwingen“, ein ausgeschaltetes Gerät oder
herstellerspezifische Energiesparregeln können Android-Ereignisse verhindern. Ohne erreichbaren
Buchungshelfer sind „Jetzt anmelden“ und das Öffnen von Kursfenster die Rückfallebene. Der Ablauf
ist unter simuliertem Android 12 geprüft und muss zusätzlich auf dem S10+ getestet werden.

## Aufbau der App

- **Kurs in VeniceBeach auswählen:** Kursdetails öffnen und in der eingeblendeten Leiste übernehmen.
- **Kurs manuell eintragen:** Öffnet das Formular für Kursname, Studio, Datum und Beginn.
- **Deine Kurse:** Zeigt „Automatische Anmeldung geplant“, „Anmeldung läuft“, „Angemeldet“ oder
  „Anmeldung bitte prüfen“. Ausführliche technische Zwischenschritte stehen nicht auf der Startseite.
- **Details → Angaben bearbeiten:** Noch geplante Kurse ändern; derselbe Plan und seine Alarm-ID
  bleiben erhalten. Laufende oder bereits abgesendete Anmeldungen werden dabei nicht neu gestartet.
  Alte Symbol-Einträge lassen sich über „Angaben ergänzen“ korrigieren.
- **Einstellungen & Hilfe:** Buchungshelfer, Alarme, Benachrichtigungen und Kalenderleiste einrichten.

Die Einstellung für die automatische Anmeldung und das zuletzt gespeicherte Studio werden für
weitere Eingaben gemerkt. Bei aktiviertem Buchungshelfer ist die automatische Anmeldung zunächst
vorbelegt. Vor dem Speichern zeigt das Formular, wann die Anmeldung startet. Bei bereits geöffnetem
Anmeldefenster zeigt der Hinweis je nach Einstellung den sofortigen oder manuellen Start an. Die Übernahme allein
speichert und bucht weiterhin nichts.

## Einen Kurs direkt aus VeniceBeach übernehmen

1. In Kursfenster **„Kurs in VeniceBeach auswählen“** wählen.
2. In VeniceBeach den gewünschten Tag und Kurs öffnen. Die Kursdetailseite mit Name,
   Startzeit und Studio sichtbar lassen.
3. Auf den eingeblendeten Button **„Kurs übernehmen“** tippen. „Abbrechen“ beendet den Modus.
   Außerhalb dieser kleinen Leiste lässt sich VeniceBeach wie gewohnt bedienen.
4. Zurück in Kursfenster die übernommenen Felder prüfen. Ein vollständiges Datum oder ein
   erfasster, zum Wochentag passender Kalendertag wird übernommen. Kann das Datum nicht sicher
   bestimmt werden, muss es vor dem Speichern ausdrücklich ausgewählt werden.
5. Die Einstellung **„Automatisch anmelden“** prüfen und **„Plan speichern“** drücken. Die Übernahme selbst
   speichert keinen Plan und klickt nicht auf „Im Studio teilnehmen“.

Die Auswahl liest nur die VeniceBeach-Oberfläche. Mehrdeutige Zeitangaben, etwa ein aufgeklappter
Wochenplan mit mehreren Zeiten, werden zurückgewiesen. Bei unlesbaren Feldern bleiben die
manuellen Eingabefelder verfügbar. Der Modus ist während einer Buchung oder Kalibrierung gesperrt
und endet bei Abbruch, Gerätesperre oder nach fünf Minuten.

## Erster Test auf dem Samsung Galaxy S10+ mit Android 12

1. Debug-APK als Update installieren. In VeniceBeach anmelden und die gewünschten Studios
   selbst auswählen. Kursfenster verändert die Studioauswahl nicht.
2. Unter **„Einstellungen & Hilfe“** den Buchungshelfer einrichten und zeitgenaue Alarme erlauben.
   Nach diesem Update die Bedienungshilfe einmal aus- und wieder einschalten, damit Android
   die zusätzliche Kalenderbild-Prüfung erlaubt. Auf Android 12 wird sie über die bereits
   aktivierte Bedienungshilfe ausgeführt; eine zusätzliche Aufnahme-App wird nicht benötigt.
3. Unter **„Einstellungen & Hilfe“** die **„Kalenderleiste einrichten“** wählen. Die Assistenz öffnet VeniceBeach und den Kurskalender.
   Es darf gerade kein Buchungsversuch laufen.
4. Auf dem eingeblendeten Hinweis zuerst die Mitte des heutigen Tagesreiters antippen,
   anschließend die Mitte des morgigen Tagesreiters. Das Datum steht jeweils im Hinweis.
   Die Einrichtung fängt diese beiden Eingaben ab: Sie ändern den Kalendertag nicht und buchen nichts.
   Durch Antippen des unteren Hinweisbereichs lässt sich die Einrichtung abbrechen.
5. Zu Kursfenster zurückkehren. Einen tatsächlich gewünschten Kurs mit exaktem Kursnamen,
   Datum, Startzeit und Studio-Namen speichern und die Automatik einschalten.
   Noch geplante Kurse können über „Details → Angaben bearbeiten“ angepasst werden. Ein bereits
   angehaltener Versuch wird weiterhin nicht automatisch wiederholt.
6. Liegt der Kursbeginn innerhalb der nächsten 24 Stunden, ist das Anmeldefenster bereits offen.
   Bei erlaubtem automatischem Öffnen startet die Anmeldung nach dem Speichern; bei gesperrtem
   Handy kann sie mit der Nachhol-Option warten. Bei ausgeschaltetem automatischem Öffnen wird
   der Plan zum manuellen Start bereitgestellt. Für spätere Kurse wird zunächst der Beginn
   des Anmeldefensters geplant.
7. Die Assistenz wartet nach dem Buchungsklick bis zu 15 Sekunden auf „Vom Kurs abmelden“.
   Erst dann erscheint in Kursfenster „Anmeldung bestätigt“. Nach der letzten Bestätigung
   kehrt sie zum Startbildschirm zurück. Fehlt die Bestätigung, bleibt die Anmeldung als
   zu prüfen markiert und VeniceBeach wird nicht automatisch verlassen.

Die Positionen werden lokal gespeichert. Sie gelten für die eingerichtete Bildschirmgröße,
Ausrichtung, Dichte und Schriftgröße. Bei geänderter Bildschirmkonfiguration werden sie nicht
verwendet. Ändert sich die Anordnung innerhalb von VeniceBeach, die Kalenderleiste neu einrichten.
„Kalendereinrichtung löschen“ entfernt die Positionen. Die Einrichtung selbst nimmt keine Bilder auf.
Der sichtbare Markerkreis wird in lokalen Fensterkoordinaten gezeichnet, während die Tipp-Punkte
weiterhin in Bildschirmkoordinaten gespeichert werden. Dadurch entfällt der sichtbare Versatz
unterhalb der angetippten Stelle. Vorhandene Punkte müssen deshalb nicht automatisch gelöscht werden;
die Kalibrierung lässt sich nach dem Update visuell prüfen oder erneut durchführen.

Samsung kann Hintergrundarbeit einschränken. Unter Einstellungen → Apps → Kursfenster → Akku
die Hintergrundnutzung erlauben und Kursfenster sowie VeniceBeach gegebenenfalls aus „Apps im
Standby“ entfernen. Die Menüs können je nach One-UI-Version abweichen.

## Buchungsablauf

- VeniceBeach mit neuem App-Task starten, die Hauptnavigation erreichen und Community öffnen.
- Danach „Mein Studio“ öffnen und „MEHR“ drücken. Ein alter Kalender überspringt die Startvorbereitung nicht.
- Den vom Benutzer eingestellten Studiofilter unverändert übernehmen.
- Das Zieldatum aus dem Kursplan bestimmen. Ein bereits markierter Zieltag wird übernommen.
  Bei eingerichteten Kalenderpositionen wird auf die Position für heute bzw. morgen getippt.
  Ohne Einrichtung werden vollständige Datumsfelder oder eindeutige Reiter wie „DO. 05“
  über die Bedienungshilfe gesucht. Wochentag und Zahl dürfen getrennte Elemente sein.
- Auf den Abschluss der Tippgeste und die Bestätigung der Zielmarkierung warten. Eine eindeutige
  Android-Auswahlmarkierung bestätigt den Tag. Fehlt sie, wird auf Android 11 oder neuer kurz
  ein Kalenderbild im Arbeitsspeicher geprüft: Innerhalb des erkannten bzw. eingerichteten
  Tagesfelds müssen der schwarze Auswahlhintergrund und die cyanfarbene Schrift der gezeigten
  VeniceBeach-Darstellung vorliegen. Bloßer Zeitablauf oder sichtbare Kurszeiten genügen nicht.
- Bleibt die Zielmarkierung aus, darf ein bislang nur regulär angeklickter Reiter einmal per
  Tippgeste bedient werden. Auch danach muss die Markierung bestätigt werden. Bei fehlender
  Bestätigung, nicht verfügbarer Aufnahme oder unbekannter Position wird ohne Scrollen angehalten.
  Für andere Farbgestaltungen ist diese visuelle Prüfung nicht validiert.
- Sichtbare Kurskarten nach Kursname und Startzeit durchsuchen und die vertikale Kursliste
  nach unten scrollen, bis ein eindeutiger Treffer oder das Ende erreicht ist.
- Den Kurs öffnen. Auf der Detailseite Kursname, Studio, Startzeit und vollständiges Datum
  oder passenden Wochentag mit dem gewählten Kalendertag abgleichen.
- Einmal auf „Im Studio teilnehmen“ drücken und auf „Vom Kurs abmelden“ warten. Dabei werden
  Kursname, Studio und Zeit erneut geprüft. Der Abmeldebutton wird niemals betätigt.
- Bei Bestätigung die Anmeldung speichern. Weitere fällige Pläne werden zuerst abgearbeitet;
  nach der letzten bestätigten Anmeldung zum Android-Startbildschirm zurückkehren. Dabei muss
  VeniceBeach noch im Vordergrund sein; eine andere geöffnete App wird nicht verlassen.

Der Studio-Name im Plan dient zur Prüfung auf der Detailseite. Gleichnamige Kurse zur selben
Uhrzeit werden bei mehrdeutigen sichtbaren Treffern nicht ausgewählt. Die Automatik stoppt
am Listenende, bei ausbleibendem Scrollfortschritt, nach 30 Scrollschritten oder spätestens
nach 60 Sekunden Suche. Für eine bereits abgesendete Anmeldung folgen höchstens 15 Sekunden
Bestätigungsprüfung. Kalenderleisten werden nicht horizontal gescrollt. Erforderliche zugängliche
Kursdaten und Scrollaktionen müssen von VeniceBeach bereitgestellt werden.

Wartelisten und zusätzliche Bestätigungsdialoge werden nicht automatisch akzeptiert.
Als Bestätigung wird die vom Nutzer beschriebene Beschriftung „Vom Kurs abmelden“ verwendet.
Bleibt sie aus oder wird der Versuch unterbrochen, wird ein bereits gesendeter Klick weiterhin
als unbestätigt gespeichert und nicht automatisch wiederholt. Die App speichert keine Zugangsdaten und umgeht keine Gerätesperre. Für die Tagesprüfung wird bei Bedarf kurz ein
Bild des geöffneten Kalenders über Androids Bedienungshilfe aufgenommen. Es wird nur das
bekannte Tagesfeld ausgewertet, anschließend werden Bild und Hardware-Puffer freigegeben.
Bilder werden weder gespeichert noch übertragen. Die Prüfung startet nur im VeniceBeach-Kalender;
vor der Auswertung werden Vordergrund-App und Bildschirmzustand nochmals geprüft.

## Diagnose bei einem Stillstand

Nach spätestens 75 Sekunden zu Kursfenster zurückkehren. Beim betreffenden Kurs zeigt
**„Details → Technische Details anzeigen“** die letzte Aktion bzw. die fehlgeschlagene Prüfung.
Mit **„Diagnose kopieren“** kann dieser Text hier im Chat eingefügt werden. Die Diagnose bleibt nach einem Abbruch erhalten
und wird mit dem Plan gelöscht. Sie enthält keinen vollständigen Bildschirmbaum und wird
nicht automatisch übertragen.

## Terminplanung und Grenzen

- Mehrere lokale Kurspläne; gleichzeitig fällige Versuche werden nacheinander bearbeitet.
- Anmeldung ab Kursbeginn minus 24 echte Stunden, auch über Zeitumstellungen hinweg.
- Studio-Zeitzone Europe/Berlin. Nicht existierende Frühlingsuhrzeiten werden abgewiesen;
  bei der doppelten Herbststunde gilt das erste Vorkommen.
- Wiederherstellung von Erinnerungen nach Neustart, Zeitänderung oder App-Update.
- Hinweise bei fehlenden Alarm- und Benachrichtigungsberechtigungen.

Android kann Alarme bei ausgeschaltetem Gerät, nach „Stopp erzwingen“ oder durch Energiesparregeln
unterdrücken. Nach „Stopp erzwingen“ die App neu öffnen. Auch exakte Alarme garantieren keine
sekundengenaue Ausführung. Nach einem bereits gesendeten Buchungsklick erfolgt keine automatische Wiederholung. Nur bekannte,
noch nicht abgesendete Versuche können mit eingeschaltetem Entsperr-Nachholen fortgesetzt werden.
Das Entfernen eines Plans storniert keine bereits erfolgte Anmeldung in VeniceBeach.

## Bauen und prüfen

Android Studio, JDK 17 oder neuer, Android SDK 35 und Build Tools 35.0.0 verwenden.
SDK-Pfad über ANDROID_HOME oder eine nicht versionierte local.properties angeben.

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Minimum Android 8 (API 26), Zielversion Android 15 (API 35).
APK: `app/build/outputs/apk/debug/app-debug.apk`.
Die zusätzliche Kopie `releases/kursfenster-0.14.0-debug.apk` trägt die Versionsnummer im Dateinamen.

In dieser Cloud-Arbeitskopie:

```sh
source /workspace/.android-tools/env.sh
cd /workspace/VB_auto_kurs_login
./gradlew --offline --no-daemon assembleDebug testDebugUnitTest lintDebug
```

Für eine neue Cloud-Umgebung stehen `scripts/setup-cloud.sh` und die zugehörigen
Bootstrap-Skripte bereit. Sie installieren unter `/workspace/.android-tools`, prüfen Downloads
gegen Hersteller-Prüfsummen, übernehmen Proxy und CA-Vertrauen und nehmen SDK-Lizenzen an.
TLS-Prüfungen bleiben aktiv. Keine Server oder echten Buchungen sind für den Build erforderlich.

JUnit prüft Terminlogik und Zeitumstellungen. Robolectric prüft Oberfläche, Speicherung,
Alarmplanung, Warteschlange, Datumswahl, Tippgesten, Kalendereinrichtung einschließlich
Fenster-Versatz, Kursübernahme ins Formular und die Suche über mehrere Listenabschnitte bis
zur Anmeldung. Weitere Prüfungen decken die echte Bestätigungsbeschriftung, ausbleibende
Bestätigung, das Zurückkehren zum Startbildschirm erst nach der letzten Anmeldung, dekorative
Emoji-/Bildbeschriftungen und das Bearbeiten eines Plans ohne zusätzliche Alarme ab.
Native Android-Vorschauen liegen nach dem Test unter `app/build/reports/ui-preview/`. Diese synthetischen Bedienungshilfe-Bäume
ersetzen keinen erfolgreichen Gerätetest mit der tatsächlichen VeniceBeach-Oberfläche.

Die Tests decken außerdem echte Entsperr-Broadcasts unter simuliertem Android 12, gesperrtes
Screen-On, den Benachrichtigungsstart, deaktivierte App-Wechsel, Ablauf vor Entsperren,
Wiederherstellung und verspätete Gesten-Rückmeldungen nach erneutem Start desselben Plans ab.

Zusätzliche Starttests prüfen die Task-Flags, den Rückweg aus Kalender und Kursdetails,
die Hauptnavigation vor der Buchung, begrenzte Rückschritte, Login-/Ladebildschirme,
den Schutz anderer Vordergrund-Apps und den frischen Einstieg für weitere wartende Kurse.
