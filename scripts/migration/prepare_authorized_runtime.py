#!/usr/bin/env python3
"""Prepare an INACTIVE Shanghai runtime; never copies business data or starts units."""
import base64
import datetime
import hashlib
import json
import os
from pathlib import Path
import pwd
import re
import shlex
import shutil
import subprocess
import sys
import urllib.request

STAGE = 'input'
HOST = '101.43.112.8'
NODE = Path('/opt/baseballmaster-node/node-v24.21.0-linux-x64/bin/node')
CONFIG = Path('/etc/baseballmaster-production')
DATA = Path('/var/lib/baseballmaster-production')
PORTS = {'team': 18788, 'live': 19088, 'lifan': 19089,
         'voice-lab': 19104, 'voice-assistant': 19105}
USERS = {role: 'bmprod-' + role for role in PORTS}
OVERLAYS = {'index.html', 'scorekeeper.html', 'support.html', 'privacy.html',
            'coach-insights.html', 'coach-insights-support.html', 'assets/filing.css'}


def sha(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as f:
        for block in iter(lambda: f.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def write(path, value, mode=0o600, owner=None):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o755)
    assert not path.exists() and not path.is_symlink(), 'Refusing overwrite'
    body = value if isinstance(value, bytes) else value.encode()
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, mode)
    with os.fdopen(fd, 'wb') as f:
        f.write(body)
        f.flush()
        os.fsync(f.fileno())
    path.chmod(mode)
    if owner:
        account = pwd.getpwnam(owner)
        os.chown(path, account.pw_uid, account.pw_gid)


def save(path, value, mode=0o600, owner=None):
    write(path, json.dumps(value, ensure_ascii=False, indent=2) + '\n', mode, owner)


def env_values(text):
    values = {}
    for line in text.splitlines():
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        if line.startswith('export '):
            line = line[7:]
        name, separator, value = line.partition('=')
        assert separator and re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', name.strip())
        parsed = shlex.split(value, comments=False, posix=True)
        assert len(parsed) <= 1, 'Unsupported environment syntax; review privately'
        values[name.strip()] = parsed[0] if parsed else ''
    return values


def env_text(values):
    for value in values.values():
        assert '\n' not in value and '\r' not in value and '\x00' not in value
    return ''.join(k + '="' + v.replace('\\', '\\\\').replace('"', '\\"') + '"\n'
                   for k, v in values.items())


