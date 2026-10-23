import SwiftUI
import UIKit
import ImageIO
import CryptoKit

enum LaunchScreenFailure: Error { case invalidConfiguration, invalidImage, invalidResponse, oversized }

struct LaunchBackgroundImage: Codable, Equatable {
    var path: String
    var sha256: String
    var mimeType: String
    var byteCount: Int
    var width: Int
    var height: Int

    func validate() throws {
        let suffix = mimeType == "image/png" ? "png" : "jpg"
        guard ["image/png", "image/jpeg"].contains(mimeType),
              sha256.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil,
              path == "/api/v3/app/launch-assets/\(sha256).\(suffix)",
              byteCount > 0, byteCount <= 3 * 1024 * 1024,
              width > 0, width <= 4096, height > 0, height <= 8192,
              width * height <= 16_000_000 else { throw LaunchScreenFailure.invalidConfiguration }
    }
}

struct LaunchScreenConfiguration: Codable, Equatable {
    var schemaVersion = 1
    var revision = 0
    var updatedAt: Int64? = nil
    var enabled = true
    var message = "每一球 都有迹可循"
    var displayDurationMs = 800
    var startsAt: Int64? = nil
    var endsAt: Int64? = nil
    var backgroundImage: LaunchBackgroundImage? = nil
    static let bundled = LaunchScreenConfiguration()

    func validate() throws {
        guard schemaVersion == 1, revision >= 0, message.unicodeScalars.count <= 40,
              !message.unicodeScalars.contains(where: { ($0.value < 32 && $0.value != 10) || $0.value == 127 }),
              message.components(separatedBy: "\n").count <= 2,
              (300...2000).contains(displayDurationMs),
              [startsAt, endsAt, updatedAt].compactMap({ $0 }).allSatisfy({ (0...4_102_444_800_000).contains($0) }),
              startsAt == nil || endsAt == nil || endsAt! > startsAt! else { throw LaunchScreenFailure.invalidConfiguration }
        try backgroundImage?.validate()
    }

    func isInSchedule(at date: Date) -> Bool {
        let time = Int64(date.timeIntervalSince1970 * 1000)
        return (startsAt == nil || time >= startsAt!) && (endsAt == nil || time < endsAt!)
    }
}

struct LaunchScreenPresentation {
    var configuration: LaunchScreenConfiguration
    var image: UIImage?
    static var bundled: Self { .init(configuration: .bundled, image: nil) }
}

struct LaunchScreenDiskCache: Sendable {
    let directory: URL
    static var standard: Self {
        .init(directory: FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("AppLaunchScreen", isDirectory: true))
    }
    var manifest: URL { directory.appendingPathComponent("configuration.json") }
    func imageURL(_ image: LaunchBackgroundImage) -> URL { directory.appendingPathComponent(image.sha256 + ".image") }

    func configuration() throws -> LaunchScreenConfiguration {
        let data = try Self.read(manifest, limit: 16_384)
        let config = try JSONDecoder().decode(LaunchScreenConfiguration.self, from: data)
        try config.validate()
        return config
    }

    func presentation(at date: Date = Date()) -> LaunchScreenPresentation? {
        do {
            let config = try configuration()
            guard config.enabled else { return nil }
            guard config.isInSchedule(at: date) else { return .bundled }
            if let descriptor = config.backgroundImage {
                let data = try Self.read(imageURL(descriptor), limit: 3 * 1024 * 1024)
                return .init(configuration: config, image: try Self.decode(data, descriptor: descriptor))
            }
            return .init(configuration: config, image: nil)
        } catch { return .bundled }
    }

