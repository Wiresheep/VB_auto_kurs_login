// swift-tools-version: 5.10
import PackageDescription

let package = Package(
    name: "KursfensterCore",
    platforms: [.iOS(.v17), .macOS(.v13)],
    products: [.library(name: "KursfensterCore", targets: ["KursfensterCore"])],
    targets: [
        .target(name: "KursfensterCore"),
        .testTarget(name: "KursfensterCoreTests", dependencies: ["KursfensterCore"], resources: [.copy("Fixtures")])
    ]
)
