"""Short destructive-in-scratch checks for state preservation and archive boundaries."""
import io
import sqlite3
import tarfile
import tempfile
from pathlib import Path
import unittest
import state_transfer as transfer


class StateTransferTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.connections = []
        for name in ['team', 'lifan']:
            db = sqlite3.connect(self.root / (name + '.sqlite'))
            self.connections.append(db)
            db.executescript('''PRAGMA journal_mode=WAL;
                CREATE TABLE records(id INTEGER PRIMARY KEY AUTOINCREMENT, value TEXT, n INTEGER, r REAL, b BLOB);
                INSERT INTO records(value,n,r,b) VALUES('保留原链接',9223372036854775807,1.125,x'0001ff');
                INSERT INTO records(value,n,r,b) VALUES(NULL,-9223372036854775808,0,NULL);
                INSERT INTO records(id,value) VALUES(50,'sequence must survive');
                DELETE FROM records WHERE id=50;
                CREATE TABLE duplicates(value TEXT);
                INSERT INTO duplicates VALUES('a'),('a'),('b');
                CREATE TABLE keyed(k TEXT PRIMARY KEY,v TEXT) WITHOUT ROWID;
                INSERT INTO keyed VALUES('key','value');''')
            db.commit()
        self.journal = self.root / 'journal'
        self.journal.mkdir()
        (self.journal / 'identity.sealed').write_bytes(b'Synthetic journal fixture')

    def tearDown(self):
        for db in self.connections:
            db.close()
        self.temp.cleanup()

    def capture(self, name='snapshot', **kwargs):
        return transfer.capture(self.root / 'team.sqlite', self.root / 'lifan.sqlite', self.journal,
                                self.root / name, writers_stopped=True, **kwargs)

    def test_wal_large_integers_blobs_rowids_and_sequences_survive(self):
        self.assertGreater((self.root / 'team.sqlite-wal').stat().st_size, 0)
        result = self.capture()
        self.assertEqual(result['databases']['team'], transfer.inspect_database(self.root / 'snapshot/team.sqlite'))
        db = sqlite3.connect(self.root / 'snapshot/team.sqlite')
        try:
            self.assertEqual(db.execute('SELECT n,b FROM records WHERE id=1').fetchone(), (9223372036854775807, b'\x00\x01\xff'))
            self.assertEqual(db.execute("SELECT seq FROM sqlite_sequence WHERE name='records'").fetchone()[0], 50)
        finally:
            db.close()

    def test_quiescence_and_existing_destination_required(self):
        with self.assertRaises(AssertionError):
            transfer.capture(self.root / 'team.sqlite', self.root / 'lifan.sqlite', self.journal, self.root / 'denied')
        self.assertFalse((self.root / 'denied').exists())
        self.capture()
        with self.assertRaises(AssertionError):
            self.capture()

    def test_container_round_trip_and_no_overwrite(self):
        self.capture()
        transfer.pack(self.root / 'snapshot', self.root / 'data.tar')
        transfer.unpack(self.root / 'data.tar', self.root / 'candidate')
        self.assertEqual(transfer.verify(self.root / 'snapshot'), transfer.verify(self.root / 'candidate'))
        with self.assertRaises(AssertionError):
            transfer.unpack(self.root / 'data.tar', self.root / 'candidate')

    def test_online_precopy_is_explicitly_ineligible_for_activation(self):
        manifest = transfer.capture(self.root / 'team.sqlite', self.root / 'lifan.sqlite', self.journal,
                                    self.root / 'precopy', online_precopy_only=True)
        self.assertFalse(manifest['activationEligible'])
        self.assertFalse(manifest['writersStoppedConfirmed'])
        self.assertTrue(manifest['onlinePrecopyOnly'])
        transfer.pack(self.root / 'precopy', self.root / 'precopy.tar')
        received = transfer.unpack(self.root / 'precopy.tar', self.root / 'precopy-received')
        self.assertFalse(received['activationEligible'])

    def test_changed_database_or_extra_private_file_is_rejected(self):
        self.capture()
        db = sqlite3.connect(self.root / 'snapshot/team.sqlite')
        db.execute("UPDATE records SET value='changed' WHERE id=1")
        db.commit()
        db.close()
        with self.assertRaises(AssertionError):
            transfer.verify(self.root / 'snapshot')
        self.capture('second')
        (self.root / 'second/credentials.json').write_text('not permitted in the data container')
        with self.assertRaises(AssertionError):
            transfer.pack(self.root / 'second', self.root / 'bad.tar')

    def test_traversal_and_symlink_members_rejected_before_extract(self):
        for kind in ['traversal', 'symlink']:
            archive = self.root / (kind + '.tar')
            with tarfile.open(archive, 'w') as tar:
                for name in ['manifest.json', 'team.sqlite', 'lifan.sqlite', 'journal/identity.sealed']:
                    info = tarfile.TarInfo(name)
                    info.size = 2
                    tar.addfile(info, io.BytesIO(b'{}'))
                info = tarfile.TarInfo('../outside' if kind == 'traversal' else 'journal/' + 'a' * 64 + '.sealed')
                if kind == 'symlink':
                    info.type = tarfile.SYMTYPE
                    info.linkname = '/etc/passwd'
                tar.addfile(info)
            with self.assertRaises(AssertionError):
                transfer.unpack(archive, self.root / kind)
            self.assertFalse((self.root / kind).exists())
            self.assertFalse((self.root / 'outside').exists())

    def test_foreign_key_damage_and_unfinished_journal_rejected(self):
        db = self.connections[0]
        db.executescript('CREATE TABLE parent(id PRIMARY KEY); CREATE TABLE child(id REFERENCES parent(id)); INSERT INTO child VALUES(1);')
        with self.assertRaises(AssertionError):
            transfer.inspect_database(self.root / 'team.sqlite')
        (self.journal / 'unfinished.partial').write_text('pending')
        with self.assertRaises(AssertionError):
            transfer.journal_inventory(self.journal)


if __name__ == '__main__':
    unittest.main()