    static func read(_ url: URL, limit: Int) throws -> Data {
        let attributes = try url.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey])
        guard attributes.isRegularFile == true, let size = attributes.fileSize, size <= limit else { throw LaunchScreenFailure.oversized }
        return try Data(contentsOf: url, options: .mappedIfSafe)
    }

    static func decode(_ data: Data, descriptor: LaunchBackgroundImage) throws -> UIImage {
        try descriptor.validate()
        guard data.count == descriptor.byteCount,
              SHA256.hash(data: data).map({ String(format: "%02x", $0) }).joined() == descriptor.sha256,
              let source = CGImageSourceCreateWithData(data as CFData, nil), CGImageSourceGetCount(source) == 1,
              let type = CGImageSourceGetType(source) as String?,
              type == (descriptor.mimeType == "image/png" ? "public.png" : "public.jpeg"),
              let info = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              (info[kCGImagePropertyPixelWidth] as? NSNumber)?.intValue == descriptor.width,
              (info[kCGImagePropertyPixelHeight] as? NSNumber)?.intValue == descriptor.height,
              let decoded = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceThumbnailMaxPixelSize: 2048,
                kCGImageSourceShouldCacheImmediately: true
              ] as CFDictionary) else { throw LaunchScreenFailure.invalidImage }
        return UIImage(cgImage: decoded)
    }
}

protocol LaunchScreenTransport: Sendable {
    func configuration() async throws -> Data
    func image(_ descriptor: LaunchBackgroundImage) async throws -> Data
}

private final class LaunchScreenRedirectBlocker: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}

struct LaunchScreenHTTPTransport: LaunchScreenTransport {
    static let productionURL = URL(string: "https://baseballmaster.cc/api/v3/app/launch-screen")!
    let endpoint: URL
    private let session: URLSession
    init(endpoint: URL = productionURL) {
        self.endpoint = endpoint
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 8; config.timeoutIntervalForResource = 12
        config.httpShouldSetCookies = false; config.httpCookieStorage = nil; config.urlCache = nil
        session = URLSession(configuration: config, delegate: LaunchScreenRedirectBlocker(), delegateQueue: nil)
    }
    func configuration() async throws -> Data { try await read(endpoint, limit: 16_384, mime: "application/json") }
    func image(_ descriptor: LaunchBackgroundImage) async throws -> Data {
        try descriptor.validate()
        guard let url = URL(string: descriptor.path, relativeTo: endpoint)?.absoluteURL,
              url.scheme == endpoint.scheme, url.host == endpoint.host, url.port == endpoint.port else { throw LaunchScreenFailure.invalidConfiguration }
        return try await read(url, limit: 3 * 1024 * 1024, mime: descriptor.mimeType)
    }
    private func read(_ url: URL, limit: Int, mime: String) async throws -> Data {
        var allowed = url.scheme == "https"
        #if DEBUG
        allowed = allowed || (url.scheme == "http" && ["127.0.0.1", "localhost", "::1"].contains(url.host ?? ""))
        #endif
        guard allowed, url.user == nil, url.password == nil, url.fragment == nil else { throw LaunchScreenFailure.invalidConfiguration }
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData)
        request.setValue(mime, forHTTPHeaderField: "Accept")
        let (bytes, response) = try await session.bytes(for: request)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200, http.url == url,
              http.mimeType?.lowercased() == mime, http.expectedContentLength <= limit else { throw LaunchScreenFailure.invalidResponse }
        var data = Data()
        for try await byte in bytes { guard data.count < limit else { throw LaunchScreenFailure.oversized }; data.append(byte) }
        return data
    }
}

