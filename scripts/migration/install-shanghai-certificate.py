#!/usr/bin/env python3
"""Receive a locally held certificate over SSH stdin; install on loopback only."""
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import socket
import ssl
import subprocess
import sys
import time
import urllib.request

CONTROL = Path('/var/lib/baseballmaster-staging-control')
CONFIG = Path('/etc/baseballmaster-staging')


def command(args):
    result = subprocess.run(args, capture_output=True, timeout=10)
    if result.returncode:
        raise RuntimeError('Certificate verification command failed: ' + args[1])
    return result.stdout


def probe(path):
    context = ssl.create_default_context()
    with socket.create_connection(('127.0.0.1', 8443), timeout=5) as sock:
        with context.wrap_socket(sock, server_hostname='baseballmaster.cc') as secure:
            secure.sendall(('GET ' + path + ' HTTP/1.1\r\nHost: baseballmaster.cc\r\nConnection: close\r\n\r\n').encode())
            first_line = secure.recv(2048).split(b'\r\n', 1)[0]
            assert first_line.split()[1] == b'200', 'HTTPS health probe failed'
            return secure.version()


def main():
    assert os.geteuid() == 0
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    actual = opener.open('http://metadata.tencentyun.com/latest/meta-data/public-ipv4', timeout=3).read().decode().strip()
    assert actual == '101.43.112.8', 'Wrong target'
    assert json.loads((CONTROL / 'synthetic-only.json').read_text())['syntheticOnly'] is True
    payload = json.load(sys.stdin)
    bundle, key = payload['certificateBundle'], payload['privateKey']
    blocks = re.findall(r'-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----', bundle, re.S)
    assert len(blocks) >= 2 and 'PRIVATE KEY-----' in key
    digest = hashlib.sha256(bundle.encode()).hexdigest()
    target = CONFIG / 'tls' / ('public-' + digest[:16])
    target.mkdir(mode=0o700, exist_ok=True)
    cert_file, key_file, leaf_file = target / 'fullchain.pem', target / 'privkey.pem', target / 'leaf.pem'
    for path, content in [(cert_file, bundle), (key_file, key), (leaf_file, blocks[0] + '\n')]:
        if path.exists():
            assert path.read_bytes() == content.encode(), 'Refusing to replace different certificate material'
        else:
            # Open exclusively with private permissions from the first byte.
            with open(path, 'x', opener=lambda name, flags: os.open(name, flags, 0o600)) as file:
                file.write(content)
        path.chmod(0o600)
    cert_public = command(['openssl', 'x509', '-in', str(leaf_file), '-pubkey', '-noout'])
    key_public = command(['openssl', 'pkey', '-in', str(key_file), '-passin', 'pass:', '-pubout'])
    assert cert_public == key_public, 'Certificate and private key do not match'
    command(['openssl', 'x509', '-in', str(leaf_file), '-checkend', str(30 * 86400), '-noout'])
    for hostname in ['baseballmaster.cc', 'www.baseballmaster.cc']:
        command(['openssl', 'verify', '-purpose', 'sslserver', '-verify_hostname', hostname,
                 '-CAfile', '/etc/ssl/certs/ca-certificates.crt', '-untrusted', str(cert_file), str(leaf_file)])
    metadata = ssl._ssl._test_decode_cert(str(leaf_file))
    nginx = CONFIG / 'nginx.conf'
    before = nginx.read_text()
    # The test gateway must remain bound only to loopback before any reload.
    listeners = re.findall(r'^\s*listen\s+([^;]+);', before, re.M)
    assert listeners and all(value.startswith('127.0.0.1:8443 ') for value in listeners)
    proposed, cert_count = re.subn(r'^\s*ssl_certificate\s+[^;]+;', '        ssl_certificate ' + str(cert_file) + ';', before, flags=re.M)
    proposed, key_count = re.subn(r'^\s*ssl_certificate_key\s+[^;]+;', '        ssl_certificate_key ' + str(key_file) + ';', proposed, flags=re.M)
    assert cert_count == key_count == 2
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    backup = CONTROL / ('nginx-before-public-certificate-' + stamp + '.conf')
    if proposed != before:
        backup.write_text(before)
        backup.chmod(0o600)
        nginx.write_text(proposed)
    try:
        command(['nginx', '-t', '-c', str(nginx)])
        command(['systemctl', 'reload', 'baseballmaster-staging-web.service'])
        # Nginx reload is asynchronous: old workers can briefly serve the test CA.
        for attempt in range(25):
            try:
                health = {path: probe(path) for path in ['/', '/api/v3/health', '/livestreaming/novideo/health', '/lifan/tryout/api/health']}
                break
            except (ssl.SSLError, OSError, AssertionError):
                if attempt == 24:
                    raise
                time.sleep(0.2)
    except Exception:
        nginx.write_text(before)
        subprocess.run(['nginx', '-t', '-c', str(nginx)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.run(['systemctl', 'reload', 'baseballmaster-staging-web.service'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        raise
    receipt = {'checkedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'host': actual,
               'certificateSource': 'existing local Tencent nginx certificate export; no Hong Kong connection',
               'subjectAltName': metadata.get('subjectAltName'), 'issuer': metadata['issuer'],
               'notBefore': metadata['notBefore'], 'notAfter': metadata['notAfter'], 'certificateBundleSHA256': digest,
               'certificateFile': str(cert_file), 'privateKeyFile': str(key_file), 'privateKeyMode': '0600',
               'gatewayListen': '127.0.0.1:8443', 'systemTrustChainAndHostnameVerified': True,
               'healthChecks': health, 'dnsChanged': False, 'publicWebListenerEnabled': False,
               'productionDataCopied': False, 'renewalConfigured': False}
    path = CONTROL / 'public-certificate-receipt.json'
    path.write_text(json.dumps(receipt, indent=2) + '\n')
    path.chmod(0o600)
    print(json.dumps(receipt))


if __name__ == '__main__':
    main()
