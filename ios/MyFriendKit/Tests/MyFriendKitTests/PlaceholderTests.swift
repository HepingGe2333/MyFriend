import XCTest
@testable import MyFriendKit

final class PlaceholderTests: XCTestCase {
    func testPackageBuilds() {
        XCTAssertEqual(MyFriendKitInfo.version, "0.9.1")
    }
}