actor LaunchScreenRepository {
    let cache: LaunchScreenDiskCache
    let transport: any LaunchScreenTransport
    private var refreshing = false
    init(cache: LaunchScreenDiskCache, transport: any LaunchScreenTransport) { self.cache = cache; self.transport = transport }

    // Download and validate both pieces before atomically replacing the manifest.
    // This deliberately never changes a presentation already visible on screen.
    @discardableResult func refresh() async -> Bool {
        guard !refreshing else { return false }
        refreshing = true; defer { refreshing = false }
        do {
            let config = try JSONDecoder().decode(LaunchScreenConfiguration.self, from: await transport.configuration())
            try config.validate()
            let previousImage = try? cache.configuration().backgroundImage
            var imageData: Data?
            if config.enabled, let descriptor = config.backgroundImage {
                if let cached = try? LaunchScreenDiskCache.read(cache.imageURL(descriptor), limit: 3 * 1024 * 1024),
                   (try? LaunchScreenDiskCache.decode(cached, descriptor: descriptor)) != nil { imageData = cached }
                else { imageData = try await transport.image(descriptor) }
                _ = try LaunchScreenDiskCache.decode(imageData!, descriptor: descriptor)
            }
            try FileManager.default.createDirectory(at: cache.directory, withIntermediateDirectories: true)
            if let data = imageData, let descriptor = config.backgroundImage { try data.write(to: cache.imageURL(descriptor), options: .atomic) }
            try JSONEncoder().encode(config).write(to: cache.manifest, options: .atomic)
            let keep = Set([config.backgroundImage?.sha256, previousImage?.sha256].compactMap { $0 }.map { $0 + ".image" })
            for url in (try? FileManager.default.contentsOfDirectory(at: cache.directory, includingPropertiesForKeys: nil)) ?? [] {
                if url.pathExtension == "image", !keep.contains(url.lastPathComponent) { try? FileManager.default.removeItem(at: url) }
            }
            return true
        } catch { return false }
    }
}

@MainActor final class LaunchScreenController: ObservableObject {
    @Published private(set) var presentation: LaunchScreenPresentation?
    private let repository: LaunchScreenRepository
    private let refreshEnabled: Bool
    private let holdPreview: Bool
    private var started = false
    init(cache: LaunchScreenDiskCache = .standard, transport: any LaunchScreenTransport = LaunchScreenHTTPTransport(),
         showSplash: Bool = true, refreshEnabled: Bool = true, preview: LaunchScreenPresentation? = nil) {
        repository = LaunchScreenRepository(cache: cache, transport: transport)
        self.refreshEnabled = refreshEnabled; holdPreview = preview != nil
        presentation = showSplash ? (preview ?? cache.presentation()) : nil
    }
    func dismiss() { presentation = nil }
    func start() async {
        guard !started else { return }; started = true
        if refreshEnabled { Task { await repository.refresh() } }
        guard let presentation, !holdPreview else { return }
        try? await Task.sleep(nanoseconds: UInt64(presentation.configuration.displayDurationMs) * 1_000_000)
        if !Task.isCancelled { dismiss() }
    }

    static func forApp(arguments: [String]) -> LaunchScreenController {
        #if DEBUG
        if arguments.contains("--launch-screen-local-preview") {
            let rawURL = arguments.first(where: { $0.hasPrefix("--launch-screen-url=") }).map { String($0.dropFirst("--launch-screen-url=".count)) }
            let rawID = arguments.first(where: { $0.hasPrefix("--launch-screen-cache=") }).map { String($0.dropFirst("--launch-screen-cache=".count)) }
            guard let rawURL, let url = URL(string: rawURL), url.scheme == "http",
                  ["127.0.0.1", "localhost", "::1"].contains(url.host ?? ""), url.user == nil, url.password == nil,
                  url.path == "/api/v3/app/launch-screen", url.query == nil, url.fragment == nil,
                  let rawID, let id = UUID(uuidString: rawID) else { return .init(refreshEnabled: false) }
            let cache = LaunchScreenDiskCache(directory: LaunchScreenDiskCache.standard.directory.appendingPathComponent("LocalQA/\(id.uuidString)", isDirectory: true))
            return .init(cache: cache, transport: LaunchScreenHTTPTransport(endpoint: url))
        }
        if arguments.contains("--launch-screen-preview") || arguments.contains("--launch-screen-custom-preview") {
            var preview = LaunchScreenPresentation.bundled
            if arguments.contains("--launch-screen-custom-preview") {
                preview.configuration.message = "本地测试 每一场都值得记录\n固定品牌 图文保持清晰"
                preview.image = UIGraphicsImageRenderer(size: CGSize(width: 64, height: 128)).image { context in
                    UIColor(red: 0.24, green: 0.16, blue: 0.10, alpha: 1).setFill(); context.fill(CGRect(x: 0, y: 0, width: 64, height: 128))
                }
            }
            return .init(refreshEnabled: false, preview: preview)
        }
        if arguments.contains(where: { $0.hasSuffix("-preview") }) || ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil {
            return .init(showSplash: false, refreshEnabled: false)
        }
        #endif
        return .init()
    }
}

