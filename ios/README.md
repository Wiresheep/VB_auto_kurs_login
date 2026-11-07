# Kursfenster für iOS – Port 0.1.0

Native SwiftUI-App ab iOS 17 mit Weblogin, Studioauswahl, Kursplanung und
selbstständigen Buchungsversuchen. Sie benötigt weder die VeniceBeach-App
noch einen eigenen Server. Android 0.15.2 bleibt die aktuelle Android-Hauptversion.

**Status:** Der Swift-Buchungskern kompiliert unter Swift 6.2/Linux; 20 Tests
bestehen. Die App-Quelldateien sind auf Swift-Syntax geprüft, das Xcode-Projekt
und seine Verweise sind als Property List validiert. Ein Xcode-/Simulator-Build,
Apple-Signierung, Keychain-Gerätetest und echte Buchung stehen noch aus.
Die Linux-Prüfung ist kein Nachweis, dass die iOS-App bereits installierbar ist.

## Was automatisch läuft

Nach Einrichtung des Webzugangs wird ein Kurs aus dem gewünschten Studio
geplant. Beginn minus 24 echte Stunden öffnet das Buchungsfenster. Die App
beantragt Hintergrundausführung mit `BGAppRefreshTaskRequest` und
`BGProcessingTaskRequest`; der zweite Antrag benötigt Netzwerk, aber kein Ladekabel.
Beim Öffnen der App werden fällige Pläne ebenfalls automatisch bearbeitet.
Während einer gewährten Ausführung wird ohne weiteren Buchungsklick angemeldet.
Die tatsächliche Teilnahme wird anschließend über den Webzugang geprüft.

**Eine spätere Ausführung ist ausdrücklich vorgesehen. Sie ist nicht garantiert:**
`earliestBeginDate` ist nur der früheste Wunschzeitpunkt. iOS kann einen Task
Stunden später oder während des gesamten Anmeldefensters gar nicht ausführen.
Regelmäßige App-Nutzung kann die Chancen verbessern, ist aber keine Garantie.
Nach bewusstem Beenden, bei ausgeschalteter Hintergrundaktualisierung, im
Stromsparmodus oder vor dem ersten Entsperren nach Neustart kann die Automatik
unterbleiben. Ein ausgeschaltetes Gerät bucht nicht. Benachrichtigungen sind
Ergebnismeldungen und lösen selbst keine Buchung aus. Es werden keine
Audio-, Standort- oder sonstigen Hintergrundmodi zweckentfremdet.

Für verbindlich ausgeführte Buchungen unabhängig von iOS wäre weiterhin eine
außerhalb des iPhones laufende Ausführung oder eine entsprechende Anbieterfunktion
nötig. Das vorliegende Projekt verwendet beides nicht.

## Wiederholungen und Schutz vor Doppelbuchungen

- Vor dem Absenden: vorübergehende Netzfehler, gesperrte Keychain oder geänderte
  Zugangsdaten führen zu einer neuen Anfrage nach 5, 15, 30 und danach 60 Minuten.
  Auch diese Zeitpunkte sind Wünsche an iOS; es wird nicht im Hintergrund gewartet.
- Falsche Zugangsdaten, fehlende Mitgliedschaft oder unpassende Kursdaten halten
  den Plan an. Eine Korrektur bzw. das Fortsetzen der Automatik ist dann erforderlich.
- Vor dem Buchungs-POST wird der Zustand `verifyRequired` atomar gespeichert.
  Nach Timeout, Ablauf der Hintergrundzeit, Prozessneustart oder unklarer Antwort
  wird ausschließlich die Teilnahme gelesen. Ein weiterer POST erfolgt nicht,
  auch nicht beim Antippen von **Teilnahme prüfen**.
- Eine automatische Nachprüfung wird höchstens dreimal versucht. Danach bleibt
  der Plan als unbestätigt erhalten. Ein vor dem tatsächlichen Versand abgebrochener
  Versuch kann wegen dieses vorsichtigen Markers ebenfalls ungebucht bleiben.
- Jede Buchung prüft Kurskennung, Studio, Name und Beginn. 64-Bit-Kurskennungen
  bleiben verlustfrei erhalten. Neue Studioauswahl verändert gespeicherte Pläne nicht.
- Pläne gehören zum Account, mit dem sie angelegt wurden. Ein anderer Account
  bucht diese Pläne nicht. Ändern sich Zugangsdaten während eines laufenden
  Versuchs, wird vor dem Absenden angehalten.
