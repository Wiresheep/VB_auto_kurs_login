import Foundation

/// Serializes booking attempts. Actor reentrancy is checked again before committing the send marker.
public actor BookingCoordinator {
    private let store: any PlanPersistence
    private let vault: any CredentialVault
    private let gateway: any BookingGateway
    private let clock: @Sendable () -> Date
    private var plans: [CoursePlan]
    private var running = false
    public init(store: any PlanPersistence, vault: any CredentialVault, gateway: any BookingGateway,
                clock: @escaping @Sendable () -> Date = { Date() }) throws {
        self.store = store; self.vault = vault; self.gateway = gateway; self.clock = clock
        plans = try store.load()
        guard Set(plans.map(\.id)).count == plans.count else { throw BookingError.invalidResponse }
    }
    public func snapshot() -> [CoursePlan] { plans.sorted { $0.start < $1.start } }
    public func add(_ plan: CoursePlan) throws {
        guard plan.start > clock(), plan.accountID == (try vault.load()).accountID else { throw BookingError.wrongAccount }
        guard !plans.contains(where: { $0.occurrenceID == plan.occurrenceID && $0.studioID == plan.studioID }) else { throw BookingError.changedCourse }
        var next = plans; next.append(plan); try store.save(next); plans = next
    }
    public func remove(_ id: UUID) throws {
        let next = plans.filter { $0.id != id }; try store.save(next); plans = next
    }
    /// A submitted or ambiguous booking is always read-only, including after user interaction.
    public func resume(_ id: UUID) throws {
        guard var plan = plans.first(where: { $0.id == id }), plan.status != .booked else { return }
        if plan.status != .verifyRequired { plan.status = .planned }
        plan.nextAttempt = clock(); try put(plan)
    }
    private func put(_ plan: CoursePlan) throws {
        guard let index = plans.firstIndex(where: { $0.id == plan.id }) else { return }
        var next = plans; next[index] = plan; try store.save(next); plans = next
    }
    public func nextWakeDate() -> Date? {
        let now = clock()
        return plans.compactMap { plan -> Date? in
            switch plan.status {
            case .planned, .retryWaiting: return max(plan.opens, plan.nextAttempt ?? plan.opens, now.addingTimeInterval(60))
            case .verifyRequired: return plan.nextAttempt.map { max($0, now.addingTimeInterval(60)) }
            default: return nil
            }
        }.min()
    }
    /// Only events worth notifying are returned; transient pre-submit failures simply wait for a later opportunity.
    public func runDue() async -> [CoursePlan] {
        guard !running else { return [] }
        running = true; defer { running = false }
        var events: [CoursePlan] = []
        let ids = snapshot().map(\.id)
        for id in ids {
            if Task.isCancelled { break }
            guard var plan = plans.first(where: { $0.id == id }) else { continue }
            if (plan.status == .planned || plan.status == .retryWaiting) && plan.start <= clock() {
                plan.status = .expired; plan.nextAttempt = nil; plan.message = "Der Kurs hat begonnen. Es wurde keine Buchung gesendet."
                do { try put(plan); events.append(plan) } catch { break }
                continue
            }
            guard plan.isDue(at: clock()) else { continue }
            do {
                if let event = try await attempt(id) { events.append(event) }
            } catch { break } // A persistence error must stop the run, especially before a booking POST.
        }
        return events
    }
    private func attempt(_ id: UUID) async throws -> CoursePlan? {
        guard let original = plans.first(where: { $0.id == id }) else { return nil }
        let verifyOnly = original.status == .verifyRequired
        var submitted = verifyOnly
        do {
            try Task.checkCancellation()
            let credentials = try vault.load()
            guard credentials.accountID == original.accountID else { throw BookingError.wrongAccount }
            try await gateway.login(credentials)
            let course = try original.match(in: await gateway.courses(day: original.start, studioID: original.studioID))
            guard var current = plans.first(where: { $0.id == id }) else { return nil }
            if course.joined {
                current.status = .booked; current.nextAttempt = nil; current.message = "Der Webzugang bestätigt deine Teilnahme."
                try put(current); return current
            }
            if verifyOnly { return try waitForVerification(current) }
            try Task.checkCancellation()
            try course.checkBookable(now: clock())
            let latestAccount = try vault.load()
            guard latestAccount.accountID == credentials.accountID, latestAccount.revision == credentials.revision else { throw BookingError.credentialsChanged }
            guard current.status == .planned || current.status == .retryWaiting else { return nil }
            // This is the commit boundary: no POST may precede a successful atomic marker write.
            current.status = .verifyRequired; current.nextAttempt = clock().addingTimeInterval(900)
            current.message = "Buchung wird gesendet. Bei unklarer Antwort wird ausschließlich die Teilnahme geprüft."
            try put(current)
            submitted = true
            do { try await gateway.join(course) } catch { /* The provider may have received the POST. Never send it again. */ }
            try Task.checkCancellation()
            let confirmed = try original.match(in: await gateway.courses(day: original.start, studioID: original.studioID))
            guard var latest = plans.first(where: { $0.id == id }) else { return nil }
            if confirmed.joined {
                latest.status = .booked; latest.nextAttempt = nil; latest.message = "Der Webzugang bestätigt deine Teilnahme."
                try put(latest); return latest
            }
            return try waitForVerification(latest)
        } catch {
            guard var current = plans.first(where: { $0.id == id }) else { return nil }
            // Also inspect the durable state: a later exception must never downgrade the send marker.
            if submitted || current.status == .verifyRequired { return try waitForVerification(current) }
            current.attempts += 1
            let known = error as? BookingError
            let retry = known == .network || known == .credentialsLocked || known == .credentialsChanged || error is CancellationError
            if retry && current.start > clock() {
                current.status = .retryWaiting
                let delays: [TimeInterval] = [300, 900, 1800, 3600]
                current.nextAttempt = min(clock().addingTimeInterval(delays[min(current.attempts - 1, delays.count - 1)]), current.start)
                current.message = "Noch keine Buchung gesendet. Neuer Versuch bei der nächsten verfügbaren Hintergrundzeit."
                try put(current); return nil
            }
            current.status = .actionRequired; current.nextAttempt = nil
            current.message = "Vor dem Absenden angehalten. Bitte Webzugang, Mitgliedschaft und Kursdaten prüfen."
            // Unknown errors include failed writes. Propagate these rather than assuming the disk is writable.
            if known == nil && !(error is CancellationError) { throw error }
            try put(current); return current
        }
    }
    private func waitForVerification(_ input: CoursePlan) throws -> CoursePlan? {
        var plan = input; plan.status = .verifyRequired; plan.verificationAttempts += 1
        plan.nextAttempt = plan.verificationAttempts < 3 && clock() < plan.start.addingTimeInterval(86_400)
            ? clock().addingTimeInterval(900) : nil
        plan.message = "Teilnahme noch nicht bestätigt. Es wird keine weitere Buchung gesendet. Nachprüfung liest nur den Status."
        try put(plan)
        return plan.verificationAttempts == 1 ? plan : nil
    }
}
