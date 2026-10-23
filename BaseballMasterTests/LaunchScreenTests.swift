import XCTest
import UIKit
import CryptoKit
@testable import BaseballMaster

private actor LaunchTransportStub: LaunchScreenTransport {
    var config: Data
    var picture: Data
    var fail = false
    private(set) var reads = 0
    init(_ config: LaunchScreenConfiguration, picture: Data = Data()) throws { self.config = try JSONEncoder().encode(config); self.picture = picture }
    func configuration() throws -> Data { reads += 1; if fail { throw URLError(.notConnectedToInternet) }; return config }
    func image(_ descriptor: LaunchBackgroundImage) throws -> Data { if fail { throw URLError(.notConnectedToInternet) }; return picture }
    func setFailure() { fail = true }
}

private actor SuspendedLaunchTransport: LaunchScreenTransport {
    private var pending: CheckedContinuation<Data, Error>?
    private(set) var requested = false
    func configuration() async throws -> Data {
        requested = true
        return try await withCheckedThrowingContinuation { pending = $0 }
    }
    func image(_ descriptor: LaunchBackgroundImage) throws -> Data { throw URLError(.cancelled) }
    func fail() { pending?.resume(throwing: URLError(.cancelled)); pending = nil }
}

@MainActor final class LaunchScreenTests: XCTestCase {
    private func disk() throws -> LaunchScreenDiskCache {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("BM-Launch-\(UUID())", isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: url) }
        return .init(directory: url)
    }
    private func picture() -> Data {
        UIGraphicsImageRenderer(size: CGSize(width: 64, height: 128)).image { context in
            UIColor.systemTeal.setFill(); context.fill(CGRect(x: 0, y: 0, width: 64, height: 128))
        }.pngData()!
    }
    private func descriptor(_ data: Data) -> LaunchBackgroundImage {
        let hash = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
        let image = UIImage(data: data)!.cgImage!
        return .init(path: "/api/v3/app/launch-assets/\(hash).png", sha256: hash, mimeType: "image/png", byteCount: data.count, width: image.width, height: image.height)
    }
    private func seed(_ config: LaunchScreenConfiguration, in cache: LaunchScreenDiskCache, picture: Data? = nil) throws {
        if let picture, let image = config.backgroundImage { try picture.write(to: cache.imageURL(image)) }
        try JSONEncoder().encode(config).write(to: cache.manifest, options: .atomic)
    }

    func testMissingAndMalformedCacheUseBundledGreenPresentation() throws {
        let cache = try disk()
        XCTAssertEqual(cache.presentation()?.configuration, .bundled)
        try Data("not-json".utf8).write(to: cache.manifest)
        XCTAssertEqual(cache.presentation()?.configuration, .bundled)
    }
    func testCachedCampaignSurvivesRestartAndDownloadsNeverReplaceVisiblePresentation() async throws {
        let cache = try disk(), data = picture()
        var old = LaunchScreenConfiguration.bundled; old.message = "缓存中的文案"; old.backgroundImage = descriptor(data)
        try seed(old, in: cache, picture: data)
        let visible = try XCTUnwrap(cache.presentation())
        var new = old; new.revision = 2; new.message = "下一次启动的新文案"
        let transport = try LaunchTransportStub(new, picture: data)
        let repository = LaunchScreenRepository(cache: cache, transport: transport)
        let success = await repository.refresh(); XCTAssertTrue(success)
        XCTAssertEqual(visible.configuration.message, old.message)
        XCTAssertNotNil(visible.image)
        XCTAssertEqual(LaunchScreenDiskCache(directory: cache.directory).presentation()?.configuration.message, new.message)
    }
    func testOfflineFailurePreservesLastValidCache() async throws {
        let cache = try disk(); var saved = LaunchScreenConfiguration.bundled; saved.message = "离线可用"
        try seed(saved, in: cache)
        let transport = try LaunchTransportStub(.bundled); await transport.setFailure()
        let success = await LaunchScreenRepository(cache: cache, transport: transport).refresh()
        XCTAssertFalse(success); XCTAssertEqual(cache.presentation()?.configuration.message, saved.message)
    }
    func testHashMismatchAndBadImageNeverPublishNewMessage() async throws {
        let cache = try disk(); var old = LaunchScreenConfiguration.bundled; old.message = "继续使用旧图"
        try seed(old, in: cache)
        var candidate = old; candidate.message = "不能提前生效"; candidate.backgroundImage = descriptor(picture())
        let transport = try LaunchTransportStub(candidate, picture: Data("broken-image".utf8))
        let success = await LaunchScreenRepository(cache: cache, transport: transport).refresh()
        XCTAssertFalse(success); XCTAssertEqual(cache.presentation()?.configuration.message, old.message)
        try seed(candidate, in: cache, picture: Data("broken-image".utf8))
        XCTAssertEqual(cache.presentation()?.configuration, .bundled)
    }
    func testNativeDecoderRejectsImageWithWrongDimensionsEvenIfHashMatches() throws {
        let data = picture(); var metadata = descriptor(data); metadata.width += 1
        XCTAssertThrowsError(try LaunchScreenDiskCache.decode(data, descriptor: metadata))
        metadata = descriptor(data); metadata.mimeType = "image/jpeg"; metadata.path = metadata.path.replacingOccurrences(of: ".png", with: ".jpg")
        XCTAssertThrowsError(try LaunchScreenDiskCache.decode(data, descriptor: metadata))
    }
    func testFutureAndExpiredCampaignsFallBackAndDisabledConfigurationSkips() throws {
        let cache = try disk(), date = Date(timeIntervalSince1970: 1_800_000_000)
        var config = LaunchScreenConfiguration.bundled; config.message = "限时活动"; config.startsAt = 1_800_000_001_000
        try seed(config, in: cache); XCTAssertEqual(cache.presentation(at: date)?.configuration, .bundled)
        config.startsAt = nil; config.endsAt = 1_800_000_000_000
        try seed(config, in: cache); XCTAssertEqual(cache.presentation(at: date)?.configuration, .bundled)
        config.endsAt = nil; config.enabled = false
        try seed(config, in: cache); XCTAssertNil(cache.presentation(at: date))
    }
    func testUnsafeURLsOversizedImagesAndUnknownSchemasAreRejected() throws {
        var config = LaunchScreenConfiguration.bundled
        config.schemaVersion = 2; XCTAssertThrowsError(try config.validate())
        config = .bundled; config.displayDurationMs = 9000; XCTAssertThrowsError(try config.validate())
        config = .bundled; config.message = String(repeating: "长", count: 41); XCTAssertThrowsError(try config.validate())
        config = .bundled; config.message = "一\n二\n三"; XCTAssertThrowsError(try config.validate())
        config = .bundled; config.backgroundImage = descriptor(picture()); config.backgroundImage!.path = "https://evil.example/image.png"
        XCTAssertThrowsError(try config.validate())
        config.backgroundImage = descriptor(picture()); config.backgroundImage!.byteCount = 3 * 1024 * 1024 + 1
        XCTAssertThrowsError(try config.validate())
    }
    func testDisabledConfigurationCanBeCachedWithoutDownloadingPicture() async throws {
        let cache = try disk(); var config = LaunchScreenConfiguration.bundled
        config.enabled = false; config.backgroundImage = descriptor(picture())
        let success = await LaunchScreenRepository(cache: cache, transport: try LaunchTransportStub(config)).refresh()
        XCTAssertTrue(success); XCTAssertNil(cache.presentation())
    }
    func testSkipAndRepeatedStartNeverReplayOrWaitForNetwork() async throws {
        let cache = try disk(); var config = LaunchScreenConfiguration.bundled; config.displayDurationMs = 300
        try seed(config, in: cache)
        let transport = try LaunchTransportStub(.bundled)
        let controller = LaunchScreenController(cache: cache, transport: transport, refreshEnabled: false)
        XCTAssertNotNil(controller.presentation); controller.dismiss(); await controller.start(); await controller.start()
        XCTAssertNil(controller.presentation); let reads = await transport.reads; XCTAssertEqual(reads, 0)
        let timed = LaunchScreenController(cache: cache, transport: transport, refreshEnabled: false)
        await timed.start(); XCTAssertNil(timed.presentation)
    }
    func testHungNetworkCannotDelayEnteringHome() async throws {
        let cache = try disk(); var config = LaunchScreenConfiguration.bundled; config.displayDurationMs = 300
        try seed(config, in: cache)
        let transport = SuspendedLaunchTransport()
        let controller = LaunchScreenController(cache: cache, transport: transport)
        await controller.start()
        XCTAssertNil(controller.presentation, "Presentation ends even though the network request is still suspended")
        let requested = await transport.requested; XCTAssertTrue(requested)
        await transport.fail()
    }
}