- Löschen eines Plans storniert keine Teilnahme. Eine schon begonnene Übertragung
  kann trotz Löschen noch abgeschlossen werden.

## Daten auf dem Gerät

`KeychainCredentials` speichert Zugangsdaten unter
`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`, ohne biometrische Abfrage bei
jedem Zugriff. Nach dem ersten Entsperren nach einem Neustart können daher auch
Buchungen bei gesperrtem Display darauf zugreifen. Andere Geräte erhalten diese
Keychain-Einträge nicht über iCloud-Synchronisierung. Tokens bleiben im Speicher.

Pläne liegen atomar als JSON im Application-Support-Verzeichnis und sind unter
für Hintergrundzugriff geeigneter Dateischutzklasse gespeichert. Defekte Dateien
werden nicht stillschweigend überschrieben. HTTP-Antworten, Tokens und Passwörter
werden nicht in Diagnosemeldungen ausgegeben. HTTPS bleibt verpflichtend;
Weiterleitungen auf andere Hosts werden abgelehnt.

## iPhone 16 und MacBook Air Anfang 2015

Das iPhone 16 wurde mit iOS 18 eingeführt. Für die hier vorgesehene Entwicklung
verwenden wir daher Xcode 16 oder neuer mit einem iOS-SDK ab Version 18.
Ein MacBook Air Anfang 2015 kann offiziell höchstens macOS Monterey verwenden;
auch Monterey 12.7.6 ermöglicht dort keinen aktuellen lokalen Xcode-Build.
Das iOS-Projekt wird deshalb nicht auf Xcode 14.2 zurückgestuft.

### Über GitHub Actions bauen

Der Workflow [iOS – Build für iPhone](../.github/workflows/ios-build.yml) verwendet
einen von GitHub bereitgestellten macOS-Runner. Der eigene Mac benötigt dafür nur
den Browser. Ein eigener Server für die laufende App wird nicht eingerichtet.

1. Auf GitHub dieses Repository öffnen und **Actions** wählen.
2. Links **iOS – Build für iPhone** auswählen und **Run workflow** drücken.
3. Den Branch **main** wählen und den Lauf starten. Der Workflow wird ausschließlich
   manuell gestartet; er läuft nicht automatisch bei jedem Push.
4. Der Lauf zeigt die verwendete Xcode-, Swift- und SDK-Version, testet den Kern,
   baut für den Simulator und erstellt anschließend ein Release-Archiv für ein
   echtes iPhone. Apple-Konto, Zertifikate und Provisioning-Profile werden dafür
   nicht benötigt.
5. Bei Erfolg unter **Artifacts → Kursfenster-iPhone-UNSIGNIERT** das Paket
   herunterladen. Es enthält `Kursfenster-unsigned.ipa`, eine Prüfsumme und
   Installationshinweise. Die Dateien werden sieben Tage aufbewahrt.
6. Bei einem Fehler den fehlgeschlagenen Schritt und das Artefakt
   **iOS-Buildprotokolle** prüfen. Ein grüner Kern-Test allein beweist noch keinen
   erfolgreichen iPhone-Build.

Der Workflow ist vorbereitet; ein tatsächlicher macOS-Lauf ist hier noch nicht
nachgewiesen. Ein erfolgreicher Build ersetzt weder Signierung noch Gerätetest.
Dieses Repository ist öffentlich. Der hier verwendete Standard-macOS-Runner fällt
unter die kostenlose Actions-Nutzung für öffentliche Repositories. Für private
Repositories oder größere Runner gelten andere Bedingungen. Der Workflow startet
nur manuell und ist auf 30 Minuten begrenzt.

### Ohne kostenpflichtiges Apple-Developer-Konto

Die unsignierte IPA ist **noch nicht direkt installierbar**. Für TestFlight oder
App-Store-Verteilung wird eine kostenpflichtige Apple-Developer-Mitgliedschaft
benötigt. Kostenlose persönliche Signierung kann für private Tests ein anderer
Weg sein. Die persönliche Signierung gilt üblicherweise sieben Tage und muss
danach erneuert werden. Bei Ablauf startet auch die Buchungsautomatik nicht mehr.
Ein geeignetes Signierwerkzeug für Monterey und die konkrete iOS-Version muss dafür separat
geprüft werden. Ein Apple-Passwort oder privater Signierschlüssel gehört nicht in
Chat, Repository oder Buildprotokolle.

