#!/usr/bin/env python3
"""Check staged source before publication. Reports never contain matched private values."""
from __future__ import annotations

import getpass
import hashlib
import json
from pathlib import Path
import re
import sqlite3
import subprocess

import package_share as share

PRIVATE_DIRECTORIES = {'.git', 'db', 'data', 'cookies', 'browser-data', 'browser-accounts',
                       'target', 'node_modules', '.next', 'out', 'build', 'logs', 'screenshots', 'recordings'}
PATTERNS = {
    'access-token': rb'\b(?:sk-[A-Za-z0-9_-]{20,}|gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})\b',
    'private-key': rb'-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----',
    'webhook-secret': rb'webhook/send\?key=(?!your_key_here\b)[A-Za-z0-9-]{16,}',
    'phone-number': rb'(?<![A-Za-z0-9])1[3-9][0-9]{9}(?![A-Za-z0-9])',
}
EMAILS = re.compile(rb'[A-Za-z0-9_.+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}')
# Public upstream licensing contact and dependency author. These are not local identities.
PUBLIC_EMAILS = {b'super_xiang@126.com', b'i@izs.me'}


def private_path(name: str) -> bool:
    path = Path(name)
    return (bool(PRIVATE_DIRECTORIES.intersection(path.parts))
            or path.name.startswith('.env')
            or (path.parts[0] == 'config' and ('-local.' in path.name or '.local.' in path.name))
            or path.suffix.lower() in {'.db', '.sqlite', '.sqlite3', '.bak', '.log', '.pem', '.key', '.p12', '.pfx'})


def private_identities(root: Path) -> set[bytes]:
    values = {getpass.getuser()}
    for key in ('user.name', 'user.email'):
        result = subprocess.run(['git', 'config', '--get', key], cwd=root, capture_output=True, text=True)
        if result.returncode == 0 and result.stdout.strip() not in {'Get Jobs Contributors', 'noreply@users.noreply.github.com'}:
            values.add(result.stdout.strip())
    database = root / 'db/getjobs.db'
    if database.is_file():
        with sqlite3.connect(f'{database.resolve().as_uri()}?mode=ro', uri=True) as db:
            values.update(str(row[0]) for row in db.execute('SELECT name FROM boss_accounts') if row[0])
    return {value.encode() for value in values if len(value) >= 2}


def public_email(value: bytes, name: str) -> bool:
    domain = value.rsplit(b'@', 1)[-1].lower()
    return (value.lower() in PUBLIC_EMAILS or domain in {
        b'example.com', b'example.org', b'example.net', b'users.noreply.github.com',
    } or domain.endswith((b'.example', b'.invalid'))
            or (name == 'front/tests/boss-history.test.mjs' and value == b'pass' + b'@www.zhipin.com'))


def inspect(data: bytes, name: str, needles: set[bytes], identities: set[bytes]) -> list[dict[str, str]]:
    findings: list[dict[str, str]] = []
    if private_path(name):
        findings.append({'path': name, 'rule': 'private-runtime-file'})
    try:
        share.scan_bytes(data, name, needles)
    except RuntimeError:
        findings.append({'path': name, 'rule': 'known-private-value-or-local-path'})
    for identity in identities:
        if re.search(rb'(?<![A-Za-z0-9_])' + re.escape(identity) + rb'(?![A-Za-z0-9_])', data, re.I):
            findings.append({'path': name, 'rule': 'local-identity'})
            break
    for rule, pattern in PATTERNS.items():
        if re.search(pattern, data):
            findings.append({'path': name, 'rule': rule})
    if any(not public_email(match.group(), name) for match in EMAILS.finditer(data)):
        findings.append({'path': name, 'rule': 'unreviewed-email'})
    return findings


def staged_files(root: Path) -> dict[str, bytes]:
    records = subprocess.check_output(['git', 'ls-files', '--stage', '-z'], cwd=root).split(b'\0')
    files: dict[str, bytes] = {}
    for record in records:
        if not record:
            continue
        metadata, name = record.split(b'\t', 1)
        mode, oid, stage = metadata.split()
        share.require(stage == b'0' and mode in {b'100644', b'100755'}, 'Resolve conflicts, symlinks or submodules before publishing')
        files[name.decode()] = subprocess.check_output(['git', 'cat-file', 'blob', oid.decode()], cwd=root)
    return files


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    files = staged_files(root)
    needles, identities = share.private_needles(root), private_identities(root)
    approved = json.loads((root / 'scripts/public-assets.sha256.json').read_text())
    findings: list[dict[str, str]] = []
    for name, data in files.items():
        try:
            data.decode('utf-8')
        except UnicodeDecodeError:
            if hashlib.sha256(data).hexdigest() != approved.get(name):
                findings.append({'path': name, 'rule': 'unreviewed-binary'})
            continue
        findings.extend(inspect(data, name, needles, identities))
    if b'https://github.com/loks666/get_jobs' not in files.get('README.md', b''):
        findings.append({'path': 'README.md', 'rule': 'missing-upstream-attribution'})
    report = {'result': 'passed' if not findings else 'failed', 'files': len(files),
              'approvedUpstreamBinaries': len(approved), 'findings': findings,
              'manifest': {name: hashlib.sha256(data).hexdigest() for name, data in files.items()}}
    share.write_json(root / 'target/privacy-audit.json', report)
    print(json.dumps({key: value for key, value in report.items() if key != 'manifest'}, ensure_ascii=False))
    raise SystemExit(bool(findings))


if __name__ == '__main__':
    main()
