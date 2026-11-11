import SwiftUI
import UIKit

enum AppColors {
    static let accent = Color("AccentColor")
    static let buttonFill = Color(red: 0.075, green: 0.459, blue: 0.404)
    static let pageBackground = Color(uiColor: .systemGroupedBackground)
    static let cardBackground = Color(uiColor: .secondarySystemGroupedBackground)
}

@main @MainActor struct KursfensterApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var model = AppModel()
    @Environment(\.scenePhase) private var scenePhase
    var body: some Scene {
        WindowGroup {
            ContentView(model: model)
                .tint(AppColors.accent)
                .environment(\.timeZone, TimeZone(identifier: "Europe/Berlin")!)
                .overlay {
                    if scenePhase != .active {
                        AppColors.pageBackground.ignoresSafeArea().overlay(Text("Kursfenster").font(.title.bold()).foregroundStyle(.primary))
                    }
                }
                 .task {
                    guard !Self.screenshotMode else { return }
                    await model.refreshLocal()
                    if scenePhase == .active { await model.loadStudios(); await model.runAutomation() }
                }
                .onChange(of: scenePhase) { _, phase in
                    guard !Self.screenshotMode else { return }
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
    // CI captures the native colors without network requests, credentials or scheduled bookings.
    private static var screenshotMode: Bool {
        #if DEBUG
        ProcessInfo.processInfo.arguments.contains("--ui-screenshot")
        #else
        false
        #endif
    }
}
