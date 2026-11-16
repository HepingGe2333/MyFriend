import XCTest
@testable import YapKit

final class PlaceholderTests: XCTestCase {
    func testPackageBuilds() {
        XCTAssertEqual(YapKitInfo.version, "0.10.0")
    }
}
