// Reads local image text for a private deny-list; never outputs recognized text or names.
import Foundation
import Vision
struct Entry: Codable { let file: String; let matches: Int; let recognizedLines: Int; let error: String? }
let args=CommandLine.arguments
if args.count < 4 { fatalError("Usage: scanner blocked-names.json report.json image...") }
let blocked=try JSONDecoder().decode([String].self,from:Data(contentsOf:URL(fileURLWithPath:args[1])))
var entries=[Entry]()
for name in args.dropFirst(3) {
 do {
  let request=VNRecognizeTextRequest()
  request.recognitionLevel = .accurate
  request.recognitionLanguages = ["zh-Hans","en-US"]
  request.usesLanguageCorrection = false
  try VNImageRequestHandler(url:URL(fileURLWithPath:name),options:[:]).perform([request])
  let lines=(request.results ?? []).compactMap{$0.topCandidates(1).first?.string}
  let text=lines.joined().filter{!$0.isWhitespace}
  let hits=blocked.filter{text.contains($0)}.count
  entries.append(Entry(file:name,matches:hits,recognizedLines:lines.count,error:nil))
 } catch { entries.append(Entry(file:name,matches:-1,recognizedLines:0,error:String(describing:error))) }
}
let encoder=JSONEncoder();encoder.outputFormatting=[.prettyPrinted,.sortedKeys]
try encoder.encode(entries).write(to:URL(fileURLWithPath:args[2]))
let failures=entries.filter{$0.matches != 0}.count
print("Image privacy OCR: \(entries.count) scanned, \(failures) flagged. No names or recognized text emitted.")
if failures>0 { exit(1) }
