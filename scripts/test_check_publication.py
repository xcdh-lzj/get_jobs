"""Publication checks cover staged bytes, ignored runtime paths and masked reporting."""
from pathlib import Path
import os
import shutil
import sqlite3
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace

import check_publication as check


class PublicationCheckTest(unittest.TestCase):
    def test_generic_and_pre_migration_databases_do_not_break_public_source_checks(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            defaults = root / 'distribution/defaults'
            shutil.copytree(Path(__file__).resolve().parents[1] / 'distribution/defaults', defaults)
            bundled = root / 'src/main/resources/defaults'
            bundled.mkdir(parents=True)
            shutil.copyfile(defaults / 'page-config.json', bundled / 'page-config.json')
            check.share.create_database(root / 'db/getjobs.db', defaults)
            self.assertEqual(set(), check.share.private_needles(root))
            with patch('check_publication.getpass.getuser', return_value='Root'), patch('check_publication.subprocess.run', return_value=SimpleNamespace(returncode=1, stdout='')):
                self.assertEqual(set(), check.private_identities(root))
                with sqlite3.connect(root / 'db/getjobs.db') as db:
                    db.execute('CREATE TABLE boss_accounts (name TEXT)')
                    db.executemany('INSERT INTO boss_accounts VALUES (?)', [('默认账号',), ('synthetic-private-account',)])
                self.assertEqual({b'synthetic-private-account'}, check.private_identities(root))

    def test_private_paths_include_local_files_backups_and_nested_dependency_caches(self):
        for name in ['.env', '.env.local', 'config/application-local.yaml', 'config/page-config.local.json',
                     'config/gradle-local.properties', 'db/getjobs.db', 'data/resume.pdf',
                     'cookies/boss.json', 'browser-accounts/boss/1/Preferences',
                     'front/node_modules/package/config.json', 'backup.sqlite', 'target/app.log']:
            self.assertTrue(check.private_path(name), name)
        for name in ['src/main/resources/application.yaml', 'src/main/resources/defaults/page-config.json']:
            self.assertFalse(check.private_path(name), name)

    def test_credentials_contacts_and_private_identity_are_blocked_without_exposing_matches(self):
        samples = [b'sk-' + b'A'*32, b'ghp_' + b'B'*32, b'18' + b'900000000',
                   b'person@' + b'private.test', b'-----BEGIN ' + b'PRIVATE KEY-----']
        for sample in samples:
            self.assertTrue(check.inspect(sample, 'fixture.txt', set(), set()))
        value = b'synthetic-private-value'
        findings = check.inspect(value, 'fixture.txt', {value}, {value})
        self.assertNotIn(value.decode(), str(findings))

    def test_staged_secrets_cannot_hide_behind_a_clean_working_file(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            subprocess.run(['git', 'init', '--quiet', temp], check=True)
            path = root / 'fixture.txt'
            secret = b'sk-' + b'A'*32
            path.write_bytes(secret)
            subprocess.run(['git', 'add', 'fixture.txt'], cwd=root, check=True)
            path.write_text('clean working file')
            staged = check.staged_files(root)
            self.assertEqual(secret, staged['fixture.txt'])
            self.assertTrue(check.inspect(staged['fixture.txt'], 'fixture.txt', set(), set()))

    def test_push_guard_rejects_old_history_and_merges_that_would_reintroduce_it(self):
        hook = Path(__file__).resolve().parents[1] / '.githooks/pre-push'
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            env = dict(os.environ, GIT_AUTHOR_NAME='Synthetic Contributor', GIT_COMMITTER_NAME='Synthetic Contributor',
                       GIT_AUTHOR_EMAIL='contributor@example.invalid', GIT_COMMITTER_EMAIL='contributor@example.invalid')
            def git(*args):
                return subprocess.check_output(['git', *args], cwd=root, env=env, text=True).strip()
            git('init', '--quiet')
            (root / 'fixture.txt').write_text('synthetic public content')
            git('add', 'fixture.txt')
            tree = git('write-tree')
            public_root = git('commit-tree', tree, '-m', 'public root')
            private_root = git('commit-tree', tree, '-m', 'private root')
            merged = git('commit-tree', tree, '-p', public_root, '-p', private_root, '-m', 'mixed history')
            git('config', 'getjobs.publicRoot', public_root)
            for commit, expected in [(public_root, 0), (private_root, 1), (merged, 1)]:
                line = f'refs/heads/main {commit} refs/heads/main {"0"*40}\n'
                result = subprocess.run(['sh', str(hook)], cwd=root, input=line, capture_output=True, text=True)
                self.assertEqual(expected, result.returncode)


if __name__ == '__main__':
    unittest.main()
