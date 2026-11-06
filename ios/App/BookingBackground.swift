import UIKit
import BackgroundTasks
import UserNotifications
import KursfensterCore

extension Notification.Name { static let bookingStateChanged = Notification.Name("KursfensterBookingStateChanged") }

@MainActor final class BookingBackground {
    static let shared = BookingBackground()
    private let refreshID = "de.wiresheep.kursfenster.booking.refresh"
    private let processingID = "de.wiresheep.kursfenster.booking.processing"
    private(set) var status = "iOS entscheidet, wann die Automatik Hintergrundzeit erhält."
    func register() {
        for identifier in [refreshID, processingID] {
            BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: .main) { task in
                Task { @MainActor in self.handle(task) }
            }
        }
    }
    private func handle(_ backgroundTask: BGTask) {
        let operation = Task { @MainActor in
            defer { backgroundTask.expirationHandler = nil }
            do {
                let runtime = try BookingRuntime.current()
                let events = await runtime.coordinator.runDue()
                await notify(events)
                await schedule()
                NotificationCenter.default.post(name: .bookingStateChanged, object: nil)
                backgroundTask.setTaskCompleted(success: !Task.isCancelled)
            } catch { backgroundTask.setTaskCompleted(success: false) }
        }
        backgroundTask.expirationHandler = { operation.cancel() }
    }
    func schedule() async {
        do {
            let runtime = try BookingRuntime.current()
            BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: refreshID)
            BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: processingID)
            guard let date = await runtime.coordinator.nextWakeDate() else { status = "Keine automatische Anmeldung offen."; return }
            guard UIApplication.shared.backgroundRefreshStatus == .available else {
                status = "Hintergrundaktualisierung ist aktuell nicht verfügbar. Bitte iOS-Einstellungen und Stromsparmodus prüfen."
                return
            }
            let refresh = BGAppRefreshTaskRequest(identifier: refreshID); refresh.earliestBeginDate = date
            let processing = BGProcessingTaskRequest(identifier: processingID)
            processing.earliestBeginDate = date; processing.requiresNetworkConnectivity = true; processing.requiresExternalPower = false
            var submitted = 0
            do { try BGTaskScheduler.shared.submit(refresh); submitted += 1 } catch { }
            do { try BGTaskScheduler.shared.submit(processing); submitted += 1 } catch { }
            status = submitted > 0 ? "Automatik bei iOS angefragt. Die Ausführung kann verspätet erfolgen oder ganz ausfallen."
                : "iOS hat die Hintergrundanfrage abgelehnt. Beim nächsten Öffnen wird erneut geplant."
        } catch { status = "Gespeicherte Pläne sind derzeit nicht verfügbar. Bitte App nach dem Entsperren erneut öffnen." }
    }
    func notify(_ plans: [CoursePlan]) async {
        let center = UNUserNotificationCenter.current()
        let settings = await center.notificationSettings()
        guard settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional else { return }
        for plan in plans {
            let content = UNMutableNotificationContent()
            content.title = plan.status == .booked ? "Angemeldet: \(plan.courseName)" : "Anmeldung prüfen: \(plan.courseName)"
            content.body = plan.message; content.sound = .default
            try? await center.add(UNNotificationRequest(identifier: plan.id.uuidString + plan.status.rawValue, content: content, trigger: nil))
        }
    }
}

@MainActor final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        BookingBackground.shared.register()
        return true
    }
}
