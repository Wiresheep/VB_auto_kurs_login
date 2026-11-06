import SwiftUI

@main @MainActor struct KursfensterApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var model = AppModel()
    @Environment(\.scenePhase) private var scenePhase
    var body: some Scene {
        WindowGroup {
            ContentView(model: model)
                .tint(Color(red: 0.075, green: 0.459, blue: 0.404))
                .environment(\.timeZone, TimeZone(identifier: "Europe/Berlin")!)
                .overlay {
                    if scenePhase != .active {
                        Color(red: 0.953, green: 0.965, blue: 0.957).ignoresSafeArea().overlay(Text("Kursfenster").font(.title.bold()))
                    }
                }
                 .task {
                    await model.refreshLocal()
                    if scenePhase == .active { await model.loadStudios(); await model.runAutomation() }
                }
                .onChange(of: scenePhase) { _, phase in
                    Task {
                        if phase == .active {
                            await model.refreshLocal()
                            if model.studios.isEmpty { await model.loadStudios() }
                            await model.runAutomation()
                        } else { await BookingBackground.shared.schedule() }
                    }
                }
                .onReceive(NotificationCenter.default.publisher(for: .bookingStateChanged)) { _ in Task { await model.refreshLocal() } }
        }
    }
}
