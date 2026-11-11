import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public protocol BookingGateway: Sendable {
    func login(_ credentials: Credentials) async throws
    func courses(day: Date, studioID: Int) async throws -> [Occurrence]
    func join(_ course: Occurrence) async throws
}

public struct WidgetRequest: Sendable {
    public let method: String
    public let url: URL
    public let headers: [String: String]
    public let body: Data?
}
public struct WidgetResponse: Sendable {
    public let status: Int
    public let data: Data
    public init(status: Int, data: Data) { self.status = status; self.data = data }
}
public protocol WidgetTransport: Sendable {
    func send(_ request: WidgetRequest) async throws -> WidgetResponse
}

private final class NoRedirects: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}
public final class URLSessionTransport: WidgetTransport, @unchecked Sendable {
    private let session: URLSession
    public init() {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 10
        configuration.timeoutIntervalForResource = 20
        configuration.httpShouldSetCookies = false
        configuration.urlCache = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        session = URLSession(configuration: configuration, delegate: NoRedirects(), delegateQueue: nil)
    }
    public func send(_ request: WidgetRequest) async throws -> WidgetResponse {
        try Task.checkCancellation()
        guard request.url.scheme == "https", request.url.host == "widget.actinate.com", request.url.user == nil,
              request.url.password == nil, request.url.port == nil else { throw BookingError.invalidLink }
        var native = URLRequest(url: request.url)
        native.httpMethod = request.method; native.httpBody = request.body; native.httpShouldHandleCookies = false
        for (key, value) in request.headers { native.setValue(value, forHTTPHeaderField: key) }
        let (data, response) = try await session.data(for: native)
        guard let http = response as? HTTPURLResponse, data.count <= 4 * 1024 * 1024 else { throw BookingError.invalidResponse }
        return WidgetResponse(status: http.statusCode, data: data)
    }
}

