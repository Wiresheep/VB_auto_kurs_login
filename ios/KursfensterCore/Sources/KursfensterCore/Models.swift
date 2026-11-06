import Foundation

public enum BookingError: Error, Equatable, Sendable {
    case network, authentication, forbidden, invalidResponse, changedCourse, unavailableCourse
    case wrongAccount, credentialsChanged, credentialsMissing, credentialsLocked, invalidLink
}

public struct Credentials: Codable, Equatable, Sendable {
    public let accountID: UUID
    public let revision: UUID
    public let email: String
    public let password: String
    public init(accountID: UUID = UUID(), revision: UUID = UUID(), email: String, password: String) {
        self.accountID = accountID; self.revision = revision; self.email = email; self.password = password
    }
}

public protocol CredentialVault: Sendable {
    func load() throws -> Credentials
}

public struct Studio: Codable, Equatable, Identifiable, Sendable {
    public let id: Int
    public let name: String
    public let enableCourses: Bool
    public let enableCourseJoin: Bool
    public var isAvailable: Bool { id > 0 && !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && enableCourses && enableCourseJoin }
}

public struct APIReference: Codable, Equatable, Sendable {
    public let rel: String
    public let uri: String
    public let method: String
}

public struct Occurrence: Codable, Equatable, Identifiable, Sendable {
    public let id: Int64
    public let name: String
    public let studioId: Int
    public let start_at: Int64
    public let end_at: Int64?
    public var joined: Bool
    public let canceled_at: Int64?
    public let `private`: Bool
    public let is_livestream: Bool
    public let enableJoin: Bool
    public let join_open_prior_start_seconds: Int64
    public let attendees_count: Int?
    public let max_participants: Int?
    public let max_joins_per_day: Int?
    public let max_joins_per_week: Int?
    public let joins_today: Int?
    public let joins_weekly: Int?
    public let links: [APIReference]
    public var start: Date { Date(timeIntervalSince1970: TimeInterval(start_at)) }
    public var opens: Date { start.addingTimeInterval(-86_400) }
    public func joinPath() throws -> String {
        let references = links.filter { $0.rel == "joinWidgetOccurence" }
        let expected = "/widgets/v1/classes/occurences/\(id)"
        guard id > 0, references.count == 1, references[0].method == "POST", references[0].uri == expected else {
            throw BookingError.invalidLink
        }
        return expected
    }
    public func isOffered(studioID: Int, day: Date, now: Date) -> Bool {
        studioId == studioID && start > now && StudioTime.calendar.isDate(start, inSameDayAs: day)
            && canceled_at == nil && !`private` && !is_livestream && enableJoin && join_open_prior_start_seconds == 86_400
    }
    public func checkBookable(now: Date) throws {
        guard canceled_at == nil, !`private`, !is_livestream, enableJoin, join_open_prior_start_seconds == 86_400,
              opens <= now, start > now else { throw BookingError.unavailableCourse }
        if let count = attendees_count, let maximum = max_participants, count >= maximum { throw BookingError.unavailableCourse }
        if let count = joins_today, let maximum = max_joins_per_day, count >= maximum { throw BookingError.unavailableCourse }
        if let count = joins_weekly, let maximum = max_joins_per_week, count >= maximum { throw BookingError.unavailableCourse }
        _ = try joinPath()
    }
}

public enum StudioTime {
    public static var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Europe/Berlin")!
        return calendar
    }
}

public struct CoursePlan: Codable, Equatable, Identifiable, Sendable {
    public enum Status: String, Codable, Sendable {
        case planned, retryWaiting, verifyRequired, booked, expired, actionRequired
        public var title: String {
            switch self {
            case .planned: return "Automatische Anmeldung geplant"
            case .retryWaiting: return "Wartet auf den nächsten Versuch"
            case .verifyRequired: return "Anmeldung bitte prüfen"
            case .booked: return "Angemeldet"
            case .expired: return "Kurs hat begonnen"
            case .actionRequired: return "Bitte Webzugang oder Kurs prüfen"
            }
        }
    }
    public let id: UUID
    public let accountID: UUID
    public let occurrenceID: Int64
    public let studioID: Int
    public let studioName: String
    public let courseName: String
    public let start: Date
    public var status: Status = .planned
    public var attempts = 0
    public var verificationAttempts = 0
    public var nextAttempt: Date?
    public var message = "iOS führt die Anmeldung bei verfügbarer Hintergrundzeit aus."
    public var opens: Date { start.addingTimeInterval(-86_400) }
    public init(id: UUID = UUID(), accountID: UUID, course: Occurrence, studio: Studio) throws {
        guard studio.id == course.studioId, studio.isAvailable, course.id > 0,
              course.join_open_prior_start_seconds == 86_400 else { throw BookingError.changedCourse }
        _ = try course.joinPath()
        self.id = id; self.accountID = accountID; occurrenceID = course.id; studioID = studio.id
        studioName = studio.name.trimmingCharacters(in: .whitespacesAndNewlines); courseName = course.name; start = course.start
    }
    public func match(in courses: [Occurrence]) throws -> Occurrence {
        let matches = courses.filter { $0.id == occurrenceID }
        guard matches.count == 1, let course = matches.first, course.studioId == studioID,
              course.start == start, course.name == courseName else { throw BookingError.changedCourse }
        return course
    }
    public func isDue(at now: Date) -> Bool {
        switch status {
        case .planned, .retryWaiting: return opens <= now && (nextAttempt ?? opens) <= now
        case .verifyRequired: return nextAttempt.map { $0 <= now } ?? false
        default: return false
        }
    }
}
