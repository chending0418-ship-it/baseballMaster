"""Use installed Android debug/test APKs and an iOS 3.1.4 simulator test bundle on loopback."""
import argparse
import importlib.util
import json
from pathlib import Path
import plistlib
import subprocess
import tempfile
import sys
sys.dont_write_bytecode = True

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("ios_fixture", ROOT / "scripts/validation/verify-scheduled-scoring.py")
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("ios_simulator")
    parser.add_argument("android_serial")
    parser.add_argument("--android-writer", action="store_true", help="iOS manages lineups while Android records plays")
    parser.add_argument("--second-android", help="use a second Android emulator for scoring and transfer back to the first")
    parser.add_argument("--roundtrip", action="store_true", help="iOS writer hands off to Android, then receives the continued game")
    args = parser.parse_args()
    if args.second_android == args.android_serial:
        parser.error("two distinct Android devices are required")
    results = ROOT / "output/validation/0.8.6"
    results.mkdir(parents=True, exist_ok=True)
    run = Path(tempfile.mkdtemp(prefix="mixed-", dir=results))
    adb = ["adb", "-s", args.android_serial]
    service = subprocess.Popen(["node", "scripts/android/an05-local-service.mjs"], cwd=ROOT,
                               stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
    forwards = {}
    ios = None
    second = None
    try:
        port = json.loads(service.stdout.readline())["port"]
        admin, scorer, settings = fixture.fixture(f"http://127.0.0.1:{port}/api/v3", {})
        def forward(serial):
            forwards[serial] = subprocess.check_output(["adb","-s",serial,"reverse","tcp:0",f"tcp:{port}"],text=True).strip()
        forward(args.android_serial)
        def instrument(serial, token, writer, handoff):
            return ["adb","-s",serial,"shell","am","instrument","-w","-e","class",
                "cc.baseballmaster.scorekeeper.ScheduledLineupInteropTest","-e","fixtureToken",token,
                "-e","fixturePort",forwards[serial],"-e","roundtrip","1" if args.roundtrip else "0","-e","writer","1" if writer else "0","-e","handoff","1" if handoff else "0",
                "-e","team",settings["BM314_TEAM"],"-e","partner",settings["BM314_PARTNER"],
                "cc.baseballmaster.scorekeeper.preview.test/androidx.test.runner.AndroidJUnitRunner"]
        if args.second_android:
            forward(args.second_android)
            with (run/"writer.log").open("w") as log:
                second = subprocess.Popen(instrument(args.second_android,scorer,True,True),stdout=log,stderr=subprocess.STDOUT)
                first = subprocess.run(instrument(args.android_serial,admin,False,True),capture_output=True,text=True,timeout=200)
                (run/"lineup.log").write_text(first.stdout+first.stderr)
                second.wait(timeout=180)
            if any("OK (1 test)" not in (run/file).read_text() for file in ["lineup.log","writer.log"]):
                raise SystemExit(f"Two-Android verification failed. See {run}")
            print(f"Two Android devices scored and transferred the full journal. {run}")
            return
        source = args.manifest.resolve()
        data = plistlib.loads(source.read_bytes().replace(b"__TESTROOT__", str(source.parent).encode()))
        targets = [t for config in data.get("TestConfigurations", []) for t in config["TestTargets"]] or list(data.values())
        target = next(t for t in targets if t.get("BlueprintName") == "BaseballMasterTests")
        target.setdefault("EnvironmentVariables", {}).update(settings, BM314_PUBLIC="0", BM314_PORT=str(port), BM314_DEVICE=admin if args.android_writer else scorer)
        test = "testAndroidScheduleCheckpointRoundTrip" if args.roundtrip else "testV314LiveScoringOnTwoSimulators"
        with tempfile.NamedTemporaryFile(suffix=".xctestrun", dir=run) as manifest, (run / "ios.log").open("w") as log:
            plistlib.dump(data, manifest); manifest.flush()
            ios = subprocess.Popen(["xcodebuild", "test-without-building", "-xctestrun", manifest.name,
                "-destination", f"platform=iOS Simulator,id={args.ios_simulator}", "-parallel-testing-enabled", "NO",
                f"-only-testing:BaseballMasterTests/GameStoreTests/{test}",
                "-resultBundlePath", str(run / "ios.xcresult")], stdout=log, stderr=subprocess.STDOUT)
            with (run/"android.log").open("w") as output:
                second = subprocess.Popen(instrument(args.android_serial,scorer if args.android_writer else admin,args.android_writer,args.roundtrip),
                    stdout=output,stderr=subprocess.STDOUT)
                code = ios.wait(timeout=180)
                if args.android_writer and code == 0:
                    subprocess.run(adb+["shell","run-as","cc.baseballmaster.scorekeeper.preview","touch",
                        "files/observed-"+settings["BM314_TEAM"]],check=True)
                second.wait(timeout=160)
        if code or "OK (1 test)" not in (run/"android.log").read_text() or f"{test}]' passed" not in (run / "ios.log").read_text():
            raise SystemExit(f"Mixed-device verification failed. See {run}")
        print(f"Mixed scoring passed (Android writer={args.android_writer}). {run}")
    finally:
        if second and second.poll() is None:
            second.terminate()
        if ios and ios.poll() is None:
            ios.terminate()
        for serial, port in forwards.items():
            subprocess.run(["adb","-s",serial,"reverse","--remove","tcp:"+port],capture_output=True)
        service.stdin.close()
        service.wait(timeout=10)


if __name__ == "__main__":
    main()
