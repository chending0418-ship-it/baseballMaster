"""Build the iOS 3.1.4 round-trip witness without editing the iOS working tree."""
import shutil
import subprocess
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[2]
folder = root / "output/validation/0.8.6/build33/ios-witness"
for name in ("BaseballMaster.xcodeproj", "BaseballMaster", "BaseballMasterTests", "BaseballMasterUITests"):
    shutil.copytree(root / name, folder / name, dirs_exist_ok=True)
test = folder / "BaseballMasterTests/GameStoreTests.swift"
test.write_text(test.read_text() + (root / "scripts/android/swift-wire/ScheduleWriterDevice.swift").read_text())
subprocess.run(["xcodebuild", "build-for-testing", "-project", "BaseballMaster.xcodeproj",
                "-scheme", "BaseballMaster", "-destination", "platform=iOS Simulator,id=" + sys.argv[1],
                "-derivedDataPath", str(folder / "DerivedData"), "CODE_SIGNING_ALLOWED=NO"], cwd=folder, check=True)
