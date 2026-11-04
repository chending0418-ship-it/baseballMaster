#!/usr/bin/env python3
"""State-preserving SQLite snapshots and strictly scoped data-only containers.

No application modules are imported, no recovery fences are applied, and no
service, DNS, credential, or existing destination is changed by this module.
"""
import argparse
import base64
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sqlite3
import struct
import sys
import tarfile
import uuid


def digest_file(path):
    digest = hashlib.sha256()
    with open(path, 'rb') as file:
        for chunk in iter(lambda: file.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def encoded(value):
    if value is None:
        return ['null']
    if isinstance(value, int):
        return ['integer', str(value)]
    if isinstance(value, float):
        return ['real', struct.pack('>d', value).hex()]
    if isinstance(value, bytes):
        return ['blob', base64.b64encode(value).decode()]
    if isinstance(value, str):
        return ['text', value]
    raise ValueError('Unsupported SQLite value')


def digest_value(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=True, separators=(',', ':')).encode()).hexdigest()


def readonly(path):
    path = Path(path)
    assert path.is_file() and not path.is_symlink(), 'Expected regular SQLite file'
    db = sqlite3.connect(path.resolve().as_uri() + '?mode=ro', uri=True, timeout=10)
    db.execute('PRAGMA query_only=ON')
    return db


def inspect_database(path):
    db = readonly(path)
    try:
        db.execute('BEGIN')
        assert db.execute('PRAGMA integrity_check').fetchall() == [('ok',)], 'SQLite integrity check failed'
        assert not db.execute('PRAGMA foreign_key_check').fetchall(), 'SQLite foreign key check failed'
        schema = db.execute('SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY type,name,tbl_name').fetchall()
        tables = {}
        for name, sql in db.execute("SELECT name,sql FROM sqlite_master WHERE type='table' ORDER BY name").fetchall():
            quoted = '"' + name.replace('"', '""') + '"'
            columns = db.execute('PRAGMA table_xinfo(' + quoted + ')').fetchall()
            names = {row[1].lower() for row in columns}
            rowid = next((alias for alias in ['_rowid_', 'rowid', 'oid'] if alias not in names), None)
            if sql and 'WITHOUT ROWID' in sql.upper():
                rowid = None
            selected = (rowid + ',' if rowid else '') + '*'
            row_hashes = []
            for row in db.execute('SELECT ' + selected + ' FROM ' + quoted):
                row_hashes.append(digest_value([encoded(value) for value in row]))
            row_hashes.sort()
            tables[name] = {'rows': len(row_hashes), 'sha256': digest_value(row_hashes),
                            'columnsSHA256': digest_value(columns), 'rowidIncluded': bool(rowid)}
        properties = {name: db.execute('PRAGMA ' + name).fetchone()[0]
                      for name in ['user_version', 'application_id', 'encoding', 'page_size']}
        summary = {'schemaSHA256': digest_value(schema), 'properties': properties, 'tables': tables}
        summary['logicalSHA256'] = digest_value(summary)
        return summary
    finally:
        db.close()


def private_json(path, value):
    with open(path, 'x', opener=lambda name, flags: os.open(name, flags, 0o600)) as file:
        json.dump(value, file, ensure_ascii=True, indent=2)
        file.write('\n')
        file.flush()
        os.fsync(file.fileno())


def snapshot_database(source, destination, *, require_source_stable=True):
    source, destination = Path(source), Path(destination)
    assert not destination.exists() and not destination.is_symlink(), 'Destination already exists'
    assert all(not Path(str(destination) + suffix).exists() for suffix in ['-wal', '-shm'])
    before = inspect_database(source) if require_source_stable else None
    src = readonly(source)
    target = sqlite3.connect(destination)
    os.chmod(destination, 0o600)
    try:
        src.backup(target, pages=256, sleep=0.01)
        target.execute('PRAGMA journal_mode=DELETE')
    finally:
        target.close()
        src.close()
    snapshot = inspect_database(destination)
    if require_source_stable:
        after = inspect_database(source)
        assert before == after == snapshot, 'Source changed or snapshot state differs'
    return snapshot


def journal_inventory(directory):
    directory = Path(directory)
    assert directory.is_dir() and not directory.is_symlink()
    files = {}
    for path in sorted(directory.iterdir()):
        assert re.fullmatch(r'(identity|[a-f0-9]{64})\.sealed', path.name), 'Unexpected journal file; investigate before copying'
        assert path.is_file() and not path.is_symlink(), 'Journal links are forbidden'
        files[path.name] = digest_file(path)
    assert 'identity.sealed' in files, 'Journal identity missing'
    return files


def capture(team, lifan, journal, destination, *, writers_stopped=False, online_precopy_only=False):
    assert writers_stopped is True or online_precopy_only is True, 'Stop writers or explicitly request an offline-only precopy'
    assert not (writers_stopped and online_precopy_only), 'Choose final capture or precopy, never both'
    destination = Path(destination)
    assert not destination.exists() and not destination.is_symlink(), 'Refusing to replace a candidate'
    destination.mkdir(mode=0o700)
    initial_journal = journal_inventory(journal)
    states = {'team': snapshot_database(team, destination / 'team.sqlite', require_source_stable=writers_stopped),
              'lifan': snapshot_database(lifan, destination / 'lifan.sqlite', require_source_stable=writers_stopped)}
    (destination / 'journal').mkdir(mode=0o700)
    for name, digest in initial_journal.items():
        target = destination / 'journal' / name
        with open(Path(journal) / name, 'rb') as source, open(target, 'xb', opener=lambda p, flags: os.open(p, flags, 0o600)) as output:
            shutil.copyfileobj(source, output)
        assert digest_file(target) == digest, 'Journal changed while copying'
    current_journal = journal_inventory(journal)
    assert all(current_journal.get(name) == digest for name, digest in initial_journal.items()), 'Existing journal records changed'
    if writers_stopped:
        assert current_journal == initial_journal, 'Journal changed while capturing'
        assert inspect_database(team) == states['team'] and inspect_database(lifan) == states['lifan'], 'A writer is still active'
    files = {name: digest_file(destination / name) for name in ['team.sqlite', 'lifan.sqlite']}
    files.update({'journal/' + name: digest for name, digest in initial_journal.items()})
    manifest = {'schemaVersion': 1, 'purpose': 'state-preserving-migration',
                'capturedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                'writersStoppedConfirmed': writers_stopped, 'activationEligible': writers_stopped,
                'onlinePrecopyOnly': online_precopy_only,
                'journalFilesAddedDuringPrecopy': len(set(current_journal) - set(initial_journal)),
                'databases': states, 'files': files,
                'recoveryFencesApplied': False, 'containsTemporaryLiveDatabase': False,
                'containsCredentialFiles': False}
    private_json(destination / 'manifest.json', manifest)
    return manifest


