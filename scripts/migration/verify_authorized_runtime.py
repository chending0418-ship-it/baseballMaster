#!/usr/bin/env python3
"""Run captured-code synthetic tests on Shanghai without activating production.

Only the outer root process reads the non-secret precopy authorization. Each
suite uses new scratch data, an empty inherited environment, a private network
namespace and an additional loopback-only Node preload. No precopied database,
private configuration, provider key or production service is used as a fixture.
"""
import argparse
import ast
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import urllib.request
import uuid

HOST = '101.43.112.8'
NODE = '/opt/baseballmaster-node/node-v24.21.0-linux-x64/bin/node'
CONTROL = Path('/var/lib/baseballmaster-staging-control')
PRECOPY = Path('/var/lib/baseballmaster-production-precopy')
RELEASES = Path('/opt/baseballmaster-production/releases')
RUN_BASE = Path('/run/baseballmaster-runtime-validation')
ROLES = ['team', 'live', 'lifan', 'voice-lab', 'voice-assistant']
STAGING_UNITS = ['baseballmaster-staging-' + role + '.service'
                 for role in ['web', 'team', 'live', 'lifan']]
HIDDEN_PATHS = ['/etc', '/root', '/home', '/www', '/mnt', '/var/lib',
                '/var/backups', '/var/spool', '/run/credentials', '/run/systemd/private']


def save(path, value):
    with open(path, 'x', opener=lambda name, flags: os.open(name, flags, 0o600)) as file:
        json.dump(value, file, ensure_ascii=False, indent=2)
        file.write('\n')


def invoke(arguments, *, env=None, timeout=20):
    return subprocess.run(arguments, capture_output=True, env=env, timeout=timeout)


def private_directory(path):
    path.mkdir(parents=True, mode=0o700, exist_ok=True)
    assert path.is_dir() and not path.is_symlink()
    assert path.stat().st_uid == 0 and path.stat().st_mode & 0o077 == 0


def guard(batch, release):
    assert os.geteuid() == 0
    assert re.fullmatch(r'precopy-[0-9TZ]+-[a-f0-9]{8}', batch)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    address = opener.open('http://metadata.tencentyun.com/latest/meta-data/public-ipv4',
                          timeout=4).read().decode().strip()
    assert address == HOST, 'Wrong host'
    authorization = PRECOPY / batch / 'authorization.json'
    assert authorization.is_file() and not authorization.is_symlink()
    assert authorization.stat().st_mode & 0o077 == 0
    permission = json.loads(authorization.read_text())
    for key in ['realDataOfflinePrecopyApproved', 'preserveAllPersistentRecords',
                'includeBothVoiceServices']:
        assert permission[key] is True
    for key in ['trafficActivationApproved', 'dnsChangeApproved']:
        assert permission[key] is False
    assert release.parent == RELEASES and release.is_dir() and not release.is_symlink()
    assert release.resolve() == release
    assert Path(NODE).is_file()
    assert invoke([NODE, '--version']).stdout.decode().strip() == 'v24.21.0'


def clean_environment(scratch):
    # Construct a whitelist; do not copy os.environ or real provider settings.
    return {'PATH': '/usr/bin:/bin:' + str(Path(NODE).parent),
            'HOME': str(scratch / 'home'), 'TMPDIR': str(scratch / 'tmp'),
            'TMP': str(scratch / 'tmp'), 'TEMP': str(scratch / 'tmp'),
            'LANG': 'C.UTF-8', 'LC_ALL': 'C.UTF-8', 'TZ': 'Asia/Shanghai',
            'NODE_ENV': 'test', 'BM_TEST_SCRATCH': str(scratch),
            'OPENSSL_CONF': str(scratch / 'openssl.cnf')}


