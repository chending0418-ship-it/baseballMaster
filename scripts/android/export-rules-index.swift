#!/usr/bin/env swift
// Generate the Android offline search index from the same PDF bundled by iOS.
import Foundation
import PDFKit
import CryptoKit

struct Line: Encodable { let text: String; let x: Double; let y: Double; let width: Double; let height: Double }
struct Page: Encodable { let width: Double; let height: Double; let lines: [Line] }
struct Index: Encodable { let pdfSHA256: String; let pages: [Page] }
let source = URL(fileURLWithPath: CommandLine.arguments[1])
let output = URL(fileURLWithPath: CommandLine.arguments[2])
let data = try Data(contentsOf: source)
guard let pdf = PDFDocument(data: data) else { fatalError("Cannot read source PDF") }
let pages = (0..<pdf.pageCount).map { n -> Page in
    guard let page = pdf.page(at: n) else { fatalError("Missing page \(n)") }
    let bounds = page.bounds(for: .mediaBox)
    let lines = page.selection(for: bounds)?.selectionsByLine().compactMap { selection -> Line? in
        guard let text = selection.string, !text.isEmpty else { return nil }
        let rect = selection.bounds(for: page)
        return Line(text: text, x: rect.minX - bounds.minX, y: rect.minY - bounds.minY, width: rect.width, height: rect.height)
    } ?? []
    return Page(width: bounds.width, height: bounds.height, lines: lines)
}
let hash = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
try encoder.encode(Index(pdfSHA256: hash, pages: pages)).write(to: output, options: .atomic)
print("Indexed \(pages.count) pages, \(pages.reduce(0) { $0 + $1.lines.count }) lines; PDF SHA-256 \(hash)")