def verify(directory):
    directory = Path(directory)
    assert directory.is_dir() and not directory.is_symlink()
    manifest = json.loads((directory / 'manifest.json').read_text())
    assert manifest['schemaVersion'] == 1 and manifest['purpose'] == 'state-preserving-migration'
    assert manifest['recoveryFencesApplied'] is False and manifest['containsCredentialFiles'] is False
    assert manifest['activationEligible'] is manifest['writersStoppedConfirmed']
    assert manifest['onlinePrecopyOnly'] is not manifest['activationEligible']
    expected = set(manifest['files']) | {'manifest.json'}
    actual = {str(p.relative_to(directory)) for p in directory.rglob('*') if p.is_file()}
    assert actual == expected, 'Unexpected or missing candidate files'
    for name, digest in manifest['files'].items():
        assert allowed_name(name), 'Forbidden candidate path'
        path = directory / name
        assert path.is_file() and not path.is_symlink() and digest_file(path) == digest
    for role in ['team', 'lifan']:
        assert inspect_database(directory / (role + '.sqlite')) == manifest['databases'][role]
    return manifest


def allowed_name(name):
    return bool(re.fullmatch(r'(manifest\.json|team\.sqlite|lifan\.sqlite|journal/(identity|[a-f0-9]{64})\.sealed)', name))


def pack(directory, archive):
    directory, archive = Path(directory), Path(archive)
    manifest = verify(directory)
    assert not archive.exists() and not archive.is_symlink(), 'Container already exists'
    fd = os.open(archive, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'wb') as output, tarfile.open(fileobj=output, mode='w', format=tarfile.PAX_FORMAT) as tar:
        for name in ['manifest.json', *sorted(manifest['files'])]:
            path = directory / name
            info = tar.gettarinfo(str(path), arcname=name)
            info.uid = info.gid = info.mtime = 0
            info.uname = info.gname = ''
            info.mode = 0o600
            with open(path, 'rb') as file:
                tar.addfile(info, file)


def unpack(archive, destination):
    archive, destination = Path(archive), Path(destination)
    assert not destination.exists() and not destination.is_symlink(), 'Refusing to replace destination'
    pending = destination.parent / ('.' + destination.name + '.unverified-' + str(uuid.uuid4()))
    pending.mkdir(mode=0o700)
    try:
        with tarfile.open(archive, 'r:') as tar:
            members = tar.getmembers()
            names = [m.name for m in members]
            assert len(names) == len(set(names)) and len(names) <= 100000
            assert {'manifest.json', 'team.sqlite', 'lifan.sqlite', 'journal/identity.sealed'} <= set(names)
            assert all(allowed_name(m.name) and m.isfile() and not m.linkname for m in members), 'Unsafe container member'
            assert all(0 <= m.size <= 2 * 1024**3 for m in members)
            assert tar.getmember('manifest.json').size <= 1024 * 1024
            for member in members:
                path = pending / member.name
                path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
                with tar.extractfile(member) as source, open(path, 'xb', opener=lambda p, flags: os.open(p, flags, 0o600)) as output:
                    shutil.copyfileobj(source, output)
        manifest = verify(pending)
        assert not destination.exists()
        pending.rename(destination)
        return manifest
    finally:
        if pending.exists():
            shutil.rmtree(pending)  # Only the private scratch directory created above.


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='action', required=True)
    p = sub.add_parser('capture')
    for name in ['team', 'lifan', 'journal', 'destination']:
        p.add_argument('--' + name, required=True)
    p.add_argument('--writers-stopped', action='store_true')
    p.add_argument('--online-precopy-only', action='store_true')
    for action in ['inspect', 'verify']:
        p = sub.add_parser(action)
        p.add_argument('path')
    for action in ['pack', 'unpack']:
        p = sub.add_parser(action)
        p.add_argument('source')
        p.add_argument('destination')
    args = parser.parse_args()
    if args.action == 'capture':
        result = capture(args.team, args.lifan, args.journal, args.destination,
                         writers_stopped=args.writers_stopped, online_precopy_only=args.online_precopy_only)
    elif args.action == 'inspect':
        result = inspect_database(args.path)
    elif args.action == 'verify':
        result = verify(args.path)
    elif args.action == 'pack':
        pack(args.source, args.destination)
        result = {'packed': True, 'sha256': digest_file(args.destination)}
    else:
        result = unpack(args.source, args.destination)
    # Row contents, credentials and bearer codes are never included in output.
    print(json.dumps(result, separators=(',', ':')))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(json.dumps({'passed': False, 'errorType': type(error).__name__, 'detailsSuppressed': True}), file=sys.stderr)
        sys.exit(1)
