#!/usr/bin/env python3
"""Build original iOS commits in cache and export synthetic versioned backups.

Usage: python3 scripts/android/export-historical-backup-fixture.py VERSION REVISION SIMULATOR_ID
Does not touch the checked-out source. Requires Xcode and an available iOS simulator.
"""
import pathlib
import subprocess
import sys
import tarfile

root = pathlib.Path(__file__).resolve().parents[2]
version, revision, simulator = sys.argv[1:]
checkout = root / 'output/cache/android/ios-historical' / ('v' + version)
checkout.mkdir(parents=True, exist_ok=False)
tar = subprocess.Popen(['git', 'archive', revision, 'BaseballMaster', 'BaseballMaster.xcodeproj', 'BaseballMasterTests', 'BaseballMasterUITests'], cwd=root, stdout=subprocess.PIPE)
with tarfile.open(fileobj=tar.stdout, mode='r|') as archive:
    archive.extractall(checkout, filter='data')
if tar.wait():
    raise RuntimeError('Could not export historical source')
test_file = checkout / 'BaseballMasterTests/GameStoreTests.swift'
s = test_file.read_text()
needle = 'final class GameStoreTests: XCTestCase {\n'
assert needle in s
source = '''    func testAndroidHistoricalBackupExport() throws {
        let store = freshStore()
        store.recordPitch(.ball); XCTAssertTrue(store.applyPlay(.single))
        let data = try Data(contentsOf: store.exportBackup())
        let attachment = XCTAttachment(data: data, uniformTypeIdentifier: "public.json")
        attachment.name = "ios-original-v%s.bmbackup"; attachment.lifetime = .keepAlways; add(attachment)
    }
''' % version
test_file.write_text(s.replace(needle, needle + source, 1))
result = root / ('output/validation/android/an04-ios-original-v%s.xcresult' % version)
log = root / ('output/validation/android/an04-ios-original-v%s.log' % version)
cmd = ['xcodebuild', 'test', '-project', str(checkout / 'BaseballMaster.xcodeproj'), '-scheme', 'BaseballMaster', '-destination', 'platform=iOS Simulator,id=' + simulator, '-derivedDataPath', str(root / ('output/cache/xcode/ios-historical-v' + version)), '-resultBundlePath', str(result), '-only-testing:BaseballMasterTests/GameStoreTests/testAndroidHistoricalBackupExport', 'CODE_SIGNING_ALLOWED=NO']
with log.open('w') as out:
    subprocess.run(cmd, cwd=root, stdout=out, stderr=subprocess.STDOUT, check=True)
if "Executed 1 test, with 0 failures" not in log.read_text():
    raise RuntimeError('Xcode did not actually run the requested test')
attachments = root / ('output/validation/android/an04/ios-v%s-attachments' % version)
attachments.mkdir(parents=True, exist_ok=True)
subprocess.run(['xcrun', 'xcresulttool', 'export', 'attachments', '--path', str(result), '--output-path', str(attachments)], check=True)
print('Generated original iOS v' + version + ' backup in ' + str(attachments))