def source_inventory(root):
    result = {}
    forbidden = {'.env', 'config.env', 'backup.key', 'lifan-backup.key',
                 'passwd-cosfs', 'cos-credentials.json'}
    for path in sorted(root.rglob('*')):
        assert not path.is_symlink(), 'Captured source links need review'
        assert not ({'.git', '.secrets'} & set(path.relative_to(root).parts))
        assert path.name not in forbidden, 'Secret configuration in code tree'
        assert path.suffix not in ['.sqlite', '.db'], 'Mutable data in code tree'
        if path.is_file():
            result[str(path.relative_to(root))] = hashlib.sha256(path.read_bytes()).hexdigest()
    return result


def host_state(production_units):
    states = {}
    for unit in [*STAGING_UNITS, *production_units]:
        value = invoke(['systemctl', 'show', unit, '-p', 'ActiveState', '-p', 'SubState',
                        '-p', 'MainPID', '-p', 'UnitFileState', '-p', 'LoadState'])
        states[unit] = dict(line.split('=', 1) for line in value.stdout.decode().splitlines()
                            if '=' in line)
    listening = invoke(['ss', '-H', '-lnt'])
    assert listening.returncode == 0
    addresses = sorted(line.split()[3] for line in listening.stdout.decode().splitlines())
    assert not any(address.rsplit(':', 1)[-1] in ['80', '443'] for address in addresses)
    for unit in production_units:
        state = states[unit]
        assert state.get('ActiveState') not in ['active', 'activating', 'reloading']
        assert int(state.get('MainPID', '0')) == 0
    return {'units': states, 'listeners': addresses, 'public80And443Unbound': True}


def parse_tap(output):
    result = {}
    for key in ['tests', 'suites', 'pass', 'fail', 'cancelled', 'skipped', 'todo']:
        values = re.findall(r'^# ' + key + r' (\d+)\s*$', output, re.MULTILINE)
        result[key] = int(values[-1]) if values else None
    duration = re.findall(r'^# duration_ms ([0-9.]+)\s*$', output, re.MULTILINE)
    result['durationMs'] = float(duration[-1]) if duration else None
    return result


def classify_failure(output, exit_code):
    if 'BM_SYNTHETIC_ISOLATION_BLOCKED' in output:
        return 'fixture-or-test-attempted-an-isolation-forbidden-operation'
    if any(word in output for word in ['ERR_MODULE_NOT_FOUND', 'MODULE_NOT_FOUND', 'ENOENT']):
        return 'missing-captured-source-or-fixture'
    if any(word in output for word in ['EACCES', 'EROFS', 'Permission denied']):
        return 'fixture-used-a-protected-or-read-only-location'
    if any(word in output for word in ['timed out', 'timeout', 'RuntimeMaxSec', 'ETIMEDOUT']):
        return 'short-runtime-limit-or-timeout'
    if exit_code:
        return 'test-or-transient-sandbox-failure'
    return None


