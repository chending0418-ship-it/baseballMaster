#!/bin/zsh
set -euo pipefail
ANDROID_TASK_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ANDROID_TASK_ROOT"
: "${BM_PARITY_SIMULATOR:?请指定专用 iOS 模拟器 UUID}"
BM_PARITY_EVIDENCE="output/validation/android/ios-parity-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$BM_PARITY_EVIDENCE"
xcodebuild test -project BaseballMaster.xcodeproj -scheme BaseballMaster \
  -destination "platform=iOS Simulator,id=$BM_PARITY_SIMULATOR" \
  -derivedDataPath output/cache/xcode/android-parity -resultBundlePath "$BM_PARITY_EVIDENCE/result.xcresult" \
  -only-testing:BaseballMasterTests/GameStoreTests/${BM_PARITY_TEST:-testAndroidParitySeedExport} CODE_SIGNING_ALLOWED=NO \
  > "$BM_PARITY_EVIDENCE/test.log" 2>&1
xcrun xcresulttool export attachments --path "$BM_PARITY_EVIDENCE/result.xcresult" --output-path "$BM_PARITY_EVIDENCE/attachments"
python3 scripts/android/extract-ios-fixtures.py "$BM_PARITY_EVIDENCE/attachments" "$BM_PARITY_EVIDENCE/candidate-fixtures"
print "候选样本：$BM_PARITY_EVIDENCE/candidate-fixtures；核对差异后再更新 test-fixtures。"
