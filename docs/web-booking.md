# Webbuchung in Kursfenster 0.15.2

Stand: 6. November 2026. Die Webintegration ist implementiert und mit simulierten
Anbieterrückmeldungen getestet. Ein erfolgreicher Login und eine echte Buchung
mit einem Mitgliedskonto stehen noch aus. Die öffentliche Studioliste (derzeit
39 Standorte) sowie Kursabfragen für Grünstadt und Frankenthal wurden live
erfolgreich abgerufen.

## Auf dem Smartphone testen

Die separat installierbare APK heißt **Kursfenster Webtest** und verwendet
`de.wiresheep.kursplaner.webpreview`. Sie benötigt keinen eigenen Server und
kann neben Kursfenster 0.14.2 installiert werden. Das Update von Webtest 0.15.0 oder 0.15.1
behält dessen Zugangsdaten und Pläne. Die Daten der beiden Apps sind
getrennt; vorhandene Pläne werden nicht übernommen.

Der Signierschlüssel der bisherigen APK ist in dieser Umgebung nicht vorhanden.
Die neue Debug-Signatur stimmt nicht mit 0.14.2 überein. Deshalb wird eine
separate Testinstallation bereitgestellt. Für ein späteres Update unter der
ursprünglichen Paketkennung wird der ursprüngliche Signierschlüssel benötigt.
Die bisherige App zum Testen nicht deinstallieren.

1. `downloads/kursfenster-0.15.2-webtest-debug.apk` installieren.
2. **Kurs im Web auswählen (auch bei Displaysperre)** öffnen, **Webzugang &
   Einstellungen** und anschließend **Webzugang einrichten** wählen. E-Mail und Passwort der VeniceBeach-App eingeben und
   **Prüfen & speichern** wählen. Der Login prüft auch die Berechtigung für
   Studiokurse. Anmeldedaten nicht in Chat oder Diagnose einfügen.
3. Unter **Webzugang & Einstellungen** zeitgenaue Alarme und Benachrichtigungen
   erlauben. Auf dem Zielgerät die
   Hintergrundnutzung für Kursfenster Webtest erlauben.
4. Im Bereich **Studio** auf den angezeigten Studionamen tippen. Die
   verfügbaren Studios werden vom Anbieter geladen und alphabetisch angeboten.
   Gewünschtes Studio und Tag auswählen, **Kurse laden**, einen tatsächlich
   gewünschten Kurs wählen
   und den angezeigten Anmeldezeitpunkt bestätigen. Es werden nur Präsenzkurse
   im ausgewählten Studio mit einem 24-Stunden-Anmeldefenster angeboten. Das bloße Laden
   und Auswählen bucht nichts. Speichern innerhalb des offenen Fensters führt
   dagegen unmittelbar zum automatischen Versuch.
5. Display sperren. Zum Anmeldezeitpunkt soll eine kurze Benachrichtigung über
   die laufende Webbuchung erscheinen. Nach dem Test den Status unter
   **Deine Kurse** und die tatsächliche Teilnahme im VeniceBeach-Kursplan prüfen.
   Einen zukünftigen Plan zusätzlich über Nacht im Geräteleerlauf/Doze testen.
6. Bei **Anmeldung bitte prüfen** die Kursdetails öffnen. **Teilnahme im Web
   prüfen** liest ausschließlich den Status. **Webanmeldung erneut versuchen**
   steht nur nach einem Fehler vor dem Absenden zur Verfügung.

## Überarbeitete Oberfläche

Version 0.15.2 nutzt die ruhigen Grüntöne der Kursfenster-Startseite, abgerundete
Karten und größere Abstände in dp. Studio, Datum und die hervorgehobene Aktion
**Kurse laden** stehen gemeinsam in einer Auswahlkarte. Kurskarten zeigen
Kursname, Beginn und – sofern vom Anbieter geliefert – das Ende des Kurses.
Während einer Anfrage läuft eine Ladeanzeige; Auswahlaktionen sind vorübergehend
gesperrt. Statusmeldungen werden auch über die Bedienungshilfe angekündigt.