def run_suite(role, release, scratch_root, log_root, support, run_id):
    source = release / 'roles' / role
    if not source.is_dir():
        return {'role': role, 'passed': False, 'complete': False,
                'failureClass': 'missing-captured-role-source'}
    original_source = source
    original_hashes = source_inventory(source)
    scratch = scratch_root / role
    private_directory(scratch)
    supplemental = False
    test_root = source / 'collaboration/test' if role == 'team' else source / 'test'
    extra = Path(__file__).parent / 'test-supplements' / role
    if (not test_root.is_dir() and role in ['live', 'lifan']) or (role == 'team' and extra.is_dir()):
        assert extra.is_dir()
        source = scratch / 'qa-code'
        shutil.copytree(original_source, source)
        assert source_inventory(source) == original_hashes
        test_root = source / ('collaboration/test' if role == 'team' else 'test')
        shutil.copytree(extra, test_root, dirs_exist_ok=True)
        supplemental = True
    files = sorted(test_root.rglob('*.test.mjs')) if test_root.is_dir() else []
    if not files:
        return {'role': role, 'passed': False, 'complete': False,
                'failureClass': 'missing-captured-test-source', 'testRoot': str(test_root)}
    assert len(files) <= 100 and all(path.resolve().is_relative_to(source) for path in files)
    before = source_inventory(source)
    scratch = scratch_root / role
    private_directory(scratch)
    for name in ['home', 'tmp']:
        private_directory(scratch / name)
    (scratch / 'openssl.cnf').write_text('')
    env = clean_environment(scratch)
    command = [NODE, '--import', str(support / 'network-guard.mjs')]
    if role == 'lifan':
        command += ['--import', str(support / 'lifan-test-connections.mjs')]
    command += ['--test', '--test-isolation=none', '--test-concurrency=1',
                '--test-reporter=tap', '--test-timeout=165000', *map(str, files)]
    unit = 'bm-runtime-qa-' + run_id + '-' + role
    properties = ['Type=exec', 'User=root', 'Group=root', 'PrivateNetwork=yes',
                  'PrivateTmp=yes', 'NoNewPrivileges=yes', 'CapabilityBoundingSet=',
                  'ProtectSystem=strict', 'ProtectHome=yes', 'PrivateDevices=yes',
                  'ProtectKernelTunables=yes', 'ProtectControlGroups=yes',
                  'RestrictNamespaces=yes', 'RestrictAddressFamilies=AF_INET AF_INET6',
                  'ReadWritePaths=' + str(scratch), 'UMask=0077', 'LimitCORE=0',
                  'MemoryMax=768M', 'MemorySwapMax=0', 'RuntimeMaxSec=180',
                  'TimeoutStopSec=10', 'KillMode=control-group',
                  'WorkingDirectory=' + str(source),
                  'InaccessiblePaths=' + ' '.join('-' + path for path in HIDDEN_PATHS)]
    arguments = ['systemd-run', '--quiet', '--wait', '--pipe', '--collect', '--unit=' + unit]
    for value in properties:
        arguments += ['--property', value]
    # systemd-run's environment is also clean; only these explicit variables reach Node.
    for key, value in env.items():
        arguments += ['--setenv=' + key + '=' + value]
    arguments += ['--', *command]
    timed_out = False
    try:
        result = invoke(arguments, env=env, timeout=205)
        output, exit_code = result.stdout + result.stderr, result.returncode
    except subprocess.TimeoutExpired as error:
        timed_out = True
        output = (error.stdout or b'') + (error.stderr or b'')
        exit_code = 124
        # Only this newly created QA unit may be stopped, never a business unit.
        invoke(['systemctl', 'stop', unit + '.service'], timeout=20)
    path = log_root / (role + '.tap.log')
    path.write_bytes(output)
    path.chmod(0o600)
    text = output.decode(errors='replace')
    counts = parse_tap(text)
    after = source_inventory(source)
    assert before == after, 'Read-only source changed during tests'
    assert source_inventory(original_source) == original_hashes
    guard_log = scratch / 'network-guard.jsonl'
    events = [json.loads(line) for line in guard_log.read_text().splitlines()] if guard_log.exists() else []
    loaded = any(event.get('kind') == 'guard-loaded' for event in events)
    safe_events = {}
    for event in events:
        kind = event.get('kind')
        assert kind in ['guard-loaded', 'non-loopback-target', 'non-tcp-connection',
                        'non-owned-loopback-port', 'non-tcp-listener', 'fixed-service-port-listener',
                        'unsupported-http-protocol', 'non-tcp-http', 'non-owned-http-port',
                        'unsupported-fetch-protocol', 'non-owned-fetch-port',
                        'subprocess-disabled', 'udp-disabled']
        safe_events[kind] = safe_events.get(kind, 0) + 1
    stopped = invoke(['systemctl', 'is-active', unit + '.service']).stdout.decode().strip()
    assert stopped not in ['active', 'activating', 'reloading']
    passed = (exit_code == 0 and loaded and (counts['tests'] or 0) > 0
              and counts['fail'] == 0 and counts['cancelled'] == 0)
    complete = passed and counts['skipped'] == 0 and counts['todo'] == 0
    return {'role': role, 'passed': passed, 'complete': complete, 'exitCode': exit_code,
            'timedOut': timed_out, 'counts': counts, 'testFiles': [str(p.relative_to(source)) for p in files],
            'testFileHashes': {str(p.relative_to(source)): before[str(p.relative_to(source))] for p in files},
            'allRoleCodeHashesUnchanged': True, 'supplementalTestFiles': supplemental, 'networkGuardLoaded': loaded,
            'guardEvents': safe_events, 'privateNetworkNamespaceRequired': True,
            'realConfigurationAndDataMasked': True, 'syntheticScratch': str(scratch),
            'environmentInherited': False, 'qaUnitRunningAfterSuite': False,
            'logFile': str(path), 'failureClass': classify_failure(text, exit_code) if not complete else None}


