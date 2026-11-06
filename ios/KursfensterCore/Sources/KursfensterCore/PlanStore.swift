import Foundation

public protocol PlanPersistence: Sendable {
    func load() throws -> [CoursePlan]
    func save(_ plans: [CoursePlan]) throws
}

public struct JSONPlanStore: PlanPersistence, Sendable {
    public let file: URL
    public init(file: URL) { self.file = file }
    public func load() throws -> [CoursePlan] {
        guard FileManager.default.fileExists(atPath: file.path) else { return [] }
        let decoder = JSONDecoder(); decoder.dateDecodingStrategy = .millisecondsSince1970
        return try decoder.decode([CoursePlan].self, from: Data(contentsOf: file))
    }
    public func save(_ plans: [CoursePlan]) throws {
        let directory = file.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let encoder = JSONEncoder(); encoder.dateEncodingStrategy = .millisecondsSince1970
        let data = try encoder.encode(plans)
        #if os(iOS)
        // Remains accessible for a locked-device booking after the first unlock following boot.
        try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: directory.path)
        try data.write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        #else
        try data.write(to: file, options: .atomic)
        #endif
    }
}
