#!/usr/bin/python3
"""Validate and deploy the Shanghai Certbot lineage; preserve the previous pair."""
import datetime
import fcntl
import json
import os
from pathlib import Path
import shutil
import socket
import ssl
import subprocess
import tempfile
import time

LINEAGE = Path('/etc/letsencrypt/live/baseballmaster.cc')
TARGET = Path('/etc/baseballmaster-production/tls')
CONF = '/etc/baseballmaster-production/nginx.conf'


def run(*args):
    return subprocess.check_output(args, stderr=subprocess.STDOUT, timeout=30)


def served_certificate():
    with socket.create_connection(('127.0.0.1', 443), timeout=5) as sock:
        with ssl.create_default_context().wrap_socket(sock, server_hostname='baseballmaster.cc') as conn:
            return conn.getpeercert(binary_form=True)


def main():
    assert os.geteuid() == 0
    assert Path(os.environ['RENEWED_LINEAGE']) == LINEAGE
    state = Path('/var/lib/baseballmaster-acme')
    state.mkdir(mode=0o700, exist_ok=True)
    with (state / 'deploy.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        cert = LINEAGE / 'cert.pem'
        run('openssl', 'x509', '-in', str(cert), '-checkend', '604800', '-noout')
        run('openssl', 'x509', '-in', str(cert), '-checkhost', 'baseballmaster.cc', '-noout')
        run('openssl', 'x509', '-in', str(cert), '-checkhost', 'www.baseballmaster.cc', '-noout')
        run('openssl', 'verify', '-CApath', '/etc/ssl/certs', '-untrusted',
            str(LINEAGE / 'chain.pem'), str(cert))
        public = run('openssl', 'x509', '-in', str(cert), '-pubkey', '-noout')
        assert public == run('openssl', 'pkey', '-in', str(LINEAGE / 'privkey.pem'), '-pubout')
        expected = ssl.PEM_cert_to_DER_cert(cert.read_text())
        try:
            current = served_certificate()
        except (OSError, ssl.SSLError):
            current = None  # An expired/unavailable old certificate must not block repair.
        if current == expected and all(
                (TARGET / name).read_bytes() == (LINEAGE / name).read_bytes()
                for name in ('fullchain.pem', 'privkey.pem')):
            print('Current certificate is already deployed and HTTPS verified')
            return
        stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
        backup = Path('/var/backups/baseballmaster-tls') / stamp
        backup.mkdir(parents=True, mode=0o700)
        names = ('fullchain.pem', 'privkey.pem')
        for name in names:
            shutil.copy2(TARGET / name, backup / name)
        try:
            for name in names:
                fd, tmp = tempfile.mkstemp(prefix='.renew-', dir=TARGET)
                with os.fdopen(fd, 'wb') as out:
                    out.write((LINEAGE / name).read_bytes())
                    out.flush()
                    os.fsync(out.fileno())
                os.replace(tmp, TARGET / name)
            run('/usr/sbin/nginx', '-t', '-c', CONF)
            run('/usr/sbin/nginx', '-s', 'reload', '-c', CONF)
            for attempt in range(10):
                actual = served_certificate()
                if actual == expected:
                    break
                time.sleep(1)
            assert actual == expected, 'Nginx did not serve the renewed certificate'
            run('curl', '--fail', '--silent', '--show-error', '--max-time', '15',
                '--resolve', 'baseballmaster.cc:443:127.0.0.1', 'https://baseballmaster.cc/')
        except BaseException:
            for name in names:
                shutil.copy2(backup / name, TARGET / name)
            run('/usr/sbin/nginx', '-t', '-c', CONF)
            run('/usr/sbin/nginx', '-s', 'reload', '-c', CONF)
            raise
        receipt = {'deployedAt': stamp, 'backup': str(backup), 'verifiedHTTPS': True,
                   'certificate': run('openssl', 'x509', '-in', str(cert), '-noout',
                                      '-issuer', '-serial', '-dates').decode()}
        (state / 'last-deployment.json').write_text(json.dumps(receipt, indent=2) + '\n')
        print(json.dumps(receipt))


if __name__ == '__main__':
    main()