def gate_checks(args, log_root):
    script, config = args.gate_script, args.gate_config
    if not script.is_file() or not config.is_file():
        return {'passed': False, 'status': 'pending', 'reason': 'gate-artifacts-not-ready',
                'productionServiceStarted': False}
    assert script.resolve().is_relative_to(args.release_root)
    assert config == Path('/etc/baseballmaster-production/runtime.json')
    ast.parse(script.read_text(), filename=str(script))
    runtime = json.loads(config.read_text())
    gate_path = runtime.get('activationFile') or runtime.get('activation_file')
    if gate_path:
        permission_path = Path(gate_path)
        assert permission_path.is_relative_to('/etc/baseballmaster-production')
        permission = json.loads(permission_path.read_text())
        for key in ['trafficActivationApproved', 'oldWritersStopped']:
            assert permission.get(key) is not True
    records = []
    for role in ROLES:
        expected = ['/usr/bin/python3', str(script), str(config), role]
        checked_unit = None
        if args.unit_draft_directory:
            matches = []
            for unit in args.unit_draft_directory.glob('*.service'):
                for line in unit.read_text().splitlines():
                    if line.startswith('ExecStartPre='):
                        command = shlex.split(line.split('=', 1)[1])
                        if command == expected:
                            matches.append(unit.name)
            assert len(matches) == 1, 'Expected exactly one production guard for role'
            checked_unit = matches[0]
        # Guard only. Never execute ExecStart, enable, start or restart a unit.
        result = invoke(expected, env={'PATH': '/usr/bin:/bin', 'LANG': 'C.UTF-8'}, timeout=12)
        output = result.stdout + result.stderr
        path = log_root / ('closed-gate-' + role + '.log')
        path.write_bytes(output)
        path.chmod(0o600)
        text = output.decode(errors='replace')
        diagnostics = []
        for line in text.splitlines():
            try:
                value = json.loads(line)
                if isinstance(value, dict):
                    diagnostics.append(value)
            except json.JSONDecodeError:
                pass
        denied = next((value for value in reversed(diagnostics)
                       if value.get('blockedBeforeStart') is True), {})
        rejected = result.returncode != 0
        activation_rejection = (denied.get('passed') is False
                                and denied.get('blockedBeforeStart') is True
                                and denied.get('stage') == 'activation')
        records.append({'role': role, 'rejected': rejected, 'exitCode': result.returncode,
                        'unitDraft': checked_unit, 'guardOnly': True,
                        'rejectionStage': denied.get('stage'),
                        'activationRejectionVerified': activation_rejection,
                        'passed': rejected and activation_rejection, 'logFile': str(path)})
    return {'passed': all(record['passed'] for record in records), 'status': 'completed',
            'checks': records, 'unitDraftCommandsVerified': bool(args.unit_draft_directory),
            'productionServiceStarted': False}


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--batch', required=True)
    parser.add_argument('--roles', nargs='+', choices=ROLES, default=ROLES)
    parser.add_argument('--release-root', type=Path, required=True)
    parser.add_argument('--gate-script', type=Path)
    parser.add_argument('--gate-config', type=Path,
                        default=Path('/etc/baseballmaster-production/runtime.json'))
    parser.add_argument('--unit-draft-directory', type=Path)
    parser.add_argument('--mode', choices=['all', 'tests', 'gates'], default='all')
    args = parser.parse_args()
    args.release_root = args.release_root.absolute()
    args.gate_script = args.gate_script or args.release_root / 'operations/authorized_runtime_prestart.py'
    guard(args.batch, args.release_root)
    run_id = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-' + uuid.uuid4().hex[:8]
    output = CONTROL / 'authorized-runtime-validation' / args.batch / run_id
    private_directory(output)
    scratch = RUN_BASE / args.batch / run_id
    private_directory(scratch)
    support = scratch / 'support'
    private_directory(support)
    own = Path(__file__).resolve().parent
    for source, name in [('authorized_runtime_network_guard.mjs', 'network-guard.mjs'),
                         ('lifan-test-connections.mjs', 'lifan-test-connections.mjs')]:
        shutil.copyfile(own / source, support / name)
        (support / name).chmod(0o600)
    production_units = ['baseballmaster-' + prefix + '-' + role + suffix
                        for prefix in ['mainland', 'production']
                        for role in [*ROLES, 'web', 'storage', 'backup', 'check', 'lifan-backup']
                        for suffix in ['.service', '.timer']]
    if args.unit_draft_directory:
        assert args.unit_draft_directory.is_dir()
        production_units += [p.name for p in args.unit_draft_directory.iterdir()
                             if p.suffix in ['.service', '.timer']
                             and re.fullmatch(r'[A-Za-z0-9_.@\\-]+', p.name)]
    production_units = sorted(set(production_units))
    before = host_state(production_units)
    for unit in STAGING_UNITS:
        assert before['units'][unit]['ActiveState'] == 'active'
    save(output / 'before.json', before)
    report = {'checkedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'host': HOST, 'mode': args.mode, 'batch': args.batch, 'rolesRequested': args.roles,
              'releaseRoot': str(args.release_root), 'outputDirectory': str(output),
              'syntheticFixturesOnly': True, 'inheritedProviderCredentialsUsed': False,
              'realPrecopyDataUsedAsFixture': False, 'productionServiceStarted': False,
              'dnsChanged': False, 'browserTestsRun': False}
    suites, gates, failure = [], None, None
    try:
        if args.mode in ['all', 'tests']:
            for role in args.roles:
                try:
                    suites.append(run_suite(role, args.release_root, scratch, output, support, run_id))
                except Exception as error:
                    suites.append({'role': role, 'passed': False, 'complete': False,
                                   'failureClass': 'runner-or-source-validation-failure',
                                   'errorType': type(error).__name__, 'detailsSuppressed': True})
        if args.mode in ['all', 'gates']:
            gates = gate_checks(args, output)
    except Exception as error:
        failure = {'errorType': type(error).__name__, 'detailsSuppressed': True}
    finally:
        after = host_state(production_units)
        save(output / 'after.json', after)
        staging_unchanged = all(before['units'][u] == after['units'][u] for u in STAGING_UNITS)
        listeners_unchanged = before['listeners'] == after['listeners']
        report.update(suites=suites, closedProductionGateChecks=gates,
                      stagingServiceStatesUnchanged=staging_unchanged,
                      hostListenersUnchanged=listeners_unchanged,
                      public80And443Unbound=True, productionUnitsStillNotRunning=True,
                      runnerFailure=failure)
        report['backendTestsPassed'] = (len(suites) == len(args.roles)
                                        and all(suite.get('complete') is True for suite in suites))
        report['passed'] = (failure is None and staging_unchanged and listeners_unchanged
                            and (args.mode == 'gates' or report['backendTestsPassed'])
                            and (args.mode == 'tests' or (gates or {}).get('passed') is True))
        save(output / 'report.json', report)
        print(json.dumps(report, ensure_ascii=False))
    return 0 if report['passed'] else 1


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception as error:
        print(json.dumps({'passed': False, 'errorType': type(error).__name__,
                          'detailsSuppressed': True}), file=sys.stderr)
        sys.exit(1)
