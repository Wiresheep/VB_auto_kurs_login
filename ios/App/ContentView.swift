import SwiftUI
import UIKit
import KursfensterCore

private struct PendingBooking: Identifiable {
    let course: Occurrence
    let studio: Studio
    var id: Int64 { course.id }
}

@MainActor struct ContentView: View {
    @ObservedObject var model: AppModel
    @State private var showAccount = false
    @State private var pending: PendingBooking?
    @State private var removing: CoursePlan?
    private let green = Color(red: 0.075, green: 0.459, blue: 0.404)
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("KURSFENSTER / iOS").font(.caption.weight(.semibold)).tracking(2).foregroundStyle(green)
                        Text("Dein nächster Kurs.").font(.largeTitle.bold())
                        Text("Studio und Tag wählen. Automatisch anmelden lassen.").foregroundStyle(.secondary)
                    }
                    VStack(alignment: .leading, spacing: 10) {
                        Label("Automatik ohne eigenen Server", systemImage: "clock.arrow.circlepath").font(.headline)
                        Text("iOS entscheidet über die Hintergrundzeit. Die Buchung kann verspätet erfolgen oder ganz ausfallen, auch innerhalb des gesamten Anmeldefensters.")
                        Text(model.backgroundStatus).font(.footnote).foregroundStyle(.secondary)
                        if UIApplication.shared.backgroundRefreshStatus != .available {
                            Button("iOS-Einstellungen öffnen") { UIApplication.shared.open(URL(string: UIApplication.openSettingsURLString)!) }
                        }
                    }.card()
                    VStack(alignment: .leading, spacing: 14) {
                        Label("Studio & Datum", systemImage: "mappin.and.ellipse").font(.headline)
                        if model.studios.isEmpty {
                            Button("Studios laden") { Task { await model.loadStudios() } }
                        } else {
                            Picker("Studio", selection: $model.selectedStudioID) {
                                ForEach(model.studios) { studio in Text(studio.name).tag(studio.id) }
                            }.pickerStyle(.menu)
                        }
                        DatePicker("Dein Tag", selection: $model.day, in: Date()..., displayedComponents: .date)
                        Button { Task { await model.loadCourses() } } label: {
                            HStack { Spacer(); if model.busy { ProgressView().tint(.white) }; Text(model.busy ? "Bitte warten …" : "Kurse laden"); Spacer() }
                        }.buttonStyle(.borderedProminent)
                    }.disabled(model.busy).card()
                    HStack {
                        Text("Deine Kursauswahl").font(.title2.bold())
                        Spacer()
                        if model.busy || model.automationBusy { ProgressView() }
                    }
                    if model.courses.isEmpty { Text("Lade die Kurse für dein Studio und deinen Wunschtag.").foregroundStyle(.secondary) }
                    ForEach(model.courses) { course in
                        VStack(alignment: .leading, spacing: 10) {
                            Text(course.name).font(.title3.bold())
                            Text(course.start, format: .dateTime.weekday().day().month().hour().minute()).foregroundStyle(.secondary)
                            Button("Automatisch buchen lassen") {
                                if let studio = model.selectedStudio { pending = PendingBooking(course: course, studio: studio) }
                            }.buttonStyle(.bordered)
                        }.frame(maxWidth: .infinity, alignment: .leading).card()
                    }
                    Text("Deine Pläne").font(.title2.bold())
                    if model.plans.isEmpty { Text("Noch keine Kurse geplant.").foregroundStyle(.secondary) }
                    ForEach(model.plans) { plan in
                        VStack(alignment: .leading, spacing: 8) {
                            Text(plan.status.title).font(.caption.weight(.semibold)).foregroundStyle(green)
                            Text(plan.courseName).font(.title3.bold())
                            Text(plan.studioName).foregroundStyle(.secondary)
                            Text(plan.start, format: .dateTime.weekday().day().month().hour().minute())
                            Text("Anmeldefenster ab \(plan.opens.formatted(date: .abbreviated, time: .shortened))").font(.footnote).foregroundStyle(.secondary)
                            Text(plan.message).font(.footnote)
                            if plan.status == .actionRequired || plan.status == .verifyRequired {
                                Button(plan.status == .verifyRequired ? "Teilnahme prüfen (nur lesen)" : "Automatik fortsetzen") { Task { await model.resume(plan) } }
                            }
                            Button("Plan entfernen", role: .destructive) { removing = plan }.font(.footnote)
                        }.frame(maxWidth: .infinity, alignment: .leading).card()
                    }
                    VStack(alignment: .leading, spacing: 8) {
                        Text(model.configured ? "Webzugang eingerichtet" : "Webzugang noch einrichten").font(.headline)
                        Button("Webzugang & Einstellungen") { showAccount = true }
                        Text("Zugangsdaten bleiben in der Keychain dieses iPhones. Die VeniceBeach-App wird nicht benötigt.")
                            .font(.footnote).foregroundStyle(.secondary)
                    }.frame(maxWidth: .infinity, alignment: .leading).card()
                }.padding(20)
            }
            .background(Color(red: 0.953, green: 0.965, blue: 0.957))
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Webzugang", systemImage: "person.crop.circle") { showAccount = true } } }
            .sheet(isPresented: $showAccount) { AccountView(model: model) }
            .sheet(item: $pending) { selection in
                NavigationStack {
                    Form {
                        Section("Kurs prüfen") {
                            LabeledContent("Kurs", value: selection.course.name)
                            LabeledContent("Studio", value: selection.studio.name)
                            LabeledContent("Beginn", value: selection.course.start.formatted(date: .abbreviated, time: .shortened))
                            LabeledContent("Anmeldung frühestens", value: selection.course.opens.formatted(date: .abbreviated, time: .shortened))
                        }
                        Section {
                            Text("Nach dem Speichern wird ohne weiteren Buchungsklick automatisch gebucht, sobald das Fenster offen ist und die App ausgeführt wird. iOS kann den Hintergrundstart verzögern oder auslassen.")
                            Button("Automatische Buchung planen") { Task { await model.add(selection.course, studio: selection.studio); pending = nil } }
                                .disabled(!model.configured)
                            if !model.configured { Text("Bitte zuerst den Webzugang einrichten.").foregroundStyle(.secondary) }
                        }
                    }.navigationTitle("Kurs planen").toolbar { ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { pending = nil } } }
                }.presentationDetents([.large])
            }
            .confirmationDialog("Plan entfernen?", isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } })) {
                Button("Entfernen", role: .destructive) { if let plan = removing { Task { await model.remove(plan) } }; removing = nil }
            } message: { Text("Eine Teilnahme wird nicht storniert. Eine bereits übertragene Buchung kann noch abgeschlossen werden.") }
            .alert("Hinweis", isPresented: Binding(get: { model.errorMessage != nil }, set: { if !$0 { model.errorMessage = nil } })) {
                Button("OK") { model.errorMessage = nil }
            } message: { Text(model.errorMessage ?? "") }
        }
    }
}