public actor WidgetAPI: BookingGateway {
    private let transport: any WidgetTransport
    private var accessToken: String?
    public init(transport: any WidgetTransport = URLSessionTransport()) { self.transport = transport }
    private func call(_ method: String, path: String, query: [URLQueryItem] = [], form: [String: String]? = nil) async throws -> Data {
        guard path.hasPrefix("/"), !path.hasPrefix("//"), !path.contains("\\"), !path.contains("#"), !path.contains("?") else { throw BookingError.invalidLink }
        var components = URLComponents(); components.scheme = "https"; components.host = "widget.actinate.com"; components.path = path
        components.queryItems = query + [URLQueryItem(name: "tenant", value: "venicebeach")]
        // URLComponents permits literal '+', but the provider's form-query decoder treats it as a space.
        components.percentEncodedQuery = components.percentEncodedQuery?.replacingOccurrences(of: "+", with: "%2B")
        guard let url = components.url else { throw BookingError.invalidLink }
        var headers = ["Accept": "application/json"]
        if let token = accessToken { headers["Authorization"] = "Bearer \(token)" }
        var body: Data?
        if method == "POST" {
            headers["Content-Type"] = "application/x-www-form-urlencoded"
            body = form.map { Self.formBody($0) } ?? Data()
        }
        let response: WidgetResponse
        do { response = try await transport.send(WidgetRequest(method: method, url: url, headers: headers, body: body)) }
        catch is CancellationError { throw CancellationError() }
        catch let error as BookingError { throw error }
        catch { throw BookingError.network }
        switch response.status {
        case 200..<300: return response.data
        case 401: throw BookingError.authentication
        case 403: throw BookingError.forbidden
        case 400 where path.hasPrefix("/oauth/"), 422 where path.hasPrefix("/oauth/"): throw BookingError.authentication
        case 408, 429, 500...599: throw BookingError.network
        default: throw BookingError.httpStatus(response.status)
        }
    }
    private static func formBody(_ values: [String: String]) -> Data {
        let allowed = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
        let text = values.keys.sorted().map { key in
            "\(key.addingPercentEncoding(withAllowedCharacters: allowed)!)=\(values[key]!.addingPercentEncoding(withAllowedCharacters: allowed)!)"
        }.joined(separator: "&")
        return Data(text.utf8)
    }
    private func decode<T: Decodable>(_ type: T.Type, from data: Data) throws -> T {
        do { return try JSONDecoder().decode(type, from: data) } catch { throw BookingError.invalidResponse }
    }
    public func login(_ credentials: Credentials) async throws {
        accessToken = nil
        struct Token: Decodable { let access_token: String }
        let data = try await call("POST", path: "/oauth/v6/oauth/token", form: [
            "username": credentials.email, "password": credentials.password, "grant_type": "password",
            "app_version": "widget", "os": "iOS", "os_version": ProcessInfo.processInfo.operatingSystemVersionString
        ])
        let token = try decode(Token.self, from: data).access_token
        guard !token.isEmpty else { throw BookingError.invalidResponse }
        accessToken = token
        struct Access: Decodable { let attendStudioClasses: Bool? }
        struct User: Decodable { let accessTypeSettings: Access }
        struct Reply: Decodable { let user: User }
        let reply = try await call("GET", path: "/widgets/v1/users/me")
        guard try decode(Reply.self, from: reply).user.accessTypeSettings.attendStudioClasses == true else {
            accessToken = nil; throw BookingError.forbidden
        }
    }
    public func studios() async throws -> [Studio] {
        struct Container: Decodable { let data: [Studio] }
        struct Reply: Decodable { let tenant: String; let studios: Container }
        let data = try await call("GET", path: "/widgets/v1/classes/tenantInfo", query: [
            URLQueryItem(name: "key", value: "dQwvUoeuoACEFD2B")
        ] + Self.filterQuery)
        let reply = try decode(Reply.self, from: data)
        guard reply.tenant == "venicebeach" else { throw BookingError.invalidResponse }
        let studios = reply.studios.data.filter(\.isAvailable)
        guard Set(studios.map(\.id)).count == studios.count else { throw BookingError.invalidResponse }
        return studios.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
    }
    public func courses(day: Date, studioID: Int) async throws -> [Occurrence] {
        guard studioID > 0 else { throw BookingError.changedCourse }
        let start = StudioTime.calendar.startOfDay(for: day)
        guard let end = StudioTime.calendar.date(byAdding: .day, value: 1, to: start) else { throw BookingError.invalidResponse }
        let seconds = StudioTime.calendar.timeZone.secondsFromGMT(for: day)
        let offset = String(format: "%@%02d:%02d", seconds < 0 ? "-" : "+", abs(seconds) / 3600, abs(seconds) % 3600 / 60)
        let data = try await call("GET", path: "/widgets/v1/classes/occurences", query: [
            URLQueryItem(name: "startAt", value: String(Int64(start.timeIntervalSince1970))),
            URLQueryItem(name: "endAt", value: String(Int64(end.timeIntervalSince1970) - 1)),
            URLQueryItem(name: "studioIds[]", value: String(studioID)), URLQueryItem(name: "timzoneForDayBuckets", value: offset)
        ] + Self.filterQuery)
        return try Self.parseCourses(data)
    }
    public static func parseCourses(_ data: Data) throws -> [Occurrence] {
        struct Day: Decodable { let occurences: [Occurrence] }
        struct Days: Decodable { let data: [Day] }
        struct Reply: Decodable { let days: Days }
        do { return try JSONDecoder().decode(Reply.self, from: data).days.data.flatMap(\.occurences) }
        catch { throw BookingError.invalidResponse }
    }
    public func join(_ course: Occurrence) async throws {
        _ = try await call("POST", path: course.joinPath())
    }
    private static let filterQuery = ["courseCategoryFilterEnabled", "courseTrainingGoalFilterEnabled", "tenantLevelFilterEnabled", "courseFilterEnabled"]
        .map { URLQueryItem(name: $0, value: "0") }
}
