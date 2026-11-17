"""Privacy and database checks for the release exporter; uses only synthetic data."""
import importlib.util
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('package_share', Path(__file__).with_name('package_share.py'))
share = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(share)
DEFAULTS = Path(__file__).resolve().parents[1] / 'distribution/defaults'


class ShareExportTest(unittest.TestCase):
    def test_fresh_database_has_only_public_reference_rows_and_blank_credentials(self):
        with tempfile.TemporaryDirectory() as temp:
            db = Path(temp) / 'db/getjobs.db'
            share.create_database(db, DEFAULTS)
            share.validate_database(db, DEFAULTS)
            with sqlite3.connect(db) as connection:
                self.assertEqual(0, connection.execute('SELECT count(*) FROM boss_data').fetchone()[0])
                self.assertEqual(0, connection.execute('SELECT count(*) FROM cookie').fetchone()[0])
                self.assertEqual(578, connection.execute('SELECT count(*) FROM boss_option').fetchone()[0])
                self.assertEqual('', connection.execute("SELECT config_value FROM config WHERE config_key='API_KEY'").fetchone()[0])
            with self.assertRaisesRegex(RuntimeError, 'overwrite'):
                share.create_database(db, DEFAULTS)

    def test_audit_rejects_even_one_nonblank_service_secret(self):
        with tempfile.TemporaryDirectory() as temp:
            db = Path(temp) / 'getjobs.db'
            share.create_database(db, DEFAULTS)
            with sqlite3.connect(db) as connection:
                connection.execute("UPDATE config SET config_value='synthetic-not-a-key' WHERE config_key='API_KEY'")
            with self.assertRaisesRegex(RuntimeError, 'credentials'):
                share.validate_database(db, DEFAULTS)

    def test_source_allowlist_rejects_private_state_and_unapproved_resources(self):
        for name in ['.env', '.git/config', 'db/getjobs.db', 'db/backup.db', 'browser-data/Default/Cookies',
                     'browser-accounts/1/Preferences', 'cookies/boss.json', 'data/boss/resume/1.png',
                     'target/logs/get-jobs.log', 'src/main/resources/resume.png',
                     'src/main/resources/images/private.png', 'doc/imgs/private.png',
                     'distribution/private.json', 'front/credentials.env']:
            self.assertFalse(share.allowed_source(name), name)
        for name in ['src/main/java/com/getjobs/Main.java', 'front/app/boss/page.tsx',
                     'gradle/wrapper/gradle-wrapper.jar', 'distribution/defaults/schema.sql']:
            self.assertTrue(share.allowed_source(name), name)

    def test_private_value_detection_suppresses_the_value_in_error(self):
        private = b'synthetic-private-secret'
        with self.assertRaises(RuntimeError) as error:
            share.scan_bytes(b'prefix ' + private, 'config.json', {private})
        self.assertNotIn(private.decode(), str(error.exception))

    def test_local_user_paths_are_rejected_in_plain_text_and_json(self):
        paths = [b'/'.join([b'', b'Users', b'example', b'private']),
                 b'\\'.join([b'C:', b'Users', b'example', b'private']),
                 b'\\\\'.join([b'C:', b'Users', b'example', b'private'])]
        for data in paths:
            with self.assertRaisesRegex(RuntimeError, 'Local user path'):
                share.scan_bytes(data, 'synthetic.json', set())

    def test_generic_defaults_contain_no_personal_profile_blacklist_or_resume_flag(self):
        defaults = json.loads((DEFAULTS / 'page-config.json').read_text(encoding='utf-8'))
        self.assertEqual('', defaults['ai'][0]['introduce'])
        self.assertEqual(5, defaults['ai'][0]['prompt'].count('%s'))
        self.assertEqual([], defaults['boss_blacklist'])
        boss = defaults['boss_config'][0]
        self.assertEqual('', boss['keywords'])
        self.assertEqual(0, boss['send_img_resume'])
        self.assertEqual(0, boss['enable_ai'])


if __name__ == '__main__':
    unittest.main()