Webzugang, Alarme, Benachrichtigungen und das Löschen von Zugangsdaten sind
unter **Webzugang & Einstellungen** zusammengefasst. Die Anmeldung selbst und
die Prüfung vor dem Speichern bleiben unverändert. Native Android-Vorschauen
werden beim Test unter `app/build/reports/ui-preview/webbooking-*.png` erzeugt.

## Studioauswahl

Die Studioliste kommt aus `tenantInfo.studios.data`; nur Standorte mit
`enableCourses=true` und `enableCourseJoin=true` werden angeboten. Die Liste
enthält auch die vom selben Anbieter bereitgestellten FitBase-/Partnerstudios.
Welche davon mit der Mitgliedschaft gebucht werden dürfen, entscheidet weiterhin
der Anbieter beim Login und Buchungsaufruf.

Grünstadt ist die anfängliche Vorauswahl. Danach merkt sich Kursfenster lokal
die zuletzt ausgewählte Studio-ID und den Namen, auch nach Schließen oder
Drehen des Bildschirms. Bei einem Studio-Wechsel werden bereits angezeigte
Kurse entfernt. Die Kursabfrage wird mit der ausgewählten ID ausgeführt;
Kurse anderer Studios in einer Antwort werden ausgeblendet. Vor dem Speichern
zeigt die Bestätigung ausdrücklich den Studio-Namen.

Jeder Plan speichert seine eigene Studio-ID und seinen Namen. Eine spätere
Änderung der Auswahl verändert keine bestehenden Pläne. Die Hintergrundbuchung
verwendet weiterhin das Studio des Plans und prüft die Antwort darauf.
**Webkursplan öffnen** in den Details öffnet die Kursauswahl für das Studio
des betreffenden Plans.

## Öffentlicher Webvertrag