def main():
    global STAGE
    os.umask(0o077)
    request = json.load(sys.stdin)
    assert os.geteuid() == 0 and request['bmApprovedOfflinePrecopy'] is True
    batch = request['batch']
    assert re.fullmatch(r'precopy-[0-9TZ]+-[a-f0-9]{8}', batch)
    STAGE = 'host'
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    assert opener.open('http://metadata.tencentyun.com/latest/meta-data/public-ipv4',
                       timeout=4).read().decode().strip() == HOST
    assert subprocess.check_output([str(NODE), '--version']).decode().strip() == 'v24.21.0'
    source = Path('/var/lib/baseballmaster-production-precopy') / batch / 'verification'
    authorization = json.loads((source.parent / 'authorization.json').read_text())
    assert authorization['realDataOfflinePrecopyApproved'] is True
    assert authorization['trafficActivationApproved'] is False
    release = Path('/opt/baseballmaster-production/releases') / batch
    assert not release.exists() and not CONFIG.exists()
    assert not (Path('/opt/baseballmaster-production') / 'current').exists()
    if DATA.exists():
        assert not any(p.is_file() for p in DATA.rglob('*'))
    STAGE = 'authenticated-source-manifests'
    for kind in ['code', 'data', 'configuration']:
        folder = source / kind
        manifest = json.loads((folder / 'manifest.json').read_text())
        assert manifest['purpose'] == 'authorized-offline-precopy'
        assert manifest['kind'] == kind and manifest['activationEligible'] is False
        actual = {str(p.relative_to(folder)) for p in folder.rglob('*') if p.is_file()}
        assert actual == set(manifest['files']) | {'manifest.json'}
        for name, digest in manifest['files'].items():
            p = folder / name
            assert not Path(name).is_absolute() and '..' not in Path(name).parts
            assert p.is_file() and not p.is_symlink() and sha(p) == digest
    fs = source / 'configuration/filesystem'
    def old(name):
        p = fs / name.lstrip('/')
        assert p.is_file() and not p.is_symlink()
        return p
    STAGE = 'private-configurations'
    team = env_values(old('/etc/baseballmaster-collaboration.env').read_text())
    bridge = env_values(old('/etc/baseballmaster-admin-live.env').read_text())
    admin = json.loads(old('/www/server/baseballmaster-collaboration-data/admin-auth.json').read_text())
    team_key = old('/www/server/baseballmaster-collaboration-data/backup.key').read_bytes()
    lifan = json.loads(old('/etc/lifan-registration/config.json').read_text())
    assert len(team_key) == 32 and admin['legacyToken'] == bridge['BM_ADMIN_BRIDGE_TOKEN']
    lifan_backup_key = base64.b64decode(request['newLifanBackupKeyBase64'], validate=True)
    assert len(lifan_backup_key) == 32 and lifan_backup_key != team_key
    voice = {role: env_values(old('/www/server/baseballmaster-' + role + '/config.env').read_text())
             for role in ['voice-lab', 'voice-assistant']}
    for role, values in voice.items():
        assert values['PUBLIC_ORIGIN'] == 'https://baseballmaster.cc'
        values.update(HOST='127.0.0.1', PORT=str(PORTS[role]))
    voice['voice-assistant']['VOICE_DATA_DIR'] = str(DATA / 'voice-assistant')
    admin['legacyBase'] = 'http://127.0.0.1:19088'
    lifan.update(dbPath=str(DATA / 'lifan/registrations.sqlite'), port=19089,
                 trustProxy=True, origin='https://baseballmaster.cc')
    mount = '/mnt/baseballmaster-production-team'
    team_env = {'BM_TEAM_DATABASE': str(DATA / 'team/collaboration.sqlite'),
                'BM_TEAM_PORT': '18788', 'BM_TEAM_BACKUP_KEY': str(CONFIG / 'team-backup.key'),
                'BM_TEAM_SAFETY_JOURNAL': mount + '/journal', 'BM_TEAM_BACKUPS': mount + '/backups'}
    team.update(team_env, NODE_ENV='production', BM_TEAM_TRUST_PROXY='1',
                BM_ADMIN_CONFIG=str(CONFIG / 'team-admin.json'),
                BM_TEAM_BACKUP_STATUS=str(DATA / 'team/backup-status.json'))
    live = dict(bridge, NODE_ENV='production', HOST='127.0.0.1', PORT='19088',
                TRUST_PROXY='1', LIVE_DB='/run/baseballmaster-production-live/live.sqlite')
    credentials_file = Path('/etc/baseballmaster-staging/cos-credentials.json')
    assert credentials_file.stat().st_mode & 0o077 == 0
    credentials = json.loads(credentials_file.read_text())
    assert credentials['Region'] == 'ap-shanghai'
    assert credentials['Bucket'] == 'bm-backup-sh-1387380066'
    credentials = dict(credentials, Prefix='production/')
    overlay = request.get('overlayFiles', {})
    before = request.get('overlayBeforeSHA256', {})
    assert set(overlay) <= OVERLAYS and set(overlay) == set(before)
    decoded = {}
    for name, encoded in overlay.items():
        body = base64.b64decode(encoded, validate=True)
        assert len(body) <= 2 * 1024**2
        p = source / 'code/website' / name
        if p.exists():
            assert before[name] == sha(p)
        else:
            assert name == 'assets/filing.css' and before[name] is None
        decoded[name] = body
    helper = Path(__file__).with_name('authorized_runtime_prestart.py')
    assert helper.is_file()
    STAGE = 'service-users'
    for role, user in USERS.items():
        try:
            account = pwd.getpwnam(user)
        except KeyError:
            assert role in ['voice-lab', 'voice-assistant']
            subprocess.run(['useradd', '--system', '--user-group', '--home-dir', '/nonexistent',
                            '--no-create-home', '--shell', '/usr/sbin/nologin', user],
                           check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            account = pwd.getpwnam(user)
        assert account.pw_shell == '/usr/sbin/nologin'
    STAGE = 'code-installation-only'
    roles_root = release / 'roles'
    roles_root.mkdir(parents=True, mode=0o755)
    for directory in [release.parent.parent, release.parent, release, roles_root]:
        directory.chmod(0o755)
    for role in [*PORTS, 'website']:
        shutil.copytree(source / 'code' / role, roles_root / role)
    for p in roles_root.rglob('*'):
        assert not p.is_symlink()
        assert '.secrets' not in p.relative_to(roles_root).parts
        p.chmod(0o755 if p.is_dir() else 0o644)
    for name, body in decoded.items():
        p = roles_root / 'website' / name
        p.parent.mkdir(parents=True, exist_ok=True, mode=0o755)
        p.write_bytes(body)
        p.chmod(0o644)
    entries = {'team': 'collaboration/server.mjs', 'live': 'server.mjs', 'lifan': 'server.mjs',
               'voice-lab': 'server.mjs', 'voice-assistant': 'server.mjs'}
    for role, entry in entries.items():
        assert (roles_root / role / entry).is_file()
    code_manifest = {'release': batch, 'sourceCodeManifestSHA256': sha(source / 'code/manifest.json'),
                     'files': {str(p.relative_to(roles_root)): sha(p) for p in sorted(roles_root.rglob('*')) if p.is_file()},
                     'websiteOverlayFiles': sorted(overlay)}
    save(release / 'code-manifest.json', code_manifest, 0o644)
    operations = release / 'operations'
    operations.mkdir(mode=0o755)
    operations.chmod(0o755)
    write(operations / helper.name, helper.read_bytes(), 0o644)
    write(operations / 'refresh_website_manifest.py', Path(__file__).with_name('refresh_website_manifest.py').read_bytes(), 0o644)
    CONFIG.mkdir(mode=0o711)
    CONFIG.chmod(0o711)
    for role, user in USERS.items():
        folder = DATA / role
        folder.mkdir(parents=True, exist_ok=True, mode=0o700)
        assert not any(folder.iterdir())
        account = pwd.getpwnam(user)
        os.chown(folder, account.pw_uid, account.pw_gid)
        folder.chmod(0o700)
    DATA.chmod(0o711)
    for name in ['backup-work', 'nginx/body', 'nginx/proxy']:
        (DATA / name).mkdir(parents=True, exist_ok=True, mode=0o700)
    worker = pwd.getpwnam('www-data')
    (DATA / 'nginx').chmod(0o711)
    for name in ['nginx/body', 'nginx/proxy']:
        os.chown(DATA / name, worker.pw_uid, worker.pw_gid)
    mount_point = Path(mount)
    mount_point.mkdir(mode=0o700, exist_ok=True)
    assert mount_point.stat().st_uid == 0 and mount_point.stat().st_mode & 0o077 == 0
    assert not any(mount_point.iterdir()) and not os.path.ismount(mount_point)
    STAGE = 'private-configurations-installation'
    write(CONFIG / 'team.env', env_text(team))
    write(CONFIG / 'live.env', env_text(live))
    save(CONFIG / 'team-admin.json', admin, owner=USERS['team'])
    write(CONFIG / 'team-backup.key', team_key, owner=USERS['team'])
    save(CONFIG / 'lifan.json', lifan, owner=USERS['lifan'])
    write(CONFIG / 'lifan-backup.key', lifan_backup_key)
    for role, values in voice.items():
        write(CONFIG / (role + '.env'), env_text(values))
    save(CONFIG / 'cos-credentials.json', credentials)
    write(CONFIG / 'passwd-cosfs', credentials['Bucket'] + ':' + credentials['SecretId'] + ':' + credentials['SecretKey'] + '\n')
    runtime = {'schemaVersion': 1, 'host': HOST, 'release': batch,
               'configurationRoot': str(CONFIG), 'codeRoot': str(roles_root),
               'codeManifest': str(release / 'code-manifest.json'),
               'codeManifestSHA256': sha(release / 'code-manifest.json'),
               'activationFile': str(CONFIG / 'activation.json'),
               'finalDataManifest': str(CONFIG / 'final-data-manifest.json'),
               'oldWriterFenceEvidence': str(CONFIG / 'hong-kong-writer-fence.json'),
               'prestartScript': str(operations / helper.name),
               'roles': {role: {'entry': str(roles_root / role / entry), 'codeRoot': str(roles_root / role),
                                'port': PORTS[role], 'user': USERS[role]} for role, entry in entries.items()},
               'teamEnvironment': team_env, 'teamDatabase': team_env['BM_TEAM_DATABASE'],
               'teamMount': mount, 'teamBackupKey': str(CONFIG / 'team-backup.key'),
               'teamAdminConfig': str(CONFIG / 'team-admin.json'),
               'liveMount': '/run/baseballmaster-production-live',
               'liveDatabase': live['LIVE_DB'], 'lifanConfig': str(CONFIG / 'lifan.json'),
               'lifanDatabase': lifan['dbPath'], 'lifanBackupKey': str(CONFIG / 'lifan-backup.key'),
               'voiceAssistantData': str(DATA / 'voice-assistant'),
               'cosCredentialsFile': str(CONFIG / 'cos-credentials.json'),
               'cosfsPasswordFile': str(CONFIG / 'passwd-cosfs'), 'bucket': credentials['Bucket'],
               'backupWork': str(DATA / 'backup-work')}
    for role in voice:
        runtime['roles'][role]['basePath'] = voice[role]['PUBLIC_BASE_PATH']
    gate = {'schemaVersion': 1, 'activeHost': HOST, 'codeRelease': batch,
            'dataCopyApproved': True, 'trafficActivationApproved': False,
            'oldWritersStopped': False, 'finalDataManifestVerified': False,
            'productionTimersApproved': False, 'approvalReference': None,
            'finalDataManifestSHA256': None, 'oldWriterFenceEvidenceSHA256': None,
            'adminBridgeSHA256': None, 'voiceLedgerPepperSHA256': None,
            'safetyJournalID': None, 'recoveryEpoch': None}
    save(CONFIG / 'runtime.json', runtime, 0o644)
    save(CONFIG / 'activation.json', gate, 0o644)
    STAGE = 'draft-units-and-gateway'
    draft = release / 'operations-draft'
    units = draft / 'systemd'
    units.mkdir(parents=True, mode=0o755)
    prefix = 'baseballmaster-mainland'
    check = '/usr/bin/python3 ' + runtime['prestartScript'] + ' ' + str(CONFIG / 'runtime.json') + ' '
    common = ('UMask=0077\nNoNewPrivileges=true\nPrivateTmp=true\nProtectSystem=strict\n'
              'ProtectHome=true\nLimitCORE=0\nMemorySwapMax=0\nSlice=' + prefix + '.slice\n')
    write(units / (prefix + '.slice'), '[Unit]\nDescription=Mainland resource budget\n[Slice]\nMemoryHigh=2G\nMemoryMax=2816M\nMemorySwapMax=0\n', 0o644)
    mount_unit = 'run-baseballmaster\\x2dproduction\\x2dlive.mount'
    account = pwd.getpwnam(USERS['live'])
    write(units / mount_unit, '[Unit]\nDescription=Temporary scores in RAM only\n[Mount]\nWhat=tmpfs\nWhere=' + runtime['liveMount'] + '\nType=tmpfs\nOptions=noswap,nosuid,nodev,noexec,size=256M,mode=0700,uid=' + str(account.pw_uid) + ',gid=' + str(account.pw_gid) + '\n', 0o644)
    for role in PORTS:
        dependency = ''
        if role == 'team':
            dependency = 'Requires=' + prefix + '-storage.service\nAfter=' + prefix + '-storage.service\nBindsTo=' + prefix + '-storage.service\n'
        if role == 'live':
            dependency = 'Requires=' + mount_unit + '\nAfter=' + mount_unit + '\nBindsTo=' + mount_unit + '\n'
        env = 'Environment=LIFAN_CONFIG=' + str(CONFIG / 'lifan.json') if role == 'lifan' else 'EnvironmentFile=' + str(CONFIG / (role + '.env'))
        writable = runtime['liveMount'] if role == 'live' else str(DATA / role)
        if role == 'team':
            writable += ' ' + mount
        memory = {'team': '768M', 'live': '384M', 'lifan': '256M', 'voice-lab': '256M', 'voice-assistant': '384M'}[role]
        text = '[Unit]\nDescription=Mainland ' + role + '\n' + dependency + '[Service]\nUser=' + USERS[role] + '\nGroup=' + USERS[role] + '\nWorkingDirectory=' + str(roles_root / role) + '\n' + env + '\nExecStartPre=' + check + role + '\nExecStart=' + str(NODE) + ' ' + runtime['roles'][role]['entry'] + '\nRestart=on-failure\nRestartSec=5\n' + common + 'ReadWritePaths=' + writable + '\nMemoryMax=' + memory + '\n[Install]\nWantedBy=multi-user.target\n'
        write(units / (prefix + '-' + role + '.service'), text, 0o644)
    STAGE = 'nginx-draft'
    paths = {'/www/wwwroot/baseballmaster.cc': str(roles_root / 'website'),
             '/www/server/baseballmaster-collaboration/current': str(roles_root / 'team'),
             '/www/server/baseballmaster-live/current': str(roles_root / 'live'),
             '/opt/lifan-registration/current': str(roles_root / 'lifan'),
             '/www/server/baseballmaster-voice-lab/current': str(roles_root / 'voice-lab'),
             '/www/server/baseballmaster-voice-assistant/current': str(roles_root / 'voice-assistant')}
    def map_nginx(text):
        for a, b in paths.items():
            text = text.replace(a, b)
        for a, b in [(8788, 18788), (18088, 19088), (18089, 19089), (18104, 19104), (18105, 19105)]:
            text = text.replace('127.0.0.1:' + str(a), '127.0.0.1:' + str(b))
        text = re.sub(r'^\s*(access_log|error_log)\s+[^;]+;', '', text, flags=re.M)
        text = re.sub(r'^\s*http2\s+on\s*;', '', text, flags=re.M)
        return text
    vhost = old('/www/server/panel/vhost/nginx/baseballmaster.cc.conf').read_text()
    certificates = {}
    for directive in ['ssl_certificate', 'ssl_certificate_key']:
        found = re.findall(r'^\s*' + directive + r'\s+([^;]+);', vhost, re.M)
        assert found
        certificates[directive] = found[0].strip().strip('"').strip("'")
    (CONFIG / 'tls').mkdir(mode=0o700)
    for directive, basename in [('ssl_certificate', 'fullchain.pem'), ('ssl_certificate_key', 'privkey.pem')]:
        write(CONFIG / 'tls' / basename, old(certificates[directive]).read_bytes())
        vhost = vhost.replace(certificates[directive], str(CONFIG / 'tls' / basename))
    for role, name in [('team', 'baseballmaster-v3.inc'), ('voice-lab', 'baseballmaster-voice-lab.inc'), ('voice-assistant', 'baseballmaster-voice-assistant.inc')]:
        old_path = '/www/server/panel/vhost/nginx/' + name
        write(CONFIG / ('routes-' + role + '.inc'), map_nginx(old(old_path).read_text()))
        vhost = vhost.replace(old_path, str(CONFIG / ('routes-' + role + '.inc')))
    vhost = map_nginx(vhost)
    # These exact aliases do not capture API, lifan or nested support URLs.
    vhost = re.sub(r'\s*location\s*=\s*/(?:scorekeeper|coach-insights)\.html\s*\{[^{}]*\}', '', vhost)
    pretty = Path(__file__).with_name('website-pretty-routes.inc')
    write(CONFIG / 'routes-pretty-pages.inc', pretty.read_bytes())
    ssl_start = vhost.index('listen 443 ssl;')
    server_name = re.search(r'server_name\s+[^;]+;', vhost[ssl_start:])
    assert server_name
    end = ssl_start + server_name.end()
    vhost = vhost[:end] + '\ninclude ' + str(CONFIG / 'routes-pretty-pages.inc') + ';\n' + vhost[end:]
    vhost = re.sub(r'(server_name\s+[^;]+;)', r'\1\nif (-f ' + str(CONFIG / 'maintenance.on') + ') { return 503; }', vhost)
    nginx = ('user www-data;\nworker_processes 1;\npid /run/baseballmaster-production-nginx.pid;\nerror_log /dev/null crit;\nevents { worker_connections 1024; }\nhttp {\ninclude /etc/nginx/mime.types;\ndefault_type application/octet-stream;\naccess_log off;\nclient_body_temp_path ' + str(DATA / 'nginx/body') + ';\nproxy_temp_path ' + str(DATA / 'nginx/proxy') + ';\nmap $http_upgrade $connection_upgrade { default upgrade; "" close; }\n' + vhost + '\n}\n')
    write(CONFIG / 'nginx.conf', nginx)
    web = '[Unit]\nDescription=Mainland original-domain gateway\nAfter=' + ' '.join(prefix + '-' + x + '.service' for x in PORTS) + '\n[Service]\nType=forking\nPIDFile=/run/baseballmaster-production-nginx.pid\nExecStartPre=' + check + 'web\nExecStartPre=/usr/sbin/nginx -t -c ' + str(CONFIG / 'nginx.conf') + '\nExecStart=/usr/sbin/nginx -c ' + str(CONFIG / 'nginx.conf') + '\nExecStop=/usr/sbin/nginx -s quit -c ' + str(CONFIG / 'nginx.conf') + '\n' + common + 'ReadWritePaths=/run ' + str(DATA / 'nginx') + '\nMemoryMax=128M\n[Install]\nWantedBy=multi-user.target\n'
    write(units / (prefix + '-web.service'), web, 0o644)
    storage = '[Unit]\nDescription=Production team COS mount\nAfter=network-online.target\nWants=network-online.target\n[Service]\nType=simple\nExecStartPre=' + check + 'storage\nExecStart=/usr/local/bin/cosfs ' + credentials['Bucket'] + ':/production/team ' + mount + ' -f -o url=https://cos.ap-shanghai.myqcloud.com -o passwd_file=' + str(CONFIG / 'passwd-cosfs') + ' -o default_acl=private -o allow_other -o default_permissions -o uid=' + str(pwd.getpwnam(USERS['team']).pw_uid) + ' -o gid=' + str(pwd.getpwnam(USERS['team']).pw_gid) + ' -o umask=077 -o mp_umask=077 -o nosuid,nodev,noexec -o connect_timeout=5 -o readwrite_timeout=15 -o retries=1\nExecStop=/usr/bin/umount ' + mount + '\nUMask=0077\nMemoryMax=256M\nMemorySwapMax=0\nSlice=' + prefix + '.slice\n'
    write(units / (prefix + '-storage.service'), storage, 0o644)
    # Existing team operations are preserved; lifan's independent job still needs
    # its Python-prestart adapter before the corresponding draft can be enabled.
    for role, action in [('backup', 'backup'), ('check', 'check')]:
        text = '[Unit]\nDescription=Mainland ' + role + '\nRequires=' + prefix + '-storage.service\nAfter=' + prefix + '-storage.service\n[Service]\nType=oneshot\nUser=' + USERS['team'] + '\nGroup=' + USERS['team'] + '\nEnvironmentFile=' + str(CONFIG / 'team.env') + '\nExecStartPre=' + check + role + '\nExecStart=' + str(NODE) + ' ' + str(roles_root / 'team/collaboration/operations.mjs') + ' ' + action + '\n' + common + ('ReadWritePaths=' + str(DATA / 'team') + ' ' + mount + '\n' if role == 'backup' else '') + 'TimeoutStartSec=600\nMemoryMax=512M\n'
        write(units / (prefix + '-' + role + '.service'), text, 0o644)
        schedule = 'OnCalendar=*-*-* 04:00:00 Asia/Shanghai' if role == 'backup' else 'OnActiveSec=15min\nOnUnitActiveSec=15min'
        write(units / (prefix + '-' + role + '.timer'), '[Unit]\nDescription=Mainland ' + role + ' timer\n[Timer]\n' + schedule + '\nPersistent=false\nUnit=' + prefix + '-' + role + '.service\n[Install]\nWantedBy=timers.target\n', 0o644)
    lifan_helper = Path(__file__).with_name('mainland-lifan-backup.mjs').read_text()
    lifan_helper = lifan_helper.replace("execFileSync(process.execPath,[config.prestartScript,path,'lifan-backup']", "execFileSync('/usr/bin/python3',[config.prestartScript,path,'lifan-backup']")
    lifan_helper = lifan_helper.replace("${config.codeRoot}/collaboration/safety-journal.mjs", "${config.codeRoot}/team/collaboration/safety-journal.mjs")
    write(operations / 'mainland-lifan-backup.mjs', lifan_helper, 0o644)
    runtime['cosClientRoot'] = '/var/lib/baseballmaster-staging-control/cos-client'
    # SDK location is verified on-host; no provider calls occur during preparation.
    assert (Path(runtime['cosClientRoot']) / 'package.json').is_file()
    # runtime.json has just been created by this invocation; safely replace its
    # own new metadata only, never an existing deployment from another run.
    (CONFIG / 'runtime.json').write_text(json.dumps(runtime, ensure_ascii=False, indent=2) + '\n')
    (CONFIG / 'runtime.json').chmod(0o644)
    write(units / (prefix + '-lifan-backup.service'), '[Unit]\nDescription=Private lifan backup\nAfter=network-online.target\n[Service]\nType=oneshot\nExecStartPre=' + check + 'lifan-backup\nExecStart=' + str(NODE) + ' ' + str(operations / 'mainland-lifan-backup.mjs') + ' ' + str(CONFIG / 'runtime.json') + '\n' + common + 'ReadWritePaths=' + str(DATA / 'backup-work') + '\nTimeoutStartSec=600\nMemoryMax=256M\n', 0o644)
    write(units / (prefix + '-lifan-backup.timer'), '[Unit]\nDescription=Private lifan backup timer\n[Timer]\nOnCalendar=*-*-* 04:15:00 Asia/Shanghai\nPersistent=false\nUnit=' + prefix + '-lifan-backup.service\n[Install]\nWantedBy=timers.target\n', 0o644)
    receipt = {'checkedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
               'prepared': True, 'inactive': True, 'batch': batch, 'release': str(release),
               'configuration': str(CONFIG / 'runtime.json'), 'unitDraftDirectory': str(units),
               'roleFiles': len(code_manifest['files']), 'websiteOverlayFiles': sorted(overlay),
               'businessDataCopiedToProduction': False, 'productionDataFiles': 0,
               'unitsInstalled': False, 'servicesStarted': False, 'nginxReloaded': False,
               'firewallChanged': False, 'cloudPolicyChanged': False,
               'productionCOSPermissionsVerified': False,
               'remainingBeforeActivation': ['Final stopped-writer data capture and authority fence',
                    'Production COS prefix authorization verification',
                    'Nginx syntax validation and removal of any remaining Hong Kong-only include paths',
                    'Independent lifan backup cloud restore validation']}
    save(release / 'inactive-installation-receipt.json', receipt)
    print(json.dumps(receipt, ensure_ascii=False))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(json.dumps({'prepared': False, 'stage': STAGE,
                          'errorType': type(error).__name__, 'detailsSuppressed': True}), file=sys.stderr)
        sys.exit(1)
