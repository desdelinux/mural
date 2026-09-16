import XCTest
@testable import MuralCore

@MainActor final class VoiceConnectionRecoveryTests: XCTestCase {
    func testBriefHandoffRecoversAndLaterDisconnectFailsOnce() async throws {
        var failures = 0
        let recovery = VoiceConnectionRecovery(timeout: .milliseconds(40)) { failures += 1 }
        recovery.disconnected()
        recovery.connected()
        try await Task.sleep(for: .milliseconds(60))
        XCTAssertEqual(failures, 0)
        recovery.disconnected()
        try await Task.sleep(for: .milliseconds(70))
        XCTAssertEqual(failures, 1)
        recovery.disconnected()
        try await Task.sleep(for: .milliseconds(60))
        XCTAssertEqual(failures, 1)
    }

    func testRepeatedDisconnectDoesNotExtendDeadline() async throws {
        var failures = 0
        let recovery = VoiceConnectionRecovery(timeout: .milliseconds(60)) { failures += 1 }
        recovery.disconnected()
        try await Task.sleep(for: .milliseconds(40))
        recovery.disconnected()
        try await Task.sleep(for: .milliseconds(40))
        XCTAssertEqual(failures, 1)
    }

    func testClosingAndStartingAnotherCallCancelsOldTimer() async throws {
        var failures = 0
        let recovery = VoiceConnectionRecovery(timeout: .milliseconds(40)) { failures += 1 }
        recovery.disconnected()
        recovery.connected() // close / disconnect
        recovery.disconnected() // next call
        recovery.connected()
        try await Task.sleep(for: .milliseconds(70))
        XCTAssertEqual(failures, 0)
    }
}