struct ConfigurableLaunchScreenView: View {
    let presentation: LaunchScreenPresentation
    let onSkip: () -> Void
    private let ink = Color(red: 198 / 255, green: 213 / 255, blue: 204 / 255)
    var body: some View {
        GeometryReader { geometry in
            let fullHeight = geometry.size.height + geometry.safeAreaInsets.top + geometry.safeAreaInsets.bottom
            ZStack {
                Group {
                    if let image = presentation.image { Image(uiImage: image).resizable() }
                    else { Image("LaunchArtwork").resizable() }
                }
                .scaledToFill().frame(width: geometry.size.width, height: fullHeight).clipped()
                .overlay { if presentation.image != nil { Color.black.opacity(0.66) } }
                .position(x: geometry.size.width / 2, y: fullHeight / 2 - geometry.safeAreaInsets.top)
                .accessibilityHidden(true)
                Image("LaunchIdentity").resizable().frame(width: 260, height: 223)
                    .position(x: geometry.size.width / 2, y: fullHeight * 0.46968 - geometry.safeAreaInsets.top)
                    .accessibilityLabel("BaseballMaster 棒球比赛记录大师").accessibilityIdentifier("launch-fixed-bm")
                Image("LaunchAppIcon").resizable().frame(width: 44, height: 44).clipShape(RoundedRectangle(cornerRadius: 10))
                    .position(x: geometry.size.width / 2, y: geometry.size.height - 103)
                    .accessibilityLabel("棒球比赛记录大师 App 图标").accessibilityIdentifier("launch-fixed-app-icon")
                if !presentation.configuration.message.isEmpty {
                    Text(presentation.configuration.message).font(.system(size: 11, weight: .medium)).tracking(2.42)
                        .lineSpacing(4).multilineTextAlignment(.center).lineLimit(2).foregroundStyle(ink)
                        .frame(width: min(280, geometry.size.width - 48), height: 38, alignment: .top)
                        .position(x: geometry.size.width / 2, y: geometry.size.height - 48)
                        .accessibilityIdentifier("launch-message")
                }
            }
            .overlay(alignment: .topTrailing) {
                Button("跳过", action: onSkip).font(.system(size: 12)).foregroundStyle(.white)
                    .padding(.horizontal, 16).frame(minHeight: 44).background(.black.opacity(0.16), in: Capsule())
                    .padding(.trailing, 20).accessibilityIdentifier("skip-launch-screen")
            }
        }
        .background(Color(red: 9 / 255, green: 42 / 255, blue: 42 / 255).ignoresSafeArea())
        .preferredColorScheme(.dark)
    }
}

struct AppLaunchContainer<Content: View>: View {
    @ObservedObject var controller: LaunchScreenController
    let requiresDataRecovery: Bool
    @ViewBuilder let content: () -> Content
    var body: some View {
        Group {
            if let presentation = controller.presentation, !requiresDataRecovery {
                ConfigurableLaunchScreenView(presentation: presentation, onSkip: controller.dismiss)
            } else { content() }
        }
        .task { if requiresDataRecovery { controller.dismiss() }; await controller.start() }
        .onChange(of: requiresDataRecovery) { needsRecovery in if needsRecovery { controller.dismiss() } }
    }
}
