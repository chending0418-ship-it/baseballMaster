"""Use installed Android debug/test APKs and an iOS 3.1.4 simulator test bundle on loopback."""
import argparse
import importlib.util
import json
from pathlib import Path
import plistlib
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("ios_fixture", ROOT / "scripts/validation/verify-scheduled-scoring.py")
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("ios_simulator")
    parser.add_argument("android_serial")
    args = parser.parse_args()
    results = ROOT / "output/validation/0.8.6"
    results.mkdir(parents=True, exist_ok=True)
    run = Path(tempfile.mkdtemp(prefix="mixed-", dir=results))
    adb = ["adb", "-s", args.android_serial]
    service = subprocess.Popen(["node", "scripts/android/an05-local-service.mjs"], cwd=ROOT,
                               stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
    ios = None
    try:
        port = json.loads(service.stdout.readline())["port"]
        admin, scorer, settings = fixture.fixture(f"http://127.0.0.1:{port}/api/v3", {})
        subprocess.run(adb + ["reverse", "tcp:18786", f"tcp:{port}"], check=True)
        source = args.manifest.resolve()
        data = plistlib.loads(source.read_bytes().replace(b"__TESTROOT__", str(source.parent).encode()))
        targets = [t for config in data.get("TestConfigurations", []) for t in config["TestTargets"]] or list(data.values())
        target = next(t for t in targets if t.get("BlueprintName") == "BaseballMasterTests")
        target.setdefault("EnvironmentVariables", {}).update(settings, BM314_PUBLIC="0", BM314_PORT=str(port), BM314_DEVICE=scorer)
        with tempfile.NamedTemporaryFile(suffix=".xctestrun", dir=run) as manifest, (run / "ios.log").open("w") as log:
            plistlib.dump(data, manifest); manifest.flush()
            ios = subprocess.Popen(["xcodebuild", "test-without-building", "-xctestrun", manifest.name,
                "-destination", f"platform=iOS Simulator,id={args.ios_simulator}", "-parallel-testing-enabled", "NO",
                "-only-testing:BaseballMasterTests/GameStoreTests/testV314LiveScoringOnTwoSimulators",
                "-resultBundlePath", str(run / "ios.xcresult")], stdout=log, stderr=subprocess.STDOUT)
            result = subprocess.run(adb + ["shell", "am", "instrument", "-w", "-e", "class",
                "cc.baseballmaster.scorekeeper.ScheduledLineupInteropTest", "-e", "fixtureToken", admin,
                "-e", "team", settings["BM314_TEAM"], "-e", "partner", settings["BM314_PARTNER"],
                "cc.baseballmaster.scorekeeper.preview.test/androidx.test.runner.AndroidJUnitRunner"],
                capture_output=True, text=True, timeout=150)
            (run / "android.log").write_text(result.stdout + result.stderr)
            code = ios.wait(timeout=150)
        if code or "OK (1 test)" not in result.stdout or "testV314LiveScoringOnTwoSimulators]' passed" not in (run / "ios.log").read_text():
            raise SystemExit(f"Mixed-device verification failed. See {run}")
        print(f"Android lineup and iOS scorer passed. {run}")
    finally:
        if ios and ios.poll() is None:
            ios.terminate()
        subprocess.run(adb + ["reverse", "--remove", "tcp:18786"], capture_output=True)
        service.stdin.close()
        service.wait(timeout=10)


if __name__ == "__main__":
    main()
