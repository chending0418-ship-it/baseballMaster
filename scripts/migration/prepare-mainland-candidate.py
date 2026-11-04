#!/usr/bin/env python3
"""Build an inactive, secret-free production candidate from the verified release."""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import shutil


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--workspace', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    workspace, output = Path(args.workspace).resolve(), Path(args.output).resolve()
    assert not output.exists()
    output.mkdir(mode=0o700, parents=True)
    app = workspace / 'apps/baseball-scorekeeper'
    tools = app / 'scripts/migration'
    original = workspace / 'local/output/deployments/shanghai-staging/synthetic-20261023T104511Z'
    frozen = original / 'frozen-source'
    manifest_bytes = (original / 'source-manifest.json').read_bytes()
    manifest = json.loads(manifest_bytes)
    assert len(manifest['files']) == 146 and manifest['productionDataIncluded'] is False
    for name, digest in manifest['files'].items():
        assert hashlib.sha256((frozen / name).read_bytes()).hexdigest() == digest
    accounts = json.loads((workspace / 'local/validation/icp-migration/2026-10-23/shanghai-production-accounts.json').read_text())
    root, config, data = '/opt/baseballmaster-production', '/etc/baseballmaster-production', '/var/lib/baseballmaster-production'
    mount = '/mnt/baseballmaster-production-team'
    node = '/opt/baseballmaster-node/node-v24.21.0-linux-x64/bin/node'
    runtime = {
        'schemaVersion': 1, 'syntheticValidation': False, 'host': '101.43.112.8',
        'bucket': 'bm-backup-sh-1387380066', 'activationFile': config + '/activation.json',
        'finalDataManifest': config + '/final-data-manifest.json',
        'codeRoot': root + '/current', 'sourceManifest': root + '/current/source-manifest.json',
        'sourceManifestCanonicalSHA256': hashlib.sha256(json.dumps(manifest, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()).hexdigest(),
        'teamDatabase': data + '/team/collaboration.sqlite', 'teamMount': mount,
        'teamBackupKey': config + '/team-backup.key', 'liveMount': '/run/baseballmaster-production-live',
        'teamAdminConfig': config + '/team-admin.json', 'teamPort': 18788, 'livePort': 19088, 'lifanPort': 19089,
        'liveInternalBase': 'http://127.0.0.1:19088',
        'lifanDatabase': data + '/lifan/registrations.sqlite', 'lifanConfig': config + '/lifan.json',
        'lifanBackupKey': config + '/lifan-backup.key', 'backupWork': data + '/backup-work',
        'cosCredentialsFile': config + '/cos-credentials.json', 'cosfsPasswordFile': config + '/passwd-cosfs',
        'cosClientRoot': root + '/operations/cos-client', 'prestartScript': root + '/operations/mainland-prestart.mjs',
    }
    gate = {'schemaVersion': 1, 'activeHost': '101.43.112.8', 'dataCopyApproved': False,
            'trafficActivationApproved': False, 'oldWritersStopped': False,
            'finalDataManifestVerified': False, 'finalDataManifestCanonicalSHA256': None,
            'productionTimersApproved': False, 'approvalReference': None,
            'safetyJournalID': None, 'recoveryEpoch': None}
    gate['adminBridgeSHA256'] = None
    def write(name, value):
        path = output / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(value)
        path.chmod(0o600)
    def write_json(name, value):
        write(name, json.dumps(value, ensure_ascii=False, indent=2) + '\n')
    write_json('configuration/runtime.json', runtime)
    write_json('configuration/activation.json.example', gate)
    write('configuration/team.env.example', '\n'.join([
        'NODE_ENV=production', 'BM_TEAM_DATABASE=' + runtime['teamDatabase'], 'BM_TEAM_PORT=18788',
        'BM_TEAM_TRUST_PROXY=1', 'BM_TEAM_REQUESTS_PER_MINUTE=600',
        'BM_TEAM_BACKUP_KEY=' + runtime['teamBackupKey'], 'BM_TEAM_SAFETY_JOURNAL=' + mount + '/journal',
        'BM_TEAM_BACKUPS=' + mount + '/backups', 'BM_TEAM_BACKUP_STATUS=' + data + '/team/backup-status.json',
        'BM_ADMIN_CONFIG=' + config + '/team-admin.json', '']))
    write('configuration/live.env.example', '\n'.join([
        'NODE_ENV=production', 'HOST=127.0.0.1', 'PORT=19088', 'TRUST_PROXY=1',
        'LIVE_DB=' + runtime['liveMount'] + '/live.sqlite', 'BM_ADMIN_BRIDGE_TOKEN=COPY_EXISTING_VALUE_SECURELY', '']))
    write_json('configuration/team-admin.json.example', {
        'salt': 'COPY_EXISTING_SALT_SECURELY', 'passwordHash': 'COPY_EXISTING_HASH_SECURELY',
        'legacyToken': 'COPY_EXISTING_TOKEN_SECURELY', 'legacyBase': runtime['liveInternalBase']})
    write_json('configuration/lifan.json.example', {
        'passwordHash': 'COPY_EXISTING_HASH_SECURELY', 'encryptionKey': 'COPY_EXISTING_KEY_SECURELY',
        'dbPath': runtime['lifanDatabase'], 'origin': 'https://baseballmaster.cc', 'port': 19089, 'trustProxy': True})
    write('configuration/hong-kong-maintenance.include.conf', '# Server-context include: baseballmaster.cc HTTPS vhost only; requires approved shared Nginx reload.\n'
          'if (-f /etc/baseballmaster-migration-maintenance.on) { return 503; }\n')
    for name in ['state_transfer.py', 'state-archive.mjs', 'mainland-prestart.mjs', 'mainland-storage-start.py', 'mainland-lifan-backup.mjs']:
        write('operations/' + name, (tools / name).read_text())
    # Use the exact route snippets that passed the prior synthetic HTTPS tests.
    for role, port_before, port_after in [('v3', '8788', '18788'), ('live', '18088', '19088'), ('lifan', '18089', '19089')]:
        source = frozen / 'verification' / ('nginx-' + role + '.conf')
        write('nginx/routes-' + role + '.conf', source.read_text().replace('127.0.0.1:' + port_before, '127.0.0.1:' + port_after))
    nginx = (workspace / 'local/validation/icp-migration/2026-10-23/shanghai-staging-nginx-current.conf').read_text()
    nginx = nginx.replace('/run/baseballmaster-staging-nginx.pid', '/run/baseballmaster-production-nginx.pid')
    nginx = nginx.replace('/var/lib/baseballmaster-staging/nginx', data + '/nginx')
    nginx = nginx.replace('/opt/baseballmaster-staging/current/website', root + '/current/website')
    nginx = nginx.replace('/etc/baseballmaster-staging/routes-', config + '/routes-')
    import re
    nginx = re.sub(r'/etc/baseballmaster-staging/tls/public-[a-f0-9]+/fullchain.pem', config + '/tls/fullchain.pem', nginx)
    nginx = re.sub(r'/etc/baseballmaster-staging/tls/public-[a-f0-9]+/privkey.pem', config + '/tls/privkey.pem', nginx)
    nginx = nginx.replace('listen 127.0.0.1:8443 ssl', 'listen 443 ssl')
    nginx = nginx.replace('        charset utf-8;', '        if (-f ' + config + '/maintenance.on) { return 503; }\n        charset utf-8;')
    nginx = nginx.replace('    server {\n        listen 443 ssl default_server;',
        '    server { listen 80 default_server; server_name _; return 444; }\n'
        '    server { listen 80; server_name baseballmaster.cc www.baseballmaster.cc; return 308 https://$host$request_uri; }\n'
        '    server {\n        listen 443 ssl default_server;', 1)
    write('nginx/nginx.conf', nginx)
    prefix = 'baseballmaster-mainland'
    prestart = node + ' ' + runtime['prestartScript'] + ' ' + config + '/runtime.json '
    common = ('Restart=on-failure\nRestartSec=5\nTimeoutStopSec=30\nUMask=0077\nLimitCORE=0\n'
              'NoNewPrivileges=true\nPrivateTmp=true\nProtectSystem=strict\nProtectHome=true\n'
              'RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX\nSlice=' + prefix + '.slice\nMemorySwapMax=0\n')
    write('systemd/' + prefix + '.slice', '[Unit]\nDescription=Mainland baseball service resource budget\n[Slice]\nMemoryHigh=2G\nMemoryMax=2560M\nMemorySwapMax=0\n')
    mount_unit = 'run-baseballmaster\\x2dproduction\\x2dlive.mount'
    account = accounts['bmprod-live']
    write('systemd/' + mount_unit, '[Unit]\nDescription=Mainland temporary scores, no disk or swap\nBefore=' + prefix + '-live.service\n'
          '[Mount]\nWhat=tmpfs\nWhere=' + runtime['liveMount'] + '\nType=tmpfs\n'
          f"Options=noswap,nosuid,nodev,noexec,size=256M,mode=0700,uid={account['uid']},gid={account['gid']}\n")
    for role, user, entry, env, writable, memory in [
        ('team', 'bmprod-team', 'collaboration/server.mjs', 'EnvironmentFile=' + config + '/team.env', data + '/team ' + mount, '768M'),
        ('live', 'bmprod-live', 'live/server.mjs', 'EnvironmentFile=' + config + '/live.env', runtime['liveMount'], '512M'),
        ('lifan', 'bmprod-lifan', 'lifan/server.mjs', 'Environment=LIFAN_CONFIG=' + runtime['lifanConfig'], data + '/lifan', '384M')]:
        dependencies = ''
        if role == 'team':
            dependencies = 'Requires=' + prefix + '-storage.service\nAfter=' + prefix + '-storage.service\nBindsTo=' + prefix + '-storage.service\n'
        elif role == 'live':
            dependencies = 'Requires=' + mount_unit + '\nAfter=' + mount_unit + '\nBindsTo=' + mount_unit + '\n'
        unit = '[Unit]\nDescription=Mainland ' + role + '\n' + dependencies + '[Service]\nUser=' + user + '\nGroup=' + user + '\n'
        unit += 'WorkingDirectory=' + root + '/current\n' + env + '\nExecStartPre=' + prestart + role + '\n'
        unit += 'ExecStart=' + node + ' ' + root + '/current/' + entry + '\n' + common
        unit += 'ReadWritePaths=' + writable + '\nMemoryMax=' + memory + '\n[Install]\nWantedBy=multi-user.target\n'
        write('systemd/' + prefix + '-' + role + '.service', unit)
    write('systemd/' + prefix + '-storage.service', '[Unit]\nDescription=Dedicated mainland production/team COS prefix\nAfter=network-online.target\nWants=network-online.target\n'
          '[Service]\nType=simple\nExecStart=/usr/bin/python3 ' + root + '/operations/mainland-storage-start.py ' + config + '/runtime.json\n'
          'ExecStartPost=/usr/bin/python3 -c "import os,time; p=\'' + mount + '\'; [(time.sleep(0.2)) for _ in iter(lambda: os.path.ismount(p), True)]"\n'
          'ExecStop=/usr/bin/umount ' + mount + '\nRestart=on-failure\nRestartSec=10\nTimeoutStartSec=45\nTimeoutStopSec=30\n'
          'UMask=0077\nLimitCORE=0\nMemoryMax=256M\nMemorySwapMax=0\nSlice=' + prefix + '.slice\n')
    write('systemd/' + prefix + '-web.service', '[Unit]\nDescription=Mainland original-domain HTTPS gateway\nAfter=' + prefix + '-team.service ' + prefix + '-live.service ' + prefix + '-lifan.service\n'
          '[Service]\nType=forking\nPIDFile=/run/baseballmaster-production-nginx.pid\nExecStartPre=' + prestart + 'web\n'
          'ExecStartPre=/usr/sbin/nginx -t -c ' + config + '/nginx.conf\nExecStart=/usr/sbin/nginx -c ' + config + '/nginx.conf\n'
          'ExecReload=/usr/sbin/nginx -s reload -c ' + config + '/nginx.conf\nExecStop=/usr/sbin/nginx -s quit -c ' + config + '/nginx.conf\n'
          'Restart=on-failure\nPrivateTmp=true\nProtectSystem=strict\nReadWritePaths=/run ' + data + '/nginx\nMemoryMax=128M\nMemorySwapMax=0\nSlice=' + prefix + '.slice\n'
          '[Install]\nWantedBy=multi-user.target\n')
    for role, user, command, writable, memory in [
        ('backup', 'bmprod-team', node + ' ' + root + '/current/collaboration/operations.mjs backup', data + '/team ' + mount, '512M'),
        ('check', 'bmprod-team', node + ' ' + root + '/current/collaboration/operations.mjs check', '', '128M'),
        ('lifan-backup', 'root', node + ' ' + root + '/operations/mainland-lifan-backup.mjs ' + config + '/runtime.json', data + '/backup-work', '256M')]:
        unit = '[Unit]\nDescription=Mainland ' + role + '\nAfter=' + prefix + '-team.service\n'
        if role in ['backup', 'check']:
            unit += 'Requires=' + prefix + '-storage.service\nAfter=' + prefix + '-storage.service\nBindsTo=' + prefix + '-storage.service\n'
        unit += '[Service]\nType=oneshot\nUser=' + user + '\nGroup=' + user + '\nEnvironmentFile=' + config + '/team.env\n'
        unit += 'ExecStartPre=' + prestart + role + '\nExecStart=' + command + '\nUMask=0077\nNoNewPrivileges=true\nPrivateTmp=true\nProtectSystem=strict\nProtectHome=true\n'
        if writable:
            unit += 'ReadWritePaths=' + writable + '\n'
        unit += 'MemoryMax=' + memory + '\nMemorySwapMax=0\nTimeoutStartSec=600\nSlice=' + prefix + '.slice\n'
        write('systemd/' + prefix + '-' + role + '.service', unit)
        schedule = 'OnActiveSec=15min\nOnUnitActiveSec=15min' if role == 'check' else 'OnCalendar=*-*-* ' + ('04:20:00' if role == 'lifan-backup' else '04:00:00') + ' Asia/Shanghai'
        write('systemd/' + prefix + '-' + role + '.timer', '[Unit]\nDescription=Mainland approved ' + role + ' schedule\n[Timer]\n' + schedule + '\nRandomizedDelaySec=60\nPersistent=false\nUnit=' + prefix + '-' + role + '.service\n[Install]\nWantedBy=timers.target\n')
    policy = json.loads((tools / 'shanghai-cos-staging-policy.json').read_text())
    policy['statement'][1]['resource'] = ['qcs::cos:ap-shanghai:uid/1387380066:bm-backup-sh-1387380066/production/*']
    write_json('cloud/BMShanghaiBackupProduction-policy.json', policy)
    write_json('cloud/production-access-transition.json', {
        'existingProgrammaticUser': 'bm-shanghai-backup', 'existingPolicyName': 'BMShanghaiBackupStaging',
        'proposedOperation': 'Replace the staging-only policy document with the production-only policy after explicit data approval',
        'newPolicyDocument': 'BMShanghaiBackupProduction-policy.json', 'removeStagingObjectGrant': True,
        'createAdditionalUserRequired': False, 'rotateCredentialValues': False,
        'requiresUserConsoleOperation': True, 'bucket': runtime['bucket'], 'region': 'ap-shanghai', 'objectPrefix': 'production/'})
    shutil.copyfile(original / 'sources.tar.gz', output / 'sources.tar.gz')
    shutil.copyfile(original / 'source-manifest.json', output / 'source-manifest.json')
    files = {str(path.relative_to(output)): hashlib.sha256(path.read_bytes()).hexdigest() for path in output.rglob('*') if path.is_file()}
    receipt = {'generatedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'status': 'inactive-draft',
               'target': '101.43.112.8', 'sourceFilesVerified': 146, 'sourceRelease': manifest['release'],
               'sourceArchiveSHA256': hashlib.sha256((output / 'sources.tar.gz').read_bytes()).hexdigest(),
               'containsProductionData': False, 'containsCredentials': False,
               'activationApprovalsDefaultFalse': True, 'unitsInstalledOrStarted': False, 'files': files}
    write_json('candidate-manifest.json', receipt)
    print(json.dumps({'output': str(output), 'files': len(files), 'sourceFilesVerified': 146, 'status': receipt['status']}))


if __name__ == '__main__':
    main()
