#!/usr/bin/env python3
"""Validate Android 0.8.1 on a genuine foldable AVD with an unlocked screen.

The instrumentation owns the Pro draft and persistence assertions. This host
runner installs the supplied Release APK and test APK, drives the emulator's
hinge/posture controls, and checks its WindowInfoTracker evidence. It never
changes wm size/density, clears application data, or removes match records.

Existing output is preserved: when --output already exists, a new round
subdirectory is created. All evidence is tied to the supplied APK hashes and
installed package hashes. Requires adb and Product081RealFoldFlowTest.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import time
import uuid

STAGES = ("unfolded-draft", "half-opened-draft", "folded-draft", "reopened-draft", "cancelled")
ARTIFACTS = ("081-real-fold-phase.txt", "081-real-fold-host-step.txt", "081-real-fold-receipt.json") + tuple(
    f"081-real-fold-{stage}.{extension}" for stage in STAGES for extension in ("png", "xml")
)


def sha256(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def run_directory(requested: Path) -> Path:
    requested = requested.expanduser().resolve()
    if requested.exists():
        if not requested.is_dir():
            raise ValueError(f"Output is not a directory: {requested}")
        stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        requested = requested / f"round-{stamp}-{uuid.uuid4().hex[:6]}"
    requested.mkdir(parents=True, exist_ok=False)
    return requested


def find_adb(value: str | None) -> str:
    if value:
        path = Path(value).expanduser().resolve()
        if not path.is_file():
            raise ValueError(f"adb not found: {path}")
        return str(path)
    executable = shutil.which("adb")
    if executable:
        return executable
    candidate = Path.home() / "Library/Android/sdk/platform-tools/adb"
    if candidate.is_file():
        return str(candidate)
    raise ValueError("adb was not found; supply --adb /path/to/platform-tools/adb")


class FoldValidation:
    def __init__(self, args: argparse.Namespace, output: Path):
        self.args = args
        self.output = output
        self.adb = find_adb(args.adb)
        self.device_files = f"/sdcard/Android/data/{args.package}/files"
        self.process: subprocess.Popen | None = None
        self.phases: list[str] = []
        self.transitions: list[dict] = []
        self.manifest = {
            "startedAtUtc": datetime.now(timezone.utc).isoformat(),
            "serial": args.serial,
            "package": args.package,
            "manualWindowSizeCommandsUsed": False,
            "output": str(output),
            "apks": {},
        }

    def command(self, *arguments: str, timeout: float = 30, check: bool = True):
        command = [self.adb, "-s", self.args.serial, *arguments]
        result = subprocess.run(command, capture_output=True, text=True, timeout=timeout)
        with (self.output / "commands.jsonl").open("a") as stream:
            stream.write(json.dumps({
                "atUtc": datetime.now(timezone.utc).isoformat(), "argv": command,
                "returncode": result.returncode, "stdout": result.stdout, "stderr": result.stderr,
            }, ensure_ascii=False) + "\n")
        if check and result.returncode:
            raise RuntimeError(f"adb {arguments[0]} failed: {result.stderr.strip() or result.stdout.strip()}")
        return result

    def save_manifest(self):
        (self.output / "manifest.json").write_text(json.dumps(self.manifest, indent=2, ensure_ascii=False))

    def package_info(self, package: str, label: str, required: bool = False) -> dict:
        result = self.command("shell", "dumpsys", "package", package, check=False)
        (self.output / f"{label}-package.txt").write_text(result.stdout + result.stderr)
        version = re.search(r"versionName=([^\s]+)", result.stdout)
        code = re.search(r"versionCode=(\d+)", result.stdout)
        paths = self.command("shell", "pm", "path", package, check=False).stdout.splitlines()
        base = next((line.removeprefix("package:") for line in paths if line.startswith("package:") and line.endswith("/base.apk")), None)
        info = {"installed": base is not None, "debuggable": bool(re.search(r"\bDEBUGGABLE\b", result.stdout))}
        if version:
            info["versionName"] = version.group(1)
        if code:
            info["versionCode"] = int(code.group(1))
        if required and not info["installed"]:
            raise RuntimeError(f"Package is missing after installation: {package}")
        if base:
            digest = self.command("shell", "sha256sum", shlex.quote(base), check=required)
            value = re.match(r"([0-9a-fA-F]{64})\s", digest.stdout)
            if value:
                info["installedSha256"] = value.group(1).lower()
            elif required:
                raise RuntimeError(f"Cannot verify installed APK bytes for {package}")
        return info

    def install(self):
        if self.command("get-state").stdout.strip() != "device":
            raise RuntimeError("The selected device is not online")
        if self.command("shell", "getprop", "sys.boot_completed").stdout.strip() != "1":
            raise RuntimeError("The selected AVD has not completed booting")
        self.manifest["device"] = {
            "sdk": self.command("shell", "getprop", "ro.build.version.sdk").stdout.strip(),
            "fingerprint": self.command("shell", "getprop", "ro.build.fingerprint").stdout.strip(),
            "avd": self.command("emu", "avd", "name").stdout.strip(),
        }
        hinge = self.command("emu", "sensor", "get", "hinge-angle0").stdout
        if "KO:" in hinge or "hinge-angle0" not in hinge:
            raise RuntimeError("A foldable AVD with an enabled hinge sensor is required")
        for label, path, package in (("app", self.args.apk, self.args.package), ("test", self.args.test_apk, self.args.package + ".test")):
            self.manifest["apks"][label] = {
                "path": str(path), "sha256": sha256(path), "bytes": path.stat().st_size,
                "before": self.package_info(package, f"{label}-before"),
            }
        self.save_manifest()
        for label, path, package in (("app", self.args.apk, self.args.package), ("test", self.args.test_apk, self.args.package + ".test")):
            self.command("install", "-r", "-t", str(path), timeout=120)
            before_hash = self.manifest["apks"][label]["sha256"]
            if sha256(path) != before_hash:
                raise RuntimeError(f"Input {label} APK changed during installation")
            after = self.package_info(package, f"{label}-after", required=True)
            self.manifest["apks"][label]["after"] = after
            if after.get("installedSha256") != before_hash:
                raise RuntimeError(f"Installed {label} APK hash does not match the input")
        app = self.manifest["apks"]["app"]["after"]
        if app.get("versionName") != self.args.expected_version or app.get("versionCode") != self.args.expected_code:
            raise RuntimeError(f"Unexpected app version: {app}")
        if app["debuggable"]:
            raise RuntimeError("The app APK is debuggable; supply the Release APK")
        self.save_manifest()

    def current_state(self) -> tuple[str, str, float]:
        # API 31 prints the committed state, then exits 255 while parsing the
        # absent optional state argument. Preserve that result in commands.jsonl
        # and require its actual state receipt rather than accepting an exit code.
        result = self.command("shell", "cmd", "device_state", "state", check=False)
        raw = result.stdout
        match = re.search(r"Committed state:.*?name='([^']+)'", raw)
        if not match:
            raise RuntimeError(f"No committed DeviceState receipt: {raw}{result.stderr}")
        angle = self.command("emu", "sensor", "get", "hinge-angle0").stdout
        value = re.search(r"hinge-angle0\s*=\s*([\d.]+)", angle)
        return match.group(1), raw, float(value.group(1)) if value else -1.0

    def wake(self):
        self.command("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        self.command("shell", "wm", "dismiss-keyguard", check=False)

    def transition(self, step: str, console: tuple[str, ...], expected: str, initial: bool = False):
        result = self.command("emu", *console)
        if "KO:" in result.stdout:
            raise RuntimeError(f"Emulator rejected {step}: {result.stdout}")
        end = time.monotonic() + 25
        while time.monotonic() < end:
            state, raw, angle = self.current_state()
            valid_angle = (angle <= 30 if step == "folded" else 30 <= angle <= 150 if step == "half-opened" else angle >= 150)
            if state == expected and valid_angle:
                break
            time.sleep(.25)
        else:
            raise RuntimeError(f"Genuine posture did not reach {expected}: {raw}, hinge={angle}")
        self.wake()
        time.sleep(1)
        state, raw, angle = self.current_state()
        record = {"step": step, "deviceState": state, "hingeAngle": angle, "console": list(console)}
        self.transitions.append(record)
        (self.output / f"host-{step}-state.txt").write_text(raw + f"\nhinge-angle0={angle}\n")
        for suffix, command in (("size-read-only", ("shell", "wm", "size")), ("density-read-only", ("shell", "wm", "density")), ("display", ("shell", "dumpsys", "display"))):
            (self.output / f"host-{step}-{suffix}.txt").write_text(self.command(*command).stdout)
        if not initial:
            destination = f"{self.device_files}/081-real-fold-host-step.txt"
            self.command("shell", f"printf '%s' {shlex.quote(step)} > {shlex.quote(destination)}")
        print(f"{step}: device state {state}, hinge {angle:g} degrees", flush=True)

    def clean_test_output(self):
        # Preserve only this test's existing device evidence before resetting its
        # protocol. No databases, settings, credentials or other files are touched.
        previous = self.output / "previous-device-files"
        previous.mkdir()
        for name in ARTIFACTS:
            self.command("pull", f"{self.device_files}/{name}", str(previous / name), check=False)
        self.command("shell", "rm", "-f", *(f"{self.device_files}/{name}" for name in ARTIFACTS))

    def instrument(self):
        self.transition("unfolded", ("unfold",), self.args.opened_state, initial=True)
        self.clean_test_output()
        runner = self.args.runner or self.args.package + ".test/androidx.test.runner.AndroidJUnitRunner"
        test = "cc.baseballmaster.scorekeeper.Product081RealFoldFlowTest"
        log_path = self.output / "instrumentation.log"
        expected = ["ready", "half-opened-ok", "folded-ok", "complete"]
        steps = [
            ("half-opened", ("posture", "2"), self.args.half_opened_state),
            ("folded", ("fold",), self.args.closed_state),
            ("unfolded", ("unfold",), self.args.opened_state),
        ]
        with log_path.open("w") as log:
            self.process = subprocess.Popen([self.adb, "-s", self.args.serial, "shell", "am", "instrument", "-w", "-r", "-e", "class", test, runner], stdout=log, stderr=subprocess.STDOUT)
            end = time.monotonic() + self.args.timeout
            while time.monotonic() < end and self.process.poll() is None:
                phase = self.command("shell", "cat", f"{self.device_files}/081-real-fold-phase.txt", check=False, timeout=10).stdout.strip()
                if phase and phase not in self.phases:
                    if len(self.phases) >= len(expected) or phase != expected[len(self.phases)]:
                        raise RuntimeError(f"Unexpected test protocol phase: {phase}")
                    self.phases.append(phase)
                    print(f"instrumentation: {phase}", flush=True)
                    if phase != "complete":
                        self.transition(*steps[len(self.phases) - 1])
                time.sleep(.5)
            if self.process.poll() is None:
                raise TimeoutError(f"Fold instrumentation exceeded {self.args.timeout} seconds")
        text = log_path.read_text()
        if not re.search(r"\bOK \(1 test\)", text) or "FAILURES!!!" in text:
            raise RuntimeError("The real fold instrumentation did not pass; inspect instrumentation.log")
        final_phase = self.command("shell", "cat", f"{self.device_files}/081-real-fold-phase.txt").stdout.strip()
        if final_phase != "complete":
            raise RuntimeError(f"The real fold protocol did not complete: {final_phase}")
        if self.phases == expected[:-1]:
            # A fast runner may exit between polls after writing its final phase.
            self.phases.append(final_phase)
        if self.phases != expected:
            raise RuntimeError(f"Incomplete host protocol: {self.phases}")

    def collect(self):
        files = self.output / "device-files"
        files.mkdir(exist_ok=True)
        for name in ARTIFACTS:
            self.command("pull", f"{self.device_files}/{name}", str(files / name), check=False)
        for label, arguments in (("runtime.log", ("logcat", "-d", "-t", "2000")), ("crash-buffer.log", ("logcat", "-d", "-b", "crash", "-t", "60"))):
            (self.output / label).write_text(self.command(*arguments, check=False).stdout)

    def validate_receipt(self):
        files = self.output / "device-files"
        for name in ARTIFACTS:
            if not (files / name).is_file():
                raise RuntimeError(f"Test evidence is missing: {name}")
        if (files / "081-real-fold-phase.txt").read_text().strip() != "complete":
            raise RuntimeError("The pulled test protocol is incomplete")
        for stage in STAGES:
            if not (files / f"081-real-fold-{stage}.png").read_bytes().startswith(b"\x89PNG\r\n\x1a\n"):
                raise RuntimeError(f"Invalid {stage} screenshot")
        path = self.output / "device-files/081-real-fold-receipt.json"
        if not path.is_file():
            raise RuntimeError("WindowInfoTracker receipt is missing")
        transitions = json.loads(path.read_text()).get("transitions", [])
        records = {record.get("stage"): record for record in transitions}
        if set(records) != set(STAGES):
            raise RuntimeError("Incomplete fold transition receipt")
        match_ids = {record.get("matchID") for record in records.values()}
        if len(match_ids) != 1 or not all(match_ids):
            raise RuntimeError("Fold transitions were not recorded for the same match")
        if not all(record.get("windowInfoSampled") for record in records.values()):
            raise RuntimeError("WindowInfoTracker was not sampled for every transition")
        half = records["half-opened-draft"].get("features", [])
        if not any(feature.get("state") == "HALF_OPENED" and feature.get("isSeparating") for feature in half):
            raise RuntimeError("A genuine separating HALF_OPENED FoldingFeature was not observed")
        opened = records["unfolded-draft"]
        folded = records["folded-draft"]
        reopened = records["reopened-draft"]
        if folded.get("displayWidth", 0) >= opened.get("displayWidth", 0):
            raise RuntimeError("The actual cover display did not become narrower than the inner display")
        if (reopened.get("displayWidth"), reopened.get("displayHeight")) != (opened.get("displayWidth"), opened.get("displayHeight")):
            raise RuntimeError("The original inner display dimensions were not restored")
        self.manifest["foldReceiptVerified"] = True
        self.manifest["matchID"] = opened["matchID"]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--serial", required=True, help="Dedicated genuine foldable emulator serial")
    parser.add_argument("--output", type=Path, required=True, help="New evidence directory; existing output receives a new round subdirectory")
    parser.add_argument("--apk", type=Path, required=True, help="Android 0.8.1 Release APK")
    parser.add_argument("--test-apk", type=Path, required=True, help="Matching instrumentation APK with Product081RealFoldFlowTest")
    parser.add_argument("--adb", help="Optional path to adb")
    parser.add_argument("--package", default="cc.baseballmaster.scorekeeper.preview")
    parser.add_argument("--runner", help="Instrumentation component; defaults to PACKAGE.test/androidx.test.runner.AndroidJUnitRunner")
    parser.add_argument("--expected-version", default="0.8.1")
    parser.add_argument("--expected-code", type=int, default=9)
    parser.add_argument("--timeout", type=int, default=420, help="Bounded instrumentation timeout in seconds")
    parser.add_argument("--opened-state", default="OPENED")
    parser.add_argument("--half-opened-state", default="HALF_OPENED")
    parser.add_argument("--closed-state", default="CLOSED")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_.]+", args.package):
        parser.error("--package must be an Android package name")
    for name in ("apk", "test_apk"):
        path = getattr(args, name).expanduser().resolve()
        if not path.is_file():
            parser.error(f"{name.replace('_', '-')} does not exist: {path}")
        setattr(args, name, path)
    if args.timeout < 30:
        parser.error("--timeout must be at least 30 seconds")
    return args


def main() -> int:
    args = parse_args()
    output = run_directory(args.output)
    print(f"Evidence: {output}", flush=True)
    run = FoldValidation(args, output)
    error = None
    try:
        run.install()
        run.instrument()
        run.collect()
        run.validate_receipt()
    except (Exception, KeyboardInterrupt) as exception:
        error = f"{type(exception).__name__}: {exception}"
        if run.process is not None and run.process.poll() is None:
            # Stop this exact host invocation, then ask its instrumentation process
            # to stop; leave every persisted record and other device untouched.
            run.process.terminate()
            run.command("shell", "am", "force-stop", args.package, check=False)
        try:
            run.collect()
        except Exception as collection_error:
            (output / "collection-error.txt").write_text(str(collection_error))
    run.manifest.update(finishedAtUtc=datetime.now(timezone.utc).isoformat(), passed=error is None,
                        phases=run.phases, transitions=run.transitions, error=error)
    run.save_manifest()
    print(json.dumps({"passed": error is None, "output": str(output), "error": error}, ensure_ascii=False), flush=True)
    return 0 if error is None else 1


if __name__ == "__main__":
    sys.exit(main())
