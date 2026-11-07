# Kursfenster – Kursplaner für VeniceBeach

**Aktuelle Hauptversion: Kursfenster 0.15.2.**

Android-App für automatische Kursanmeldungen 24 Stunden vor Kursbeginn.
Die Webbuchung läuft auf dem Smartphone, auch bei gesperrtem Display,
mit Studioauswahl und lokal gespeicherten Plänen. Ein eigener Server ist nicht nötig.
Unabhängiges Projekt, keine offizielle VeniceBeach-App.

## Herunterladen

- [Kursfenster 0.15.2 installieren](downloads/kursfenster-0.15.2.apk)
- [SHA-256-Prüfsumme](downloads/kursfenster-0.15.2.apk.sha256)
- [Vorschau der Webbuchung](downloads/kursfenster-0.15.2-webbuchung.png)

Auf GitHub die APK öffnen und **Download raw file** wählen.
Die App heißt jetzt **Kursfenster**. 0.15.2 ersetzt 0.14.2 als Hauptversion dieses
Projekts; die bisherigen Downloads bleiben als Archiv erhalten.

## iOS-Port

Unter [ios/](ios/README.md) entsteht ein eigenständiger SwiftUI-Port ohne
VeniceBeach-App. Er führt Buchungen bei verfügbarer iOS-Hintergrundzeit automatisch
aus und erlaubt verspätete Versuche. iOS kann die Ausführung auch ganz auslassen.
Der Kern ist mit 20 Tests geprüft; ein Xcode-Build und iPhone-Gerätetest stehen noch
aus. Es gibt deshalb noch keine installierbare iOS-Version. Android 0.15.2 bleibt
von diesem Port unberührt.

Für **iPhone 16 und neuer** ist ein Cloud-Build über den manuellen GitHub-Workflow
**iOS – Build für iPhone** vorbereitet. Er kann auch vom Browser eines älteren Macs
wie dem MacBook Air 2015 gestartet werden. Der Workflow erzeugt zunächst eine
**unsignierte** IPA; Signierung und Installation sind ein separater Schritt.
[Anleitung für den Cloud-Build](ios/README.md#über-github-actions-bauen).

## Installation und Updates

Die aktuelle APK ist ein signierter Release-Build ohne aktiviertes Debugging.
Sie lässt sich als Update über **Kursfenster Webtest 0.15.0 bis 0.15.2** installieren.
Dabei bleiben Zugangsdaten und Kurspläne erhalten. Die höhere interne Versionsnummer
ermöglicht auch das Update von der bisherigen Testausgabe 0.15.2.

Die interne Paketkennung `de.wiresheep.kursplaner.webpreview` bleibt für diese
Updatefähigkeit erhalten; der sichtbare App-Name lautet **Kursfenster**.

**Von 0.14.2 ist kein direktes Update möglich:** Der passende frühere Signierschlüssel
liegt hier nicht vor. Die neue Hauptversion kann neben 0.14.2 installiert werden;
die alten Pläne werden dabei nicht automatisch übernommen. Die bisherige App nicht
zum Installieren der neuen Version deinstallieren.

## Kurs automatisch buchen lassen

1. **Kurs im Web auswählen (auch bei Displaysperre)** öffnen.
2. Unter **Webzugang & Einstellungen** die VeniceBeach-Zugangsdaten einrichten
   sowie zeitgenaue Alarme und Benachrichtigungen erlauben.
3. Studio und Tag wählen, **Kurse laden** und einen tatsächlich gewünschten Kurs
   auswählen. Die letzte Studioauswahl wird gemerkt.
4. Studio, Beginn und Anmeldezeitpunkt prüfen und **Automatisch buchen lassen**
   bestätigen. Bei bereits offenem 24-Stunden-Fenster beginnt der Versuch sofort.
5. Unter **Deine Kurse** den Status prüfen. Ein unbestätigter Buchungsversuch wird
   nicht automatisch wiederholt; eine Nachprüfung liest ausschließlich die Teilnahme.

Bestehende Pläne behalten ihr gespeichertes Studio, auch wenn danach ein anderes
Studio ausgewählt wird. Zugangsdaten werden auf dem Gerät mit Android Keystore
verschlüsselt gespeichert. Das Entfernen eines Plans storniert keine Teilnahme.

[Ausführliche Web-Anleitung und technische Details](docs/web-booking.md) ·
[Alternative Buchung über die sichtbare VeniceBeach-App](docs/app-booking.md)

## Prüfung und Grenzen

Build, automatisierte Tests und Android Lint sind geprüft. Die öffentliche
Studioliste und Kursabfragen für Grünstadt und Frankenthal wurden live gelesen.
Login und echte Buchung müssen noch mit einem Mitgliedskonto auf dem Smartphone
bestätigt werden; die Hauptversion-Kennzeichnung ersetzt diesen Gerätetest nicht.

Android kann Arbeit nach „Stopp erzwingen“, bei ausgeschaltetem Gerät, fehlendem
Netz oder durch Energiesparregeln verhindern. Auch exakte Alarme garantieren
keine sekundengenaue Ausführung oder einen freien Kursplatz.

## Bauen

JDK 17 oder neuer, Android SDK 35 und Build Tools 35.0.0 verwenden.
Minimum Android 8 (API 26), Zielversion Android 15 (API 35).

```sh
./gradlew --no-daemon assembleRelease testReleaseUnitTest lintRelease
```

APK: `app/build/outputs/apk/release/app-release.apk`.
Die Release-Konfiguration verwendet den bisherigen lokalen Signierschlüssel aus
dem Android-Debug-Keystore, damit die vorhandenen Webversionen aktualisierbar bleiben.
Dieser Schlüssel muss für zukünftige Updates aufbewahrt werden; er wird nicht ins
Repository eingecheckt. Der Build ist unabhängig vom verwendeten Schlüsselspeicher
als Release gebaut und nicht debuggable.

In dieser Cloud-Arbeitskopie vor dem Build:

```sh
source /workspace/.android-tools/env.sh
```

Für neue Cloud-Umgebungen stehen `scripts/setup-cloud.sh` und
`scripts/bootstrap-android.py` bereit. Native Android-Vorschauen entstehen beim
Test unter `app/build/reports/ui-preview/`.
