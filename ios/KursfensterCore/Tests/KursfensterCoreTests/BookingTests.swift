import XCTest
@testable import KursfensterCore

private enum TestFailure: Error { case disk }
private final class MemoryStore: PlanPersistence, @unchecked Sendable {
    private let lock = NSLock()
    private var plans: [CoursePlan]
    var rejectMarker = false
    init(_ plans: [CoursePlan] = []) { self.plans = plans }
    func load() throws -> [CoursePlan] { lock.lock(); defer { lock.unlock() }; return plans }
    func save(_ plans: [CoursePlan]) throws {
        lock.lock(); defer { lock.unlock() }
        if rejectMarker && plans.contains(where: { $0.status == .verifyRequired }) { throw TestFailure.disk }
        self.plans = plans
    }
}
private final class TestVault: CredentialVault, @unchecked Sendable {
    private let lock = NSLock(); private var record: Credentials
    init(_ record: Credentials) { self.record = record }
    func load() throws -> Credentials { lock.lock(); defer { lock.unlock() }; return record }
    func set(_ value: Credentials) { lock.lock(); defer { lock.unlock() }; record = value }
}
private final class Clock: @unchecked Sendable {
    private let lock = NSLock(); private var date: Date
    init(_ date: Date) { self.date = date }
    func now() -> Date { lock.lock(); defer { lock.unlock() }; return date }
    func advance(_ seconds: TimeInterval) { lock.lock(); defer { lock.unlock() }; date = date.addingTimeInterval(seconds) }
}
private actor Gate {
    private var suspended = false
    private var continuation: CheckedContinuation<Void, Never>?
    func wait() async { suspended = true; await withCheckedContinuation { continuation = $0 } }
    func untilSuspended() async { while !suspended { await Task.yield() } }
    func release() { continuation?.resume(); continuation = nil }
}
private actor FakeGateway: BookingGateway {
    var course: Occurrence; var posts = 0; var reads = 0; var studioIDs: [Int] = []
    var loginFailure: BookingError?; var postLosesReply = false; var confirmPost = true
    var gate: Gate?; var afterLogin: (@Sendable () -> Void)?; var cancelOnPost = false
    var markerCheck: (@Sendable () throws -> Void)?
    init(_ course: Occurrence) { self.course = course }
    func login(_ credentials: Credentials) async throws { if let loginFailure { throw loginFailure }; afterLogin?() }
    func courses(day: Date, studioID: Int) async throws -> [Occurrence] {
        reads += 1; studioIDs.append(studioID)
        if let gate { self.gate = nil; await gate.wait() }; return [course]
    }
    func join(_ course: Occurrence) async throws {
        try markerCheck?(); posts += 1
        if cancelOnPost { withUnsafeCurrentTask { $0?.cancel() }; throw CancellationError() }
        self.course.joined = confirmPost
        if postLosesReply { throw BookingError.network }
    }
    func configure(login: BookingError? = nil, loseReply: Bool = false, confirmed: Bool = true, gate: Gate? = nil,
                   afterLogin: (@Sendable () -> Void)? = nil, cancelOnPost: Bool = false,
                   markerCheck: (@Sendable () throws -> Void)? = nil) {
        loginFailure = login; postLosesReply = loseReply; confirmPost = confirmed; self.gate = gate
        self.afterLogin = afterLogin; self.cancelOnPost = cancelOnPost; self.markerCheck = markerCheck
    }
    func counts() -> (Int, Int) { (posts, reads) }
    func studios() -> [Int] { studioIDs }
}

