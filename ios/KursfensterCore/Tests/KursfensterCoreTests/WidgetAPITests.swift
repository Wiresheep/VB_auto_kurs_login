import XCTest
@testable import KursfensterCore

private actor ScriptTransport: WidgetTransport {
    private var replies: [WidgetResponse]
    private var requests: [WidgetRequest] = []
    init(_ replies: [WidgetResponse]) { self.replies = replies }
    func send(_ request: WidgetRequest) async throws -> WidgetResponse {
        requests.append(request)
        guard !replies.isEmpty else { throw BookingError.network }
        return replies.removeFirst()
    }
    func captured() -> [WidgetRequest] { requests }
}

final class WidgetAPITests: XCTestCase {
    private func fixture(_ name: String) throws -> Data {
        try Data(contentsOf: XCTUnwrap(Bundle.module.url(forResource: name, withExtension: "json", subdirectory: "Fixtures")))
    }
    func testLoginFormBearerRequestsAndProviderSuppliedJoinPath() async throws {
        let fixture = try fixture("gruenstadt")
        let transport = ScriptTransport([
            WidgetResponse(status: 200, data: Data("{\"access_token\":\"fake-test-token\"}".utf8)),
            WidgetResponse(status: 200, data: Data("{\"user\":{\"accessTypeSettings\":{\"attendStudioClasses\":true}}}".utf8)),
            WidgetResponse(status: 200, data: fixture), WidgetResponse(status: 204, data: Data())
        ])
        let api = WidgetAPI(transport: transport)
        try await api.login(Credentials(email: "a+b@example.com", password: "p&=+ö"))
        let courses = try await api.courses(day: Date(timeIntervalSince1970: 1_794_000_000), studioID: 47)
        try await api.join(courses[0])
        let requests = await transport.captured()
        XCTAssertEqual(requests.count, 4)
        XCTAssertEqual(requests[0].url.path, "/oauth/v6/oauth/token")
        let form = String(decoding: requests[0].body!, as: UTF8.self)
        XCTAssertTrue(form.contains("username=a%2Bb%40example.com")); XCTAssertTrue(form.contains("password=p%26%3D%2B%C3%B6"))
        XCTAssertNil(requests[0].headers["Authorization"])
        XCTAssertEqual(requests[1].headers["Authorization"], "Bearer fake-test-token")
        let query = URLComponents(url: requests[2].url, resolvingAgainstBaseURL: false)!.queryItems!
        XCTAssertEqual(query.first { $0.name == "studioIds[]" }?.value, "47")
        XCTAssertEqual(query.first { $0.name == "tenant" }?.value, "venicebeach")
        XCTAssertEqual(requests[3].method, "POST")
        XCTAssertEqual(requests[3].url.path, "/widgets/v1/classes/occurences/8400000084641347")
        XCTAssertTrue(requests.allSatisfy { $0.url.scheme == "https" && $0.url.host == "widget.actinate.com" })
    }
    func testAuthenticationAndMissingMembershipAreNotTransientRetries() async throws {
        let denied = WidgetAPI(transport: ScriptTransport([WidgetResponse(status: 400, data: Data())]))
        do { try await denied.login(Credentials(email: "test", password: "test")); XCTFail() }
        catch { XCTAssertEqual(error as? BookingError, .authentication) }
        let missingAccess = WidgetAPI(transport: ScriptTransport([
            WidgetResponse(status: 200, data: Data("{\"access_token\":\"fake\"}".utf8)),
            WidgetResponse(status: 200, data: Data("{\"user\":{\"accessTypeSettings\":{}}}".utf8))
        ]))
        do { try await missingAccess.login(Credentials(email: "test", password: "test")); XCTFail() }
        catch { XCTAssertEqual(error as? BookingError, .forbidden) }
    }
    func testStudiosFromProviderAndWrongTenantRejected() async throws {
        let data = try fixture("studios")
        let api = WidgetAPI(transport: ScriptTransport([WidgetResponse(status: 200, data: data)]))
        let studios = try await api.studios(); XCTAssertEqual(studios.count, 39)
        XCTAssertEqual(studios.first { $0.id == 47 }?.name, "VeniceBeach Frankenthal")
        var root = try JSONSerialization.jsonObject(with: data) as! [String: Any]; root["tenant"] = "other"
        let wrong = WidgetAPI(transport: ScriptTransport([WidgetResponse(status: 200, data: try JSONSerialization.data(withJSONObject: root))]))
        do { _ = try await wrong.studios(); XCTFail() } catch { XCTAssertEqual(error as? BookingError, .invalidResponse) }
    }
    func testPublicRequestsHaveNoTokenAndInvalidJSONIsNotAccepted() async throws {
        let transport = ScriptTransport([WidgetResponse(status: 200, data: Data("invalid".utf8))])
        let api = WidgetAPI(transport: transport)
        do { _ = try await api.courses(day: Date(), studioID: 74); XCTFail() }
        catch { XCTAssertEqual(error as? BookingError, .invalidResponse) }
        let requests = await transport.captured(); XCTAssertNil(requests[0].headers["Authorization"])
    }
    func testStudioDayRangeHonors23And25HourDays() async throws {
        let data = try fixture("gruenstadt")
        for (day, hours) in [("2027-03-28T12:00:00Z",23), ("2027-10-31T12:00:00Z",25)] {
            let transport = ScriptTransport([WidgetResponse(status: 200, data: data)])
            let api = WidgetAPI(transport: transport)
            _ = try await api.courses(day: XCTUnwrap(ISO8601DateFormatter().date(from: day)), studioID: 74)
            let requests = await transport.captured()
            let query = URLComponents(url: requests[0].url, resolvingAgainstBaseURL: false)!.queryItems!
            let start = Int64(query.first { $0.name == "startAt" }!.value!)!
            let end = Int64(query.first { $0.name == "endAt" }!.value!)!
            XCTAssertEqual(end - start + 1, Int64(hours * 3600))
        }
    }
}
