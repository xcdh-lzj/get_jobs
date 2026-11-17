#!/usr/bin/env python3
"""Prepare, verify and archive an isolated Windows release, without copying local state.

prepare -> build/test the exported source -> finish. Output stays under target/share.
Only public reference data is used to create SQLite files; no local database is cloned.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import sqlite3
import subprocess
import zipfile

ROOT_FILES = {
    '.gitattributes', '.gitignore', 'LICENSE', 'CLA.md', 'build.gradle.kts',
    'settings.gradle', 'gradle.properties', 'gradlew', 'gradlew.bat', 'start-local.ps1',
}
RESOURCE_FILES = {'application.yaml', 'banner.txt', 'defaults/page-config.json'}
DIST_FILES = {'defaults/schema.sql', 'defaults/options.json', 'defaults/page-config.json',
              'start.bat', 'start-windows.ps1', 'README-Windows.md', 'PACKAGING.md'}
OPTION_TABLES = {'boss_option', 'liepin_option', 'job51_option', 'zhilian_option'}
CONFIG_DEFAULTS = {'BASE_URL': '', 'API_KEY': '', 'MODEL': '', 'HOOK_URL': '', 'BOT_IS_SEND': '0'}
SYNTHETIC_CASES = [
    {'bossInfo': {'activeTimeDesc': '刚刚活跃', 'bossOnline': True}, 'observedCount': 1},
    {'bossInfo': {'activeTimeDesc': '刚刚活跃', 'bossOnline': False}, 'observedCount': 1},
    {'bossInfo': {'activeTimeDesc': '3日内活跃', 'bossOnline': False}, 'observedCount': 1},
    {'bossInfo': {'activeTimeDesc': '本周活跃', 'bossOnline': False}, 'observedCount': 1},
    {'bossInfo': {'activeTimeDesc': None, 'bossOnline': False}, 'observedCount': 1},
    {'bossInfo': {'activeTimeDesc': '本月活跃', 'bossOnline': False}, 'observedCount': 1},
]


def require(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(message)


def sha256(path: Path) -> str:
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def copy_file(source: Path, target: Path) -> None:
    require(source.is_file() and not source.is_symlink(), f'Unsafe or missing input: {source.name}')
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, target)
    target.chmod(0o755 if source.name == 'gradlew' else 0o644)


def write_json(path: Path, data: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def create_database(target: Path, defaults: Path) -> None:
    require(not target.exists(), 'Refusing to overwrite a database')
    target.parent.mkdir(parents=True, exist_ok=True)
    options = json.loads((defaults / 'options.json').read_text(encoding='utf-8'))
    require(set(options) == OPTION_TABLES, 'Unexpected reference table')
    with sqlite3.connect(target) as db:
        db.executescript((defaults / 'schema.sql').read_text(encoding='utf-8'))
        for table, rows in options.items():
            for row in rows:
                require(set(row) == {'type', 'name', 'code', 'sort_order'}, 'Unexpected reference fields')
                db.execute(f'INSERT INTO {table}(type,name,code,sort_order) VALUES(?,?,?,?)',
                           (row['type'], row['name'], row['code'], row['sort_order']))
        for key, value in CONFIG_DEFAULTS.items():
            db.execute('INSERT INTO config(config_key,config_value) VALUES(?,?)', (key, value))
        db.commit()
        require(db.execute('PRAGMA integrity_check').fetchone()[0] == 'ok', 'SQLite integrity failure')
    validate_database(target, defaults)


def validate_database(path: Path, defaults: Path) -> None:
    expected = json.loads((defaults / 'options.json').read_text(encoding='utf-8'))
    with sqlite3.connect(f'{path.resolve().as_uri()}?mode=ro', uri=True) as db:
        tables = [r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")]
        for table in tables:
            require(re.fullmatch('[a-z_0-9]+', table) is not None, 'Unsafe table name')
            if table in OPTION_TABLES:
                actual = db.execute(f'SELECT type,name,code,sort_order FROM {table} ORDER BY id').fetchall()
                desired = [(r['type'], r['name'], r['code'], r['sort_order']) for r in expected[table]]
                require(actual == desired, f'Reference data changed: {table}')
            elif table == 'config':
                actual = dict(db.execute('SELECT config_key,config_value FROM config'))
                require(actual == CONFIG_DEFAULTS, 'Nonblank service credentials')
            else:
                require(db.execute(f'SELECT count(*) FROM {table}').fetchone()[0] == 0,
                        f'Nonempty business table: {table}')
        require(db.execute('PRAGMA freelist_count').fetchone()[0] == 0, 'Database contains unused pages')


def allowed_source(name: str) -> bool:
    if name in ROOT_FILES:
        return True
    if name.startswith('gradle/wrapper/'):
        return name.rsplit('/', 1)[1] in {'gradle-wrapper.jar', 'gradle-wrapper.properties'}
    if name.startswith('src/main/resources/'):
        return name.removeprefix('src/main/resources/') in RESOURCE_FILES
    if name.startswith('src/main/java/') or name.startswith('src/test/java/'):
        return name.endswith('.java')
    if name.startswith('src/test/resources/boss/'):
        return name.rsplit('/', 1)[1] in {'hr-online-detail.html', 'hr-response-samples.json'}
    if name.startswith('front/'):
        return Path(name).suffix in {'.ts', '.tsx', '.js', '.mjs', '.json', '.yaml', '.svg', '.css'} or name == 'front/.gitignore'
    return (name.startswith('distribution/') and name.removeprefix('distribution/') in DIST_FILES) or name in {
        'scripts/package_share.py', 'scripts/test_package_share.py'}


def patch_export(source: Path, root: Path) -> None:
    copy_file(root / 'distribution/defaults/page-config.json', source / 'src/main/resources/defaults/page-config.json')
    (source / 'gradle.properties').write_text(
        'org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8 -Djava.net.preferIPv4Stack=true\n', encoding='utf-8')
    gradle = source / 'build.gradle.kts'
    original = gradle.read_text(encoding='utf-8')
    updated = original.replace('"node_modules/next/dist/bin/next", "build")',
                               '"node_modules/next/dist/bin/next", "build", "--webpack")')
    require(updated != original or '"build", "--webpack"' in original,
            'Frontend build command changed; review the exporter')
    gradle.write_text(updated, encoding='utf-8')
    package = source / 'front/package.json'
    data = json.loads(package.read_text(encoding='utf-8'))
    data['scripts']['build'] = 'next build --webpack'
    data['scripts']['build:prod'] = 'next build --webpack && node scripts/copy-dist.mjs'
    write_json(package, data)
    workspace = source / 'front/pnpm-workspace.yaml'
    workspace.write_text(workspace.read_text(encoding='utf-8').replace('set this to true or false', 'true'), encoding='utf-8')
    write_json(source / 'src/test/resources/boss/hr-response-samples.json', SYNTHETIC_CASES)
    test = source / 'src/test/java/com/getjobs/worker/boss/BossHrActivityReaderTest.java'
    text = test.read_text(encoding='utf-8').replace('recognizesSanitizedActivityFieldsFrom254ActualCapturedResponses',
                                                 'recognizesActivityFieldsFromSyntheticResponses')
    text = text.replace('assertEquals(254,total);assertTrue(known>=252);', 'assertEquals(6,total);assertEquals(5,known);')
    require('254' not in text and '252' not in text, 'Historical sample assertions changed; review the exporter')
    test.write_text(text, encoding='utf-8')
    fixture = source / 'src/test/resources/boss/hr-online-detail.html'
    text = fixture.read_text(encoding='utf-8')
    fixture.write_text(re.sub(r'<!--.*?-->', '<!-- Synthetic recruiter activity fixture. -->', text, flags=re.S), encoding='utf-8')
    # The original initializer test assumed a populated personal blacklist and
    # Shanghai filters. Keep restart/upgrade coverage with the clean defaults.
    test = source / 'src/test/java/com/getjobs/application/init/DefaultPageConfigInitializerTest.java'
    text = test.read_text(encoding='utf-8')
    replacements = {
        'String.join(",", table.getValue().getFirst().keySet())':
            'table.getValue().isEmpty() ? "type,value" : String.join(",", table.getValue().getFirst().keySet())',
        'assertEquals("上海",': 'assertEquals("全国",',
        'assertTrue(jdbc.queryForObject("SELECT keywords FROM boss_config", String.class).contains("大模型"));':
            'assertEquals("", jdbc.queryForObject("SELECT keywords FROM boss_config", String.class));',
        'assertEquals(16,': 'assertEquals(0,',
    }
    for old, new in replacements.items():
        if old in text:
            text = text.replace(old, new)
    test.write_text(text, encoding='utf-8')


def safe_tree(source: Path, target: Path) -> None:
    for path in sorted(source.rglob('*')):
        require(not path.is_symlink(), f'Symlink in export input: {path.name}')
        if path.is_file():
            copy_file(path, target / path.relative_to(source))


def prepare(root: Path, output: Path, label: str) -> None:
    require(not output.exists(), 'Output directory already exists; use a new --output')
    output.mkdir(parents=True)
    source = output / f'get_jobs-source-{label}'
    runtime = output / f'get_jobs-windows-{label}'
    tracked = subprocess.check_output(['git', 'ls-files', '-z'], cwd=root).decode().split('\0')
    names = {name for name in tracked if name and allowed_source(name)}
    # Also permit this tool's explicitly owned files before their first local commit.
    names.update('distribution/' + name for name in DIST_FILES)
    names.update({'scripts/package_share.py', 'scripts/test_package_share.py'})
    for name in sorted(names):
        copy_file(root / name, source / name)
    patch_export(source, root)
    dist = root / 'src/main/resources/dist'
    require((dist / 'index.html').is_file(), 'Build the current frontend before preparing the export')
    safe_tree(dist, source / 'src/main/resources/dist')
    for package in (source, runtime):
        create_database(package / 'db/getjobs.db', root / 'distribution/defaults')
        copy_file(root / 'LICENSE', package / 'LICENSE')
        copy_file(root / 'distribution/README-Windows.md', package / 'README-Windows.md')
        copy_file(root / 'distribution/README-Windows.md', package / 'README.md')
    for name in ('start.bat', 'start-windows.ps1'):
        text = (root / 'distribution' / name).read_text(encoding='utf-8')
        # Windows PowerShell 5.1 requires a BOM for non-ASCII script text.
        (runtime / name).write_bytes((b'\xef\xbb\xbf' if name.endswith('.ps1') else b'') + text.replace('\n', '\r\n').encode('utf-8'))
    driver = root / 'build/patchright-driver'
    require((driver / '.version').read_text().strip() == '1.62.1', 'Unexpected Patchright version')
    require((driver / 'package/cli.js').is_file(), 'Patchright is not installed')
    safe_tree(driver / 'package', runtime / 'runtime/patchright-driver/package')
    copy_file(driver / '.version', runtime / 'runtime/patchright-driver/.version')
    version = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root).decode().strip()
    inventory = {p.name: [f.relative_to(p).as_posix() for f in sorted(p.rglob('*')) if f.is_file()]
                 for p in (source, runtime)}
    write_json(output / 'build-state.json', {'label': label, 'baseCommit': version,
                                           'source': source.name, 'runtime': runtime.name,
                                           'inventory': inventory})
    print(json.dumps({'source': str(source), 'runtime': str(runtime)}, ensure_ascii=False))


def private_needles(root: Path) -> set[bytes]:
    """Load private values only into memory for leak checking; never print or export them."""
    values: set[str] = set()
    fields = [('ai', 'introduce'), ('ai', 'prompt'), ('boss_config', 'say_hi')]
    for path in [root / 'src/main/resources/defaults/page-config.json', root / 'config/page-config.local.json']:
        if path.is_file():
            defaults = json.loads(path.read_text(encoding='utf-8'))
            values.update(str(row.get(key, '')) for table, key in fields for row in defaults.get(table, []))
    public_defaults = json.loads((root / 'distribution/defaults/page-config.json').read_text(encoding='utf-8'))
    public_values = {str(row.get(key, '')) for table, key in fields for row in public_defaults.get(table, [])}
    local_db = root / 'db/getjobs.db'
    if local_db.is_file():
        with sqlite3.connect(f'{local_db.resolve().as_uri()}?mode=ro', uri=True) as db:
            for query in [
                "SELECT config_value FROM config WHERE config_key <> 'MODEL'", 'SELECT cookie_value FROM cookie',
                'SELECT cookie_value FROM boss_accounts', 'SELECT introduce FROM boss_accounts',
                'SELECT introduce FROM ai',
            ]:
                try:
                    values.update(str(row[0]) for row in db.execute(query) if row[0])
                except sqlite3.OperationalError as error:
                    # A newly initialized or older database may not have optional account tables yet.
                    if 'no such table' not in str(error) and 'no such column' not in str(error):
                        raise
    env = root / '.env'
    if env.is_file():
        for line in env.read_text(encoding='utf-8').splitlines():
            if '=' in line and not line.lstrip().startswith('#') and line.split('=', 1)[0].strip() != 'MODEL':
                values.add(line.split('=', 1)[1].strip().strip('"\''))
    # Check cookie token leaves too, as a serialized cookie may have changed formatting.
    for value in list(values):
        try:
            pending = [json.loads(value)]
        except (ValueError, TypeError):
            continue
        while pending:
            item = pending.pop()
            if isinstance(item, dict):
                pending.extend(v for k, v in item.items() if k not in {'domain', 'path', 'name'})
            elif isinstance(item, list):
                pending.extend(item)
            elif isinstance(item, str):
                values.add(item)
    # Public model identifiers occur in compatibility code/tests. Their saved
    # selection is removed by recreating config rows, rather than matching code.
    values.difference_update(public_values)
    needles = {v.encode('utf-8') for v in values if len(v) >= 12}
    for value in values:
        needles.update(m.encode('utf-8') for m in re.findall(r'(?<!\d)1[3-9]\d{9}(?!\d)|[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}', value))
    return needles


def scan_bytes(data: bytes, name: str, needles: set[bytes]) -> None:
    require(not any(n in data for n in needles), f'Private value detected in {name} (value suppressed)')
    require(re.search(rb'/Users/[A-Za-z0-9_.-]+/|C:[\\]{1,2}Users[\\]{1,2}', data) is None,
            f'Local user path detected in {name}')


def audit_jar(path: Path, root: Path, needles: set[bytes]) -> None:
    with zipfile.ZipFile(path) as jar:
        names = jar.namelist()
        require('BOOT-INF/classes/dist/index.html' in names, 'JAR has no management UI')
        clean_default = (root / 'distribution/defaults/page-config.json').read_bytes()
        require(jar.read('BOOT-INF/classes/defaults/page-config.json') == clean_default, 'JAR contains unsanitized defaults')
        for name in names:
            if not name.startswith('BOOT-INF/classes/') or name.endswith('/'):
                continue
            relative = name.removeprefix('BOOT-INF/classes/')
            require(relative.endswith('.class') or relative.startswith('dist/') or relative in RESOURCE_FILES
                    or relative == 'META-INF/build-info.properties', f'Unexpected application resource: {relative}')
            scan_bytes(jar.read(name), name, needles)
        for entry in jar.infolist():
            if entry.filename.startswith('BOOT-INF/lib/') and entry.filename.endswith('.jar'):
                require(entry.compress_type == zipfile.ZIP_STORED, 'Spring Boot nested JAR must remain uncompressed')


def finish(root: Path, output: Path, jar: Path) -> None:
    state = json.loads((output / 'build-state.json').read_text(encoding='utf-8'))
    source, runtime = output / state['source'], output / state['runtime']
    # Require the JAR compiled from the sanitized source, never the personal working tree.
    require(jar.resolve().is_relative_to(source.resolve() / 'build/libs'), 'Build the sanitized source JAR first')
    needles = private_needles(root)
    audit_jar(jar, root, needles)
    copy_file(jar, runtime / 'get_jobs.jar')
    for package in (source, runtime):
        validate_database(package / 'db/getjobs.db', root / 'distribution/defaults')
        names = list(state['inventory'][package.name])
        if package == runtime:
            names.append('get_jobs.jar')
        for name in names:
            path = package / name
            relative = Path(name)
            require(path.is_file() and path.resolve().is_relative_to(package.resolve()) and not path.is_symlink(),
                    f'Unsafe package file: {relative}')
            if path.suffix != '.jar':
                scan_bytes(path.read_bytes(), relative.as_posix(), needles)
        write_json(package / 'RELEASE.json', {
            'baseCommit': state['baseCommit'], 'label': state['label'],
            'data': 'Fresh SQLite database with public option dictionaries and blank service credentials.',
            'sanitized': ['account logins', 'cookies', 'resumes', 'delivery history', 'AI history and caches',
                          'personal introductions', 'personal blacklists and filters', 'logs and screenshots',
                          'Git history', 'local proxy settings', 'observed test statistics'],
            'defaults': 'Generic editable filters; AI introduction and credentials are blank.',
            'databaseTemplateSource': 'https://github.com/loks666/get_jobs/releases/tag/v2.0.1',
            'databaseTemplateSha256': '81e837df423f1dcf06c89f430493a244bdb17f46d7eab6b22afaec94675e1c97',
        })
        # Only the prepare inventory is archived. Build caches, reports and newly created
        # user files are never eligible, even if they appear after prepare.
        names.append('RELEASE.json')
        files = [package / name for name in sorted(names)]
        manifest = '\n'.join(f'{sha256(p)}  {p.relative_to(package).as_posix()}' for p in files) + '\n'
        (package / 'MANIFEST.sha256').write_text(manifest, encoding='utf-8')
        files.append(package / 'MANIFEST.sha256')
        archive = output / f'{package.name}.zip'
        require(not archive.exists(), 'Archive already exists; use a new output for another release')
        with zipfile.ZipFile(archive, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as zipout:
            for path in files:
                zipout.write(path, f'{package.name}/{path.relative_to(package).as_posix()}')
        with zipfile.ZipFile(archive) as zipin:
            require(zipin.testzip() is None, 'ZIP integrity check failed')
        print(f'{archive.name}: {archive.stat().st_size:,} bytes; sha256={sha256(archive)}')
    archives = sorted(output.glob('*.zip'))
    (output / 'SHA256SUMS.txt').write_text('\n'.join(f'{sha256(p)}  {p.name}' for p in archives) + '\n', encoding='utf-8')
    write_json(output / 'privacy-audit.json', {
        'result': 'passed', 'packages': [p.name for p in archives],
        'checks': ['Source allowlist', 'Fresh SQLite public-only rows; no free pages',
                   'Generic defaults in source and compiled JAR', 'Known private values absent',
                   'No local user paths', 'ZIP integrity', 'No runtime or Git history'],
        'windowsNativeTested': False,
    })


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['prepare', 'finish'])
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--label', default='clean')
    parser.add_argument('--jar', type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    output = args.output.resolve()
    require(output.is_relative_to(root / 'target/share'), 'Output must be inside target/share')
    if args.action == 'prepare':
        require(re.fullmatch('[a-zA-Z0-9_.-]+', args.label) is not None, 'Use a portable release label')
        prepare(root, output, args.label)
    else:
        require(args.jar is not None, 'finish requires --jar')
        finish(root, output, args.jar)


if __name__ == '__main__':
    main()
