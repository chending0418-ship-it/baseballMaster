#!/usr/bin/env python3
"""Resume encrypted precopy chunks only inside an explicitly approved SH batch."""
import hashlib
import json
import os
from pathlib import Path
import re
import sys

from authorized_precopy import guard, SH, SH_BASE, digest


def main():
    os.umask(0o077)
    guard(SH)
    batch, kind, offset, total, length, chunk_sha, full_sha = sys.argv[1:]
    assert re.fullmatch(r'precopy-[0-9TZ]+-[a-f0-9]{8}', batch)
    assert kind in ['code', 'data', 'configuration']
    offset, total, length = int(offset), int(total), int(length)
    assert 0 <= offset < total <= 2 * 1024**3
    assert 0 < length <= 4 * 1024**2 and offset + length <= total
    assert re.fullmatch(r'[a-f0-9]{64}', chunk_sha) and re.fullmatch(r'[a-f0-9]{64}', full_sha)
    job = SH_BASE / batch
    permission = json.loads((job / 'authorization.json').read_text())
    assert permission['realDataOfflinePrecopyApproved'] is True
    assert permission['trafficActivationApproved'] is False
    folder = job / 'encrypted'
    assert folder.is_dir() and not folder.is_symlink() and folder.stat().st_mode & 0o077 == 0
    target, pending = folder / (kind + '.sealed'), folder / (kind + '.sealed.partial')
    assert not target.exists() and not target.is_symlink() and not pending.is_symlink()
    if pending.exists():
        assert pending.is_file() and pending.stat().st_size == offset
    else:
        assert offset == 0
    body = sys.stdin.buffer.read(length + 1)
    assert len(body) == length and hashlib.sha256(body).hexdigest() == chunk_sha
    with pending.open('ab') as output:
        output.write(body)
        output.flush()
        os.fsync(output.fileno())
    pending.chmod(0o600)
    complete = offset + length == total
    if complete:
        assert pending.stat().st_size == total and digest(pending) == full_sha
        assert not target.exists()
        pending.rename(target)
    print(json.dumps({'receivedBytes': offset + length, 'complete': complete,
                      'ciphertextOnly': True, 'credentialsPrinted': False}))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(json.dumps({'passed': False, 'errorType': type(error).__name__, 'detailsSuppressed': True}), file=sys.stderr)
        sys.exit(1)
