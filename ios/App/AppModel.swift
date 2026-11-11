import SwiftUI
import UserNotifications
import KursfensterCore

@MainActor final class AppModel: ObservableObject {
    @Published var studios: [Studio] = []
    @Published var courses: [Occurrence] = []
    @Published var plans: [CoursePlan] = []
    @Published var selectedStudioID: Int = UserDefaults.standard.object(forKey: "studioID") as? Int ?? 74 {
        didSet { courses = []; UserDefaults.standard.set(selectedStudioID, forKey: "studioID") }
    }
    @Published var day: Date = StudioTime.calendar.date(byAdding: .day, value: 1, to: Date())! { didSet { courses = [] } }
    @Published var email = ""
    @Published var busy = false
    @Published var automationBusy = false
    @Published var backgroundStatus = ""
    @Published var errorMessage: String?
    private var runtime: BookingRuntime?
    private let api = WidgetAPI()
    var selectedStudio: Studio? { studios.first { $0.id == selectedStudioID } }
    var configured: Bool { !email.isEmpty }
    init() {
        do {
            let instance = try BookingRuntime.current(); runtime = instance
            email = (try? instance.vault.load().email) ?? ""
        }
        catch { errorMessage = "Gespeicherte Pläne konnten nicht gelesen werden. Die Datei wird nicht überschrieben." }
    }
    func refreshLocal() async {
        if runtime == nil { runtime = try? BookingRuntime.current() }
        if let runtime { plans = await runtime.coordinator.snapshot(); email = (try? runtime.vault.load().email) ?? "" }
        backgroundStatus = BookingBackground.shared.status
    }
    func loadStudios() async {
        guard !busy else { return }; busy = true; defer { busy = false }
        do {
            studios = try await api.studios()
            if !studios.contains(where: { $0.id == selectedStudioID }), let first = studios.first { selectedStudioID = first.id }
        } catch { errorMessage = "Studios nicht erreichbar. Bitte Internet prüfen und erneut laden." }
    }
    func loadCourses() async {
        guard !busy, let studio = selectedStudio else { return }
        let requestedDay = day; busy = true; courses = []; defer { busy = false }
        do {
            let result = try await api.courses(day: requestedDay, studioID: studio.id)
            guard selectedStudioID == studio.id, StudioTime.calendar.isDate(day, inSameDayAs: requestedDay) else { return }
            courses = result.filter { $0.isOffered(studioID: studio.id, day: requestedDay, now: Date()) }
                .filter { (try? $0.joinPath()) != nil }.sorted { $0.start < $1.start }
        } catch { errorMessage = (error as? BookingError ?? .network).courseLoadingMessage + " Es wurde nichts gebucht." }
    }
    func saveAccount(email: String, password: String) async -> Bool {
        guard !busy, let runtime else { return false }
        let address = email.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !address.isEmpty, !password.isEmpty else { errorMessage = "Bitte E-Mail und Passwort eingeben."; return false }
        busy = true; defer { busy = false }
        do {
            try await WidgetAPI().login(Credentials(email: address, password: password))
            try runtime.vault.save(email: address, password: password)
            self.email = address
            await BookingBackground.shared.schedule(); await refreshLocal()
            return true
        } catch { errorMessage = "Webzugang konnte nicht geprüft oder gespeichert werden. Zugangsdaten, Mitgliedschaft und Netz prüfen."; return false }
    }
    func clearAccount() async {
        do { try runtime?.vault.clear(); email = ""; await BookingBackground.shared.schedule(); await refreshLocal() }
        catch { errorMessage = "Webzugang konnte nicht gelöscht werden." }
    }
    func add(_ course: Occurrence, studio: Studio) async {
        guard let runtime else { return }
        do {
            let account = try runtime.vault.load()
            try course.checkBookableForPlanning(studio: studio)
            let plan = try CoursePlan(accountID: account.accountID, course: course, studio: studio)
            try await runtime.coordinator.add(plan)
            await runAutomation()
        } catch { errorMessage = "Plan nicht gespeichert. Bitte Webzugang einrichten, Kursdaten und vorhandene Pläne prüfen." }
        await refreshLocal()
    }
    func remove(_ plan: CoursePlan) async {
        do { try await runtime?.coordinator.remove(plan.id); await BookingBackground.shared.schedule(); await refreshLocal() }
        catch { errorMessage = "Plan konnte nicht entfernt werden." }
    }
    func resume(_ plan: CoursePlan) async {
        do { try await runtime?.coordinator.resume(plan.id); await runAutomation() }
        catch { errorMessage = "Plan konnte nicht fortgesetzt werden." }
    }
    func runAutomation() async {
        if runtime == nil { await refreshLocal() }
        guard !automationBusy, let runtime else { return }
        automationBusy = true; defer { automationBusy = false }
        let events = await runtime.coordinator.runDue()
        await BookingBackground.shared.notify(events)
        await BookingBackground.shared.schedule()
        await refreshLocal()
    }
    func requestNotifications() async {
        _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])
    }
}

private extension Occurrence {
    func checkBookableForPlanning(studio: Studio) throws {
        guard isOffered(studioID: studio.id, day: start, now: Date()) else { throw BookingError.unavailableCourse }
    }
}
