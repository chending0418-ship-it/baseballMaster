#!/usr/bin/env python3
"""Install a verified source-only release into Shanghai's loopback-only staging."""
import hashlib
import json
import os
from pathlib import Path
import pwd
import re
import shutil
import subprocess
import sys
import urllib.request

CONTROL = Path('/var/lib/baseballmaster-staging-control')
CONFIG = Path('/etc/baseballmaster-staging')
DATA = Path('/var/lib/baseballmaster-staging')
ROOT = Path('/opt/baseballmaster-staging')
NODE = '/opt/baseballmaster-node/node-v24.21.0-linux-x64/bin/node'


def run(args, **kw):
    return subprocess.run(args, check=True, **kw)


def write(path, text, mode=0o644):
    path.write_text(text)
    path.chmod(mode)


def main():
    assert os.geteuid() == 0
    assert json.loads((CONTROL / 'synthetic-only.json').read_text())['syntheticOnly'] is True
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    assert opener.open('http://metadata.tencentyun.com/latest/meta-data/public-ipv4', timeout=3).read().decode().strip() == '101.43.112.8'
    release = sys.argv[1]
    assert re.fullmatch(r'synthetic-[0-9TZ]+', release)
    folder = ROOT / 'releases' / release
    manifest = json.loads((folder / 'source-manifest.json').read_text())
    assert manifest['syntheticOnly'] is True and manifest['productionDataIncluded'] is False
    for name, digest in manifest['files'].items():
        p = folder / name
        assert p.is_file() and not p.is_symlink() and folder in p.resolve().parents
        assert hashlib.sha256(p.read_bytes()).hexdigest() == digest, 'Source checksum mismatch: ' + name
    assert not any(p.suffix in ['.sqlite', '.pem', '.key'] or '.local' in p.parts for p in folder.rglob('*'))
    current = ROOT / 'current'
    assert not current.exists(), 'Existing staging release requires an explicit update procedure'
    current.symlink_to(folder)
    CONFIG.mkdir(mode=0o711, exist_ok=True)
    DATA.mkdir(mode=0o711, exist_ok=True)
    for suffix in ['team', 'live', 'lifan']:
        user = 'bmstage-' + suffix
        try:
            pwd.getpwnam(user)
        except KeyError:
            run(['useradd', '--system', '--user-group', '--no-create-home', '--shell', '/usr/sbin/nologin', user])
    for name, user in [('team', 'bmstage-team'), ('lifan', 'bmstage-lifan'), ('journal', 'bmstage-team'),
                       ('backups', 'bmstage-team'), ('verification', 'root'), ('nginx', 'www-data')]:
        p = DATA / name
        p.mkdir(mode=0o700, exist_ok=True)
        shutil.chown(p, user, user)
    run([NODE, str(folder / 'verification/initialize.mjs')])
    for name in ['team-admin.json', 'backup.key']:
        shutil.chown(CONFIG / name, 'bmstage-team', 'bmstage-team')
    shutil.chown(CONFIG / 'lifan.json', 'bmstage-lifan', 'bmstage-lifan')
    tls = CONFIG / 'tls'
    tls.mkdir(mode=0o700)
    quiet = {'stdout': subprocess.DEVNULL, 'stderr': subprocess.DEVNULL}
    run(['openssl', 'req', '-x509', '-nodes', '-newkey', 'rsa:2048', '-keyout', str(tls / 'ca.key'),
         '-out', str(tls / 'ca.pem'), '-days', '14', '-subj', '/CN=BaseballMaster Shanghai isolated test CA'], **quiet)
    run(['openssl', 'req', '-nodes', '-newkey', 'rsa:2048', '-keyout', str(tls / 'server.key'),
         '-out', str(tls / 'server.csr'), '-subj', '/CN=baseballmaster.cc'], **quiet)
    write(tls / 'server.ext', 'subjectAltName=DNS:baseballmaster.cc,DNS:www.baseballmaster.cc\nextendedKeyUsage=serverAuth\n')
    run(['openssl', 'x509', '-req', '-in', str(tls / 'server.csr'), '-CA', str(tls / 'ca.pem'),
         '-CAkey', str(tls / 'ca.key'), '-CAcreateserial', '-out', str(tls / 'server.pem'), '-days', '14',
         '-extfile', str(tls / 'server.ext')], **quiet)
    for name in ['ca.key', 'server.key']:
        (tls / name).chmod(0o600)
    shutil.copyfile(folder / 'verification/nginx-v3.conf', CONFIG / 'routes-v3.conf')
    shutil.copyfile(folder / 'verification/nginx-live.conf', CONFIG / 'routes-live.conf')
    shutil.copyfile(folder / 'verification/nginx-lifan.conf', CONFIG / 'routes-lifan.conf')
    nginx = '''user www-data;
worker_processes 1;
pid /run/baseballmaster-staging-nginx.pid;
error_log /dev/null crit;
events { worker_connections 512; }
http {
    include /etc/nginx/mime.types;
    default_type application/octet-stream;
    access_log off;
    server_tokens off;
    client_body_temp_path /var/lib/baseballmaster-staging/nginx/body;
    proxy_temp_path /var/lib/baseballmaster-staging/nginx/proxy;
    server {
        listen 127.0.0.1:8443 ssl default_server;
        server_name _;
        ssl_certificate /etc/baseballmaster-staging/tls/server.pem;
        ssl_certificate_key /etc/baseballmaster-staging/tls/server.key;
        return 444;
    }
    server {
        listen 127.0.0.1:8443 ssl;
        server_name baseballmaster.cc www.baseballmaster.cc;
        ssl_certificate /etc/baseballmaster-staging/tls/server.pem;
        ssl_certificate_key /etc/baseballmaster-staging/tls/server.key;
        ssl_protocols TLSv1.2 TLSv1.3;
        root /opt/baseballmaster-staging/current/website;
        charset utf-8;
        autoindex off;
        add_header X-Content-Type-Options nosniff always;
        include /etc/baseballmaster-staging/routes-live.conf;
        include /etc/baseballmaster-staging/routes-v3.conf;
        include /etc/baseballmaster-staging/routes-lifan.conf;
        location = / { try_files /index.html =404; }
        location = /index.html { try_files $uri =404; }
        location = /privacy.html { try_files $uri =404; }
        location = /support.html { try_files $uri =404; }
        location = /scorekeeper.html { try_files $uri =404; }
        location = /coach-insights.html { try_files $uri =404; }
        location = /coach-insights-support.html { try_files $uri =404; }
        location = /coach-pocket.html { return 301 /coach-insights.html$is_args$args; }
        location = /coach-pocket-support.html { return 301 /coach-insights-support.html$is_args$args; }
        location = /robots.txt { try_files $uri =404; }
        location = /sitemap.xml { try_files $uri =404; }
        location ^~ /assets/ { try_files $uri =404; }
        location = /recording/demoReview { return 308 /recording/demoReview/; }
        location ^~ /recording/demoReview/ { try_files $uri $uri/index.html =404; }
        location / { return 404; }
    }
}
'''
    write(CONFIG / 'nginx.conf', nginx)
    units = Path('/etc/systemd/system')
    write(units / 'baseballmaster-staging.slice', '[Unit]\nDescription=Isolated synthetic staging resource budget\n[Slice]\nMemoryHigh=1536M\nMemoryMax=2G\nMemorySwapMax=0\n')
    mount_path = '/run/baseballmaster-staging-live'
    mount_name = subprocess.check_output(['systemd-escape', '--path', '--suffix=mount', mount_path], text=True).strip()
    account = pwd.getpwnam('bmstage-live')
    write(units / mount_name, f'''[Unit]
Description=Synthetic live tmpfs, no disk or swap
Before=baseballmaster-staging-live.service
[Mount]
What=tmpfs
Where={mount_path}
Type=tmpfs
Options=noswap,nosuid,nodev,noexec,size=256M,mode=0700,uid={account.pw_uid},gid={account.pw_gid}
''')
    for name, user, command, env, writable, memory in [
        ('team', 'bmstage-team', 'collaboration/server.mjs', 'EnvironmentFile=/etc/baseballmaster-staging/team.env',
         '/var/lib/baseballmaster-staging/team /var/lib/baseballmaster-staging/journal /var/lib/baseballmaster-staging/backups', '768M'),
        ('live', 'bmstage-live', 'live/server.mjs', 'EnvironmentFile=/etc/baseballmaster-staging/live.env', mount_path, '512M'),
        ('lifan', 'bmstage-lifan', 'lifan/server.mjs', 'Environment=LIFAN_CONFIG=/etc/baseballmaster-staging/lifan.json',
         '/var/lib/baseballmaster-staging/lifan', '384M')]:
        mount_dependency = f'\nRequires={mount_name}\nAfter={mount_name}\nBindsTo={mount_name}' if name == 'live' else ''
        unit = f'''[Unit]
Description=Shanghai synthetic-only {name}{mount_dependency}
[Service]
User={user}
Group={user}
WorkingDirectory=/opt/baseballmaster-staging/current
{env}
ExecStart={NODE} {command}
Restart=on-failure
RestartSec=3
TimeoutStopSec=20
UMask=0077
LimitCORE=0
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths={writable}
RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX
Slice=baseballmaster-staging.slice
MemoryMax={memory}
MemorySwapMax=0
'''
        write(units / f'baseballmaster-staging-{name}.service', unit)
    write(units / 'baseballmaster-staging-web.service', '''[Unit]
Description=Shanghai loopback-only synthetic HTTPS gateway
After=baseballmaster-staging-live.service baseballmaster-staging-team.service baseballmaster-staging-lifan.service
[Service]
Type=forking
PIDFile=/run/baseballmaster-staging-nginx.pid
ExecStartPre=/usr/sbin/nginx -t -c /etc/baseballmaster-staging/nginx.conf
ExecStart=/usr/sbin/nginx -c /etc/baseballmaster-staging/nginx.conf
ExecReload=/usr/sbin/nginx -s reload -c /etc/baseballmaster-staging/nginx.conf
ExecStop=/usr/sbin/nginx -s quit -c /etc/baseballmaster-staging/nginx.conf
Restart=on-failure
PrivateTmp=true
ProtectSystem=strict
ReadWritePaths=/run /var/lib/baseballmaster-staging/nginx
Slice=baseballmaster-staging.slice
MemoryMax=128M
MemorySwapMax=0
LimitCORE=0
''')
    run(['nginx', '-t', '-c', str(CONFIG / 'nginx.conf')])
    run(['systemctl', 'daemon-reload'])
    try:
        run(['systemctl', 'start', *[f'baseballmaster-staging-{n}.service' for n in ['live', 'team', 'lifan', 'web']]])
    except Exception:
        subprocess.run(['systemctl', 'stop', *[f'baseballmaster-staging-{n}.service' for n in ['web', 'live', 'team', 'lifan']]])
        raise
    # No enable calls: these test services and all business timers stay disabled.
    print(json.dumps({'release': release, 'sourceFilesVerified': len(manifest['files']),
                      'syntheticOnly': True, 'httpsListen': '127.0.0.1:8443', 'productionDataIncluded': False}))


if __name__ == '__main__':
    main()