@MainActor private struct AccountView: View {
    @ObservedObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var email = ""
    @State private var password = ""
    @State private var deleting = false
    var body: some View {
        NavigationStack {
            Form {
                Section("VeniceBeach-Webzugang") {
                    TextField("E-Mail", text: $email).textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.emailAddress).textContentType(.username)
                    SecureField("Passwort", text: $password).textContentType(.password).privacySensitive()
                    Text("Für die Automatik speichert Kursfenster die Zugangsdaten in der Keychain. Zugriff bei gesperrtem Display ist nach dem ersten Entsperren nach einem Neustart möglich.").font(.footnote)
                    Button(model.busy ? "Wird geprüft …" : "Prüfen & speichern") {
                        Task { let secret = password; password = ""; if await model.saveAccount(email: email, password: secret) { dismiss() } }
                    }.disabled(model.busy || email.isEmpty || password.isEmpty)
                }
                Section("Hintergrund & Hinweise") {
                    Text("Hintergrundaktualisierung aktivieren und Stromsparmodus möglichst vermeiden. Nach bewusstem Beenden die App erneut öffnen. Auch bei erlaubter Hintergrundarbeit kann iOS die Ausführung auslassen.")
                    Button("iOS-Einstellungen öffnen") { UIApplication.shared.open(URL(string: UIApplication.openSettingsURLString)!) }
                    Button("Ergebnis-Benachrichtigungen erlauben") { Task { await model.requestNotifications() } }
                }
                Section {
                    Button("Webzugang löschen", role: .destructive) { deleting = true }
                } footer: { Text("Geplante Buchungen bleiben gespeichert, benötigen anschließend erneut denselben Webzugang. Ein anderer Account bucht keine Pläne des bisherigen Accounts.") }
            }
            .navigationTitle("Webzugang").toolbar { ToolbarItem(placement: .cancellationAction) { Button("Fertig") { password = ""; dismiss() } } }
            .onAppear { email = model.email }
            .confirmationDialog("Zugangsdaten löschen?", isPresented: $deleting) {
                Button("Löschen", role: .destructive) { Task { await model.clearAccount(); password = ""; dismiss() } }
            } message: { Text("Bereits abgesendete Buchungen können noch abgeschlossen werden.") }
            .alert("Webzugang", isPresented: Binding(get: { model.errorMessage != nil }, set: { if !$0 { model.errorMessage = nil } })) {
                Button("OK") { model.errorMessage = nil }
            } message: { Text(model.errorMessage ?? "") }
        }
    }
}

private extension View {
    func card() -> some View { padding(18).background(.background, in: RoundedRectangle(cornerRadius: 20)) }
}