final class BookingTests: XCTestCase {
    private func fixture(_ name: String) throws -> Data {
        try Data(contentsOf: XCTUnwrap(Bundle.module.url(forResource: name, withExtension: "json", subdirectory: "Fixtures")))
    }
    private func occurrence(start: Date, studio: Int = 74, values: [String: Any] = [:]) throws -> Occurrence {
        var root = try XCTUnwrap(JSONSerialization.jsonObject(with: fixture("gruenstadt")) as? [String: Any])
        let days = root["days"] as! [String: Any]
        var course = ((days["data"] as! [[String: Any]])[0]["occurences"] as! [[String: Any]])[0]
        course["start_at"] = Int64(start.timeIntervalSince1970); course["studioId"] = studio
        values.forEach { course[$0.key] = $0.value }
        root["days"] = ["data": [["occurences": [course]]]]
        return try WidgetAPI.parseCourses(JSONSerialization.data(withJSONObject: root))[0]
    }
    private typealias Context = (Clock, Credentials, CoursePlan, MemoryStore, TestVault, FakeGateway)
    private func context(startOffset: TimeInterval = 7200, studioID: Int = 74) throws -> Context {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let credentials = Credentials(email: "test@example.com", password: "not-a-real-password")
        let course = try occurrence(start: now.addingTimeInterval(startOffset), studio: studioID)
        let studio = try JSONDecoder().decode(Studio.self, from: Data("{\"id\":\(studioID),\"name\":\"Selected studio\",\"enableCourses\":true,\"enableCourseJoin\":true}".utf8))
        let plan = try CoursePlan(accountID: credentials.accountID, course: course, studio: studio)
        return (Clock(now), credentials, plan, MemoryStore([plan]), TestVault(credentials), FakeGateway(course))
    }
    private func coordinator(_ c: Context) throws -> BookingCoordinator {
        try BookingCoordinator(store: c.3, vault: c.4, gateway: c.5, clock: { c.0.now() })
    }
    func test64BitIDsAndElapsed24HoursAcrossDST() throws {
        for value in ["2027-03-28T08:00:00Z", "2027-10-31T09:00:00Z"] {
            let course = try occurrence(start: XCTUnwrap(ISO8601DateFormatter().date(from: value)))
            XCTAssertEqual(course.id, 8_400_000_084_641_347)
            XCTAssertEqual(course.start.timeIntervalSince(course.opens), 86_400)
        }
    }
    func testMarkerPrecedesPostAndParticipationIsConfirmedInSavedStudio() async throws {
        let c = try context(studioID: 47)
        await c.5.configure(markerCheck: { XCTAssertEqual(try c.3.load()[0].status, .verifyRequired) })
        let engine = try coordinator(c); let events = await engine.runDue()
        XCTAssertEqual(events.first?.status, .booked); XCTAssertEqual(try c.3.load()[0].status, .booked)
        let counts = await c.5.counts(); XCTAssertEqual(counts.0, 1)
        let studios = await c.5.studios(); XCTAssertEqual(studios, [47,47])
    }
    func testEarlyAndExpiredPlansNeverReachGateway() async throws {
        for offset: TimeInterval in [172_800, -1] {
            let c = try context(startOffset: offset); let engine = try coordinator(c); _ = await engine.runDue()
            let counts = await c.5.counts(); XCTAssertEqual(counts.0, 0); XCTAssertEqual(counts.1, 0)
            XCTAssertEqual(try c.3.load()[0].status, offset < 0 ? .expired : .planned)
        }
    }
    func testNetworkFailureBeforePostIsAutomaticallyRetriedLater() async throws {
        let c = try context(); await c.5.configure(login: .network)
        let engine = try coordinator(c); _ = await engine.runDue()
        XCTAssertEqual(try c.3.load()[0].status, .retryWaiting)
        _ = await engine.runDue(); let before = await c.5.counts(); XCTAssertEqual(before.0, 0)
        c.0.advance(301); await c.5.configure(); _ = await engine.runDue()
        XCTAssertEqual(try c.3.load()[0].status, .booked)
    }
    func testLostPostReplyDoesNotRepeatPost() async throws {
        let c = try context(); await c.5.configure(loseReply: true)
        let engine = try coordinator(c); _ = await engine.runDue(); _ = await engine.runDue()
        let counts = await c.5.counts(); XCTAssertEqual(counts.0, 1); XCTAssertEqual(try c.3.load()[0].status, .booked)
    }
    func testAmbiguousAttemptProcessRestartAndUserResumeRemainReadOnly() async throws {
        let c = try context(); await c.5.configure(loseReply: true, confirmed: false)
        let first = try coordinator(c); _ = await first.runDue()
        XCTAssertEqual(try c.3.load()[0].status, .verifyRequired)
        c.0.advance(901); let restarted = try coordinator(c); _ = await restarted.runDue()
        try await restarted.resume(c.2.id); _ = await restarted.runDue()
        let counts = await c.5.counts(); XCTAssertEqual(counts.0, 1)
        XCTAssertEqual(try c.3.load()[0].status, .verifyRequired)
    }
    func testVerificationBackoffEventuallyStopsScheduling() async throws {
        let c = try context(); await c.5.configure(confirmed: false)
        let engine = try coordinator(c); _ = await engine.runDue()
        for _ in 0..<4 { c.0.advance(901); _ = await engine.runDue() }
        let next = await engine.nextWakeDate(); XCTAssertNil(next)
        let counts = await c.5.counts(); XCTAssertEqual(counts.0, 1)
    }
    func testFailedMarkerWritePreventsSubmission() async throws {
        let c = try context(); c.3.rejectMarker = true
        let engine = try coordinator(c); _ = await engine.runDue()
        let counts = await c.5.counts(); XCTAssertEqual(counts.0, 0); XCTAssertEqual(try c.3.load()[0].status, .planned)
    }
    func testAuthenticationFailureStopsAutomaticRetries() async throws {
        let c = try context(); await c.5.configure(login: .authentication)
        let engine = try coordinator(c); _ = await engine.runDue(); c.0.advance(600); _ = await engine.runDue()
        XCTAssertEqual(try c.3.load()[0].status, .actionRequired)
        let counts = await c.5.counts(); XCTAssertEqual(counts.0, 0)
    }
    func testWrongAccountAndChangedCredentialsPreventPost() async throws {
        let c = try context(); c.4.set(Credentials(email: "other@example.com", password: "test"))
        let engine = try coordinator(c); _ = await engine.runDue()
        XCTAssertEqual(try c.3.load()[0].status, .actionRequired)
        let d = try context()
        await d.5.configure(afterLogin: { d.4.set(Credentials(accountID: d.1.accountID, email: d.1.email, password: "new")) })
        let changed = try coordinator(d); _ = await changed.runDue()
        let counts = await d.5.counts(); XCTAssertEqual(counts.0, 0); XCTAssertEqual(try d.3.load()[0].status, .retryWaiting)
    }
    func testDeletionDuringNetworkCallPreventsPostAndResurrection() async throws {
        let c = try context(); let gate = Gate(); await c.5.configure(gate: gate)
        let engine = try coordinator(c); let operation = Task { await engine.runDue() }
        await gate.untilSuspended(); try await engine.remove(c.2.id); await gate.release(); _ = await operation.value
        XCTAssertTrue(try c.3.load().isEmpty); let counts = await c.5.counts(); XCTAssertEqual(counts.0, 0)
    }
    func testExpirationDuringPostPreservesReadOnlyMarker() async throws {
        let c = try context(); await c.5.configure(cancelOnPost: true)
        let engine = try coordinator(c); let operation = Task { await engine.runDue() }; _ = await operation.value
        XCTAssertEqual(try c.3.load()[0].status, .verifyRequired)
        c.0.advance(901); await c.5.configure(confirmed: false); _ = await engine.runDue()
        let counts = await c.5.counts(); XCTAssertEqual(counts.0, 1)
    }
    func testWrongStudioFullPrivateAndCancelledCoursesAreRefused() throws {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        for values: [String: Any] in [["private": true], ["canceled_at": 1], ["attendees_count": 100], ["enableJoin": false], ["is_livestream": true], ["join_open_prior_start_seconds": 3600]] {
            XCTAssertThrowsError(try occurrence(start: now.addingTimeInterval(3600), values: values).checkBookable(now: now))
        }
        let c = try context(); XCTAssertThrowsError(try c.2.match(in: [occurrence(start: c.2.start, studio: 47)]))
    }
    func testExternalAndMismatchedJoinLinksAreRejected() throws {
        for path in ["https://other.example/join", "//other.example/join", "/widgets/v1/classes/occurences/42"] {
            let course = try occurrence(start: Date().addingTimeInterval(3600), values: ["links": [["rel": "joinWidgetOccurence", "method": "POST", "uri": path]]])
            XCTAssertThrowsError(try course.joinPath())
        }
    }
    func testJSONStoreRoundTripAndCorruptionDoesNotOverwriteFile() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let file = directory.appendingPathComponent("plans.json"); let store = JSONPlanStore(file: file)
        var plan = try context().2; plan.status = .verifyRequired
        try store.save([plan]); XCTAssertEqual(try JSONPlanStore(file: file).load(), [plan])
        let corrupt = Data("broken".utf8); try corrupt.write(to: file)
        XCTAssertThrowsError(try store.load()); XCTAssertEqual(try Data(contentsOf: file), corrupt)
    }
}