Die [Studioseite Grünstadt](https://www.venicebeach-fitness.de/clubs/venicebeach/gruenstadt.html#kursplan-info)
bindet `https://widget.actinate.com/widget.js` mit `data-key=dQwvUoeuoACEFD2B`
und `data-studioId=74` ein. Der öffentliche Schlüssel ist kein Mitgliedszugang.

Widget-Code und öffentliche JSON-Antworten liefern:

- Tenant: `venicebeach`, Backend: `magicline`, Studio 74: VeniceBeach Grünstadt.
- Öffentliche Konfiguration: `GET /widgets/v1/classes/tenantInfo?key=…`.
- Login: `POST /oauth/v6/oauth/token?tenant=venicebeach`, Formular mit
  `username`, `password`, `grant_type=password`, `app_version=widget`,
  `os` und `os_version`. Antwortfeld `access_token`; weitere Anfragen mit
  `Authorization: Bearer …`.
- Berechtigungsprüfung: `GET /widgets/v1/users/me?tenant=venicebeach`,
  `user.accessTypeSettings.attendStudioClasses`.
- Kurse: `GET /widgets/v1/classes/occurences` mit `tenant`, Unix-Sekunden für
  `startAt` und `endAt`, `studioIds[]=74` und `timzoneForDayBuckets`.
  Antwort: `days.data[].occurences[]`. Der Schreibfehler `occurences` und der
  Parameter `timzoneForDayBuckets` entsprechen dem Anbieter.
- Der Kurs liefert einen Link `rel=joinWidgetOccurence` mit Methode POST.
  Nur der zum Kurs passende relative Pfad
  `/widgets/v1/classes/occurences/{id}` auf demselben HTTPS-Host wird akzeptiert.
  Die App sendet keine Abmeldeanfragen.
- Teilnahmebestätigung: erneute authentifizierte Kursabfrage mit `joined=true`
  für dieselbe Kurskennung, dasselbe Studio, denselben Namen und Beginn.
  Eine erfolgreiche HTTP-Antwort allein setzt den Plan nicht auf gebucht.

Kurskennungen liegen oberhalb des 32-Bit-Bereichs und bleiben als Zeichenfolge
im Plan erhalten. Der öffentliche Testdatensatz unter
`app/src/test/resources/web/gruenstadt.json` enthält keine Mitgliedsdaten.
Die Login- und authentifizierten Antwortverträge wurden aus dem Widget-Code
abgeleitet; die Prüfung mit einem echten Konto steht noch aus.

## Ausführung und Wiederherstellung

`CoursePlan` speichert zusätzlich die Web-Kurskennung und Studio-ID. Bestehende
Pläne ohne diese Felder bleiben bei der sichtbaren App-Bedienung. Es findet
keine automatische Umstellung alter Pläne statt. Der Buchungshelfer lehnt
Webpläne ausdrücklich ab.

`PlanScheduler` plant Beginn minus 24 echte Stunden. Der Alarm startet für
Webpläne einen kurzen `dataSync`-Vordergrunddienst mit Benachrichtigung und
zeitlich begrenztem CPU-WakeLock. Das Display wird nicht eingeschaltet; es
werden weder eine Activity noch die Bedienungshilfe für Webbuchungen gestartet.
Mehrere Webversuche laufen seriell. Zugangsdaten werden für jeden Versuch
entschlüsselt, der Login erneuert; Tokens werden nur im Speicher gehalten.
Android Keystore schützt die lokal gespeicherten Zugangsdaten mit AES-GCM.
Der Schlüssel benötigt keine erneute Entsperrung pro Zugriff. Backups sind für
die App deaktiviert. Zugangsdaten und Antworten gelangen nicht in Diagnosen.

Vor dem Buchungs-POST wird `VERIFY_REQUIRED` synchron auf dem Gerät gespeichert.
Nach einem Timeout, Prozessabbruch oder fehlender Bestätigung wird nicht erneut
gebucht. Eine erneute Ausführung dieses Zustands liest nur die Teilnahme.
Vor dem Absenden gestoppte Versuche sind über die Kursdetails manuell erneut
startbar. Das Entfernen eines Plans storniert keine Teilnahme und kann eine
bereits begonnene Übertragung nicht zuverlässig zurücknehmen.

Android kann nach „Stopp erzwingen“, bei ausgeschaltetem Gerät, vor dem ersten
Entsperren nach einem Neustart, bei fehlendem Netz oder durch Herstellerregeln
Hintergrundarbeit verhindern. Ohne Freigabe für exakte Alarme fällt der
bestehende Scheduler auf ungenaue Alarme zurück; Android kann dann den
Vordergrundstart ablehnen. Der Plan erhält einen Hinweis zur manuellen
Fortsetzung. Ein gesperrter Bildschirm allein verhindert den Webweg nicht.
Es gibt keine Garantie auf sekundengenaue Ausführung oder einen freien Platz.

## Bauen und prüfen

```sh
source /workspace/.android-tools/env.sh
./gradlew --no-daemon assembleDebug testDebugUnitTest lintDebug
./gradlew --no-daemon -PwebPreview assembleDebug
```

`-PwebPreview` ändert Paketkennung und App-Namen für die parallele Testinstallation.
Ohne diese Option bleibt die ursprüngliche Paketkennung erhalten; solche Builds
können wegen der abweichenden Signatur derzeit kein Update von 0.14.2 ersetzen.

Die automatisierten Webtests decken zusätzlich Studioauswahl, Persistenz nach
Neuerstellung/erneutem Öffnen, das Verwerfen alter Kurskarten beim Studio-Wechsel,
fehlerhafte Studiolisten und Buchung im gespeicherten Studio ab. Sie prüfen auch
den gesperrten Bildschirm, die Alarmauslösung,
Identitätsprüfung, frühe/abgelaufene Pläne, volle/abgesagte/private Kurse,
abweichende Buchungsfenster, bereits vorhandene Teilnahme, externe oder falsche
Buchungslinks, verlorene POST-Antworten, fehlerhafte Bestätigungen, Prozessneustart,
rein lesende Nachprüfung, Webplan-Persistenz und die neue Activity ab.
Diese simulierten Tests ersetzen keinen Gerätetest mit echtem Mitgliedskonto.
