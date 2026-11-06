// Validates project syntax/references, not iOS framework types. Usage: swift ios/scripts/validate-project.swift ios
import Foundation
let root = URL(fileURLWithPath: CommandLine.arguments[1])
let data = try Data(contentsOf: root.appendingPathComponent("Kursfenster.xcodeproj/project.pbxproj"))
var format = PropertyListSerialization.PropertyListFormat.openStep
let project = try PropertyListSerialization.propertyList(from: data, options: [], format: &format) as! [String: Any]
let objects = project["objects"] as! [String: [String: Any]]
let identifier = project["rootObject"] as! String
precondition(objects[identifier]?["isa"] as? String == "PBXProject")
func checkReferences(_ value: Any) {
    if let text = value as? String, text.count == 24, text.allSatisfy({ $0.isHexDigit }) { precondition(objects[text] != nil, "Undefined project reference") }
    if let array = value as? [Any] { array.forEach(checkReferences) }
    if let dictionary = value as? [String: Any] { dictionary.values.forEach(checkReferences) }
}
objects.values.forEach(checkReferences)
for object in objects.values where object["isa"] as? String == "PBXFileReference" {
    guard object["sourceTree"] as? String == "<group>", let path = object["path"] as? String else { continue }
    precondition(FileManager.default.fileExists(atPath: root.appendingPathComponent("App").appendingPathComponent(path).path))
}
let info = try PropertyListSerialization.propertyList(from: Data(contentsOf: root.appendingPathComponent("App/Info.plist")), options: [], format: nil) as! [String: Any]
precondition(Set(info["BGTaskSchedulerPermittedIdentifiers"] as! [String]) == Set(["de.wiresheep.kursfenster.booking.refresh", "de.wiresheep.kursfenster.booking.processing"]))
precondition(Set(info["UIBackgroundModes"] as! [String]) == Set(["fetch", "processing"]))
print("Xcode project parsed; \(objects.count) objects, all references and source files valid; background identifiers match.")
