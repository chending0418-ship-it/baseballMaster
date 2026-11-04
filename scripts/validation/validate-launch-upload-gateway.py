"""Exercise the Nginx upload boundary with a synthetic loopback upstream.

Read nginx-v3.conf from stdin. Uses temporary high ports, no production data or
configuration, and stops both processes before returning a JSON receipt.
"""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import json
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request


class SyntheticAdmin(BaseHTTPRequestHandler):
    def do_POST(self):
        self.rfile.read(int(self.headers.get('Content-Length', 0)))
        self.send_response(401)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        self.wfile.write(b'{"error":"synthetic login required"}')

    def log_message(self, *_):
        pass


def main():
    routes = sys.stdin.read()
    nginx = shutil.which('nginx') or '/usr/sbin/nginx'
    upstream = ThreadingHTTPServer(('127.0.0.1', 0), SyntheticAdmin)
    thread = threading.Thread(target=upstream.serve_forever, daemon=True)
    thread.start()
    worker = None
    try:
        with tempfile.TemporaryDirectory(prefix='bm-launch-gateway-') as folder:
            root = Path(folder)
            with socket.socket() as port:
                port.bind(('127.0.0.1', 0))
                gateway_port = port.getsockname()[1]
            routes = routes.replace('127.0.0.1:8788', '127.0.0.1:' + str(upstream.server_port))
            config = root / 'nginx.conf'
            config.write_text(
                f'pid {root}/nginx.pid; error_log /dev/null crit;\n'
                'events {}\nhttp { access_log off;\n'
                f'client_body_temp_path {root}/body; proxy_temp_path {root}/proxy;\n'
                f'server {{ listen 127.0.0.1:{gateway_port};\n' + routes + '\n}\n}\n')
            subprocess.run([nginx, '-t', '-c', str(config)], check=True, capture_output=True)
            worker = subprocess.Popen([nginx, '-c', str(config), '-g', 'daemon off;'],
                                      stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            for _ in range(100):
                try:
                    with socket.create_connection(('127.0.0.1', gateway_port), timeout=.1):
                        break
                except OSError:
                    assert worker.poll() is None, 'Isolated gateway exited'
                    time.sleep(.02)
            else:
                raise RuntimeError('Isolated gateway did not start')
            checks = []
            for path, size, expected in [
                ('launch-screen/image', 4097, 401),
                ('launch-screen/image', 3 * 1024 * 1024, 401),
                ('launch-screen/image', 3 * 1024 * 1024 + 1, 413),
                ('login', 4096, 401),
                ('login', 4097, 413),
            ]:
                request = urllib.request.Request(
                    f'http://127.0.0.1:{gateway_port}/admin/api/{path}',
                    data=b'0' * size, headers={'Content-Type': 'image/png'})
                try:
                    response = urllib.request.urlopen(request, timeout=10)
                except urllib.error.HTTPError as error:
                    response = error
                with response:
                    assert response.status == expected, (path, size, response.status)
                    if expected == 401:
                        assert json.load(response)['error'] == 'synthetic login required'
                    checks.append({'path': path, 'bytes': size, 'status': response.status})
            print(json.dumps({'passed': True, 'syntheticOnly': True,
                              'productionConfigurationChanged': False, 'checks': checks}, indent=2))
    finally:
        if worker is not None:
            worker.terminate()
            worker.wait(timeout=10)
        upstream.shutdown()
        upstream.server_close()
        thread.join(timeout=5)


if __name__ == '__main__':
    main()