Der Workflow enthält absichtlich keine Signier-Geheimnisse und behauptet nicht,
eine bereits installierbare oder für TestFlight freigegebene App auszugeben.

## Auf einem Mac bauen

Xcode 16 oder neuer verwenden und zunächst Folgendes ausführen:

```sh
cd ios
./scripts/verify-on-mac.sh
```

Das Skript prüft Xcode ab Version 16, testet das Swift-Paket, validiert die
Projektverweise und baut die App für den iOS-Simulator ohne Signierung. Dieser Schritt ist hier mangels macOS/Xcode **noch nicht ausgeführt**.
Das Projekt lässt sich direkt mit `open Kursfenster.xcodeproj` öffnen;
zusätzliche Projektgeneratoren oder externe Swift-Pakete werden nicht benötigt.

Für ein echtes iPhone:

1. In Xcode das Target **Kursfenster** wählen, unter **Signing & Capabilities**
   das eigene Apple-Team einstellen und bei Bedarf eine eigene Bundle-ID verwenden.
   Es sind keine Signierschlüssel im Repository enthalten.
2. Die aktivierten Background Modes **Background fetch** und **Background processing**
   sowie die beiden IDs aus `Info.plist` beibehalten. Registrierung erfolgt beim Start
   in `AppDelegate`, bevor der App-Start abgeschlossen wird.
3. iPhone als Ziel auswählen und über Xcode installieren. Für eine Verteilung über
   TestFlight wird ein Apple-Developer-Konto und ein signiertes Archiv benötigt.
4. Webzugang in der App prüfen, Ergebnis-Benachrichtigungen erlauben und
   Hintergrundaktualisierung in iOS aktivieren. Einen tatsächlich gewünschten Kurs
   planen und die Bestätigungsangaben einschließlich Studio kontrollieren.
5. Zunächst eine Anmeldung bei geöffneter App prüfen. Anschließend einen späteren
   Start mit gesperrtem iPhone testen und Teilnahme im Anbieter-Kursplan kontrollieren.
   Für echte Hintergrundtests nicht dauerhaft mit dem Debugger verbunden bleiben.
6. Flugmodus vor dem Versuch, Prozessabbruch nach der Versandmarkierung, Neustart,
   anderer Account und ausgeschaltete Hintergrundaktualisierung zusätzlich prüfen.

Ein manuell im Debugger ausgelöster Task prüft nur den Handler. Er beweist keine
zuverlässige automatische Zuteilung durch iOS. App-Store-Datenschutzangaben und die
Privacy-Manifest-Deklaration sind vor externer Verteilung mit dem tatsächlichen
Produktbetrieb abzugleichen; eine Store-Freigabe ist nicht Bestandteil dieses Ports.

## Kern getrennt testen

```sh
swift test --package-path ios/KursfensterCore
swift ios/scripts/validate-project.swift ios
```

Die 20 Tests prüfen unter anderem echte 24 Stunden und Studio-Tagesgrenzen über
Sommerzeitwechsel, verspätete Ausführung, Wiederholung vor dem Versand, verlorene
POST-Antworten, Neustart nach Versandmarkierung, Schreibfehler, Ablauf während des
Versands, gelöschte Pläne, Accountwechsel, OAuth-Formularkodierung, Bearer-Header,
Studiofilter und die Ablehnung falscher Buchungslinks. Es werden ausschließlich
öffentliche Datensatz-Fixtures und simulierte Antworten benutzt, keine echten
Zugangsdaten oder Buchungen.

Die SwiftUI-Oberfläche, iOS-Systemhandler und die Keychain-Integration benötigen
zusätzlich den beschriebenen Xcode- und Gerätetest. Die Tests behaupten keine
Erfolgswahrscheinlichkeit der iOS-Hintergrundplanung.

`python3 ios/scripts/generate-project.py` regeneriert das eingecheckte Xcode-Projekt
bei Änderungen an der Dateiliste. Keine manuellen Signing-Einstellungen in dieses
Skript schreiben. Die Android-App und ihre Versionsnummer werden nicht verändert.

Apple-Dokumentation: [BackgroundTasks](https://developer.apple.com/documentation/backgroundtasks),
[earliestBeginDate](https://developer.apple.com/documentation/backgroundtasks/bgtaskrequest/earliestbegindate),
[Keychain-Zugriff nach dem ersten Entsperren](https://developer.apple.com/documentation/security/ksecattraccessibleafterfirstunlockthisdeviceonly).
