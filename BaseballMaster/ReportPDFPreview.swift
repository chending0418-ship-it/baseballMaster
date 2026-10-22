import SwiftUI
import PDFKit

struct ExportedReportFile: Identifiable {
    let url: URL
    var id: URL { url }
}

struct ReportExportButton: View {
    let title: String
    let identifier: String
    let previewTitle: String
    let generate: () throws -> URL
    @State private var report: ExportedReportFile?
    @State private var errorMessage: String?

    var body: some View {
        Button {
            do {
                let url = try generate()
                guard let document = PDFDocument(url: url), document.pageCount > 0 else {
                    throw CocoaError(.fileReadCorruptFile)
                }
                report = ExportedReportFile(url: url)
            } catch { errorMessage = error.localizedDescription }
        } label: {
            Label(title, systemImage: "square.and.arrow.up")
        }
        .accessibilityIdentifier(identifier)
        .sheet(item: $report) { file in
            ReportPDFPreview(url: file.url, title: previewTitle)
        }
        .alert("PDF 导出失败", isPresented: Binding(
            get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } }
        )) {
            Button("知道了", role: .cancel) { errorMessage = nil }
        } message: { Text(errorMessage ?? "") }
    }
}

struct ReportPDFPreview: View {
    @Environment(\.dismiss) private var dismiss
    let url: URL
    let title: String
    @State private var sharing = false

    private var pageSummary: String {
        let document = PDFDocument(url: url)
        let bounds = document?.page(at: 0)?.bounds(for: .mediaBox) ?? .zero
        let orientation = bounds.width > bounds.height ? "横版" : "竖版"
        return "A4 \(orientation) · \(document?.pageCount ?? 0) 页 · 可缩放阅读、保存或打印"
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Text(pageSummary)
                    .font(.caption)
                    .foregroundStyle(BMTheme.secondaryText)
                    .padding(10)
                    .accessibilityIdentifier("report-pdf-page-count")
                ReportPDFView(url: url)
                    .accessibilityIdentifier("report-pdf-document")
            }
            .bmScreenBackground()
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("关闭") { dismiss() }.accessibilityIdentifier("close-report-pdf")
                }
                ToolbarItem(placement: .primaryAction) {
                    Button { sharing = true } label: { Label("分享", systemImage: "square.and.arrow.up") }
                        .accessibilityIdentifier("share-report-pdf")
                }
            }
            .sheet(isPresented: $sharing) { ActivityShareSheet(items: [url]) }
        }
    }
}

private struct ReportPDFView: UIViewRepresentable {
    let url: URL

    func makeUIView(context: Context) -> PDFView {
        let view = PDFView()
        view.document = PDFDocument(url: url)
        view.autoScales = true
        view.displayMode = .singlePageContinuous
        view.displayDirection = .vertical
        return view
    }

    func updateUIView(_ uiView: PDFView, context: Context) {}
}
