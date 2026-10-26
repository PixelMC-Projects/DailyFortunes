#!/usr/bin/env python3
"""One host-bound staging operation. No game/web/database process is started or restarted."""
from pathlib import Path
import datetime
import hashlib
import json
import os
import secrets
import shutil
import subprocess

G = Path('/home/rhymc/dockerserver/mc-server-1')
W = Path('/home/rhymc/dockerserverdata/pixelmc-manager')
DB = Path('/home/rhymc/dockerserverdata')
S = G / 'pixelmcmanager/deploy-dailyfortunes-0.1.2-20261027'
WS = W / 'deployment/dailyfortunes-0.1.2-20261027'
OLD_WS = W / 'deployment/manager-ui-0.5.2-20261026'
JAR = 'dailyfortunes-forge-1.20.1-0.1.2.jar'
JAR_SHA = '3f60e036e1bced2733a4e8e11ea0972198cf7baef1e8d549a21db169ecdfa279'
SERVICE_SHA = 'd13c8c8e4817f9b55e56f53eb5070eee63b1d71a6a28a26c4e8399a584b1d1e6'
MANAGER_SHA = '71bd6bc6d915df392c8223c961d2d793a9bd6d0a6b0ec6ebe229a7a7fe518a27'


def sha(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()


def atomic(p, text, mode=0o600):
    p.parent.mkdir(parents=True, exist_ok=True)
    temporary = p.with_name('.' + p.name + '.dailyfortunes.new')
    with temporary.open('x') as stream:
        os.chmod(temporary, mode)
        stream.write(text)
    os.replace(temporary, p)


def snapshot():
    result = {}
    for name in ['MCSM-6ef856', 'pixelmc-manager-web', 'pixelmc-titles-db']:
        d = json.loads(subprocess.check_output(['docker', 'inspect', name]))[0]
        result[name] = {k: d['State'][k] for k in ['Running', 'Pid', 'StartedAt']}
    return result


def sql(query):
    result = subprocess.run(['docker', 'exec', '-i', '--user', '0', 'pixelmc-titles-db',
        'mariadb', '--defaults-extra-file=/data/secrets/admin-client.cnf',
        '--batch', '--skip-column-names'], input=query, text=True, capture_output=True, timeout=20)
    if result.returncode:
        raise RuntimeError('Database preparation failed; no credentials are printed')
    return result.stdout.strip()


def checksums(directory, names):
    atomic(directory / 'SHA256SUMS', ''.join(sha(directory / n) + '  ' + n + '\n' for n in names))


def replace_checksum_entry(directory, name):
    file = directory / 'SHA256SUMS'
    lines = file.read_text().splitlines()
    found = [i for i, line in enumerate(lines) if line.split(maxsplit=1)[-1].lstrip('*') == name]
    if len(found) != 1:
        raise RuntimeError('Unexpected existing checksum list')
    lines[found[0]] = sha(directory / name) + '  ' + name
    atomic(file, '\n'.join(lines) + '\n')


def main():
    os.umask(0o077)
    if (S / 'staging-report.json').exists():
        raise RuntimeError('Already staged; read the existing report before changing it')
    assert sha(S / JAR) == JAR_SHA
    assert sha(OLD_WS / 'pixelmc-manager-service-0.5.2-web.1.jar') == SERVICE_SHA
    assert int.from_bytes((S / 'classes/DailyFortunesReadiness.class').read_bytes()[6:8], 'big') == 61
    assert int.from_bytes((WS / 'classes/DailyFortunesPrepare.class').read_bytes()[6:8], 'big') == 61
    state = snapshot()
    watched = [G / 'automodpack/automodpack-server.json',
        G / 'automodpack/host-modpack/automodpack-content.json', W / 'manager.json']
    watched += [G / 'mods/pixelmcmanager-forge-1.20.1-0.5.1.jar',
        G / 'mods/memorialtitles-forge-1.20.1-0.4.0-dev.jar']
    active = {str(p): sha(p) for p in watched}
    mods_before = sorted((p.name, p.stat().st_size, p.stat().st_mtime_ns)
        for p in (G / 'mods').iterdir() if p.is_file())
    assert not list((G / 'mods').glob('*dailyfortunes*.jar'))
    assert not (G / 'config/dailyfortunes/server.json').exists()
    future_sync = G / 'pixelmcmanager/deploy-titles-ui-0.4.2-20261026/automodpack-server.next.json'
    sync = json.loads(future_sync.read_text())
    assert sync['generateModpackOnStart'] and sync['autoExcludeServerSideMods']
    assert '/mods/memorialtitles-forge-1.20.1-0.4.2-dev.jar' in sync['syncedFiles']
    assert '/mods/FramedBlocks-9.4.3.jar' in sync['syncedFiles']
    assert not any('dailyfortunes' in p.lower() for p in sync['syncedFiles'])
    sync['syncedFiles'].append('/mods/' + JAR)
    atomic(S / 'automodpack-server.next.json', json.dumps(sync, indent=2) + '\n')
    atomic(S / 'server.json', '{"mode":"auto"}\n')
    atomic(S / 'expected-future.sha256', sha(future_sync) + '  automodpack/automodpack-server.json\n'
        + MANAGER_SHA + '  mods/pixelmcmanager-forge-1.20.1-0.5.2.jar\n')

    # Keep the old launcher and its checksum list for a reversible future-only change.
    launcher = OLD_WS / 'launch-manager.sh'
    activator = G / 'pixelmcmanager/activate-manager.sh'
    before = S / 'before-staging'
    before.mkdir(mode=0o700)
    for p in [launcher, OLD_WS / 'SHA256SUMS', activator]:
        shutil.copy2(p, before / ('web-' + p.name if p.parent == OLD_WS else p.name))
    old_launch = launcher.read_text()
    needle = 'echo \'[Manager UI staging] Starting backend 0.5.2-web.1 with original JVM arguments after native backup.\'\n'
    assert old_launch.count(needle) == 1
    web_hook = ('fortune_stage="${PIXELMC_SERVICE_ROOT:-/data}/deployment/dailyfortunes-0.1.2-20261027"\n'
        'bash "$fortune_stage/prepare-web.sh" "${command[$jar_index]}" manager.json\n')
    new_launch = old_launch.replace(needle, web_hook + needle)
    old_activate = activator.read_text()
    assert old_activate.count('exec "$@"\n') == 1
    assert 'deploy-dailyfortunes-0.1.2-20261027' not in old_activate
    game_hook = ('# DailyFortunes follows the authenticated Manager/Titles startup stages.\n'
        'bash "$root/pixelmcmanager/deploy-dailyfortunes-0.1.2-20261027/activate-game.sh"\n')
    new_activate = old_activate.replace('exec "$@"\n', game_hook + 'exec "$@"\n')
    for p in [S / 'activate-game.sh', WS / 'prepare-web.sh']:
        subprocess.run(['bash', '-n', str(p)], check=True)

    # A new logical database/account only. No current schema, game data or live service changes.
    assert not sql("SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='dailyfortunes';")
    assert not sql("SELECT User FROM mysql.user WHERE User='dailyfortunes';")
    password = secrets.token_urlsafe(36)
    atomic(DB / 'secrets/dailyfortunes-password', password + '\n')
    database = {'url': 'jdbc:mariadb://memorialtitles-db:3306/dailyfortunes',
        'user': 'dailyfortunes', 'password': password}
    atomic(WS / 'fortunes-database.json', json.dumps(database, indent=2) + '\n')
    sql("CREATE DATABASE dailyfortunes CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
        + "CREATE USER 'dailyfortunes'@'%' IDENTIFIED BY '" + password + "';"
        + "GRANT SELECT,INSERT,UPDATE,DELETE,CREATE ON dailyfortunes.* TO 'dailyfortunes'@'%';")
    table_count = sql("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='dailyfortunes';")
    assert table_count == '0'
    checksums(S, [JAR, 'activate-game.sh', 'classes/DailyFortunesReadiness.class',
        'automodpack-server.next.json', 'server.json', 'expected-future.sha256'])
    checksums(WS, ['prepare-web.sh', 'classes/DailyFortunesPrepare.class', 'fortunes-database.json'])
    # Stage all payload first; publish entry-point changes last.
    atomic(launcher, new_launch, 0o700)
    subprocess.run(['bash', '-n', str(launcher)], check=True)
    replace_checksum_entry(OLD_WS, 'launch-manager.sh')
    atomic(activator, new_activate, 0o700)
    subprocess.run(['bash', '-n', str(activator)], check=True)
    assert snapshot() == state
    assert all(sha(Path(p)) == value for p, value in active.items())
    assert mods_before == sorted((p.name, p.stat().st_size, p.stat().st_mtime_ns)
        for p in (G / 'mods').iterdir() if p.is_file())
    for directory in [S, WS, OLD_WS]:
        subprocess.run(['sha256sum', '--check', 'SHA256SUMS'], cwd=directory,
            check=True, stdout=subprocess.DEVNULL)
    report = {'status': 'staged-for-next-normal-start', 'version': '0.1.2',
        'createdAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'gameStage': str(S), 'webStage': str(WS), 'jarSha256': JAR_SHA,
        'before': state, 'after': snapshot(), 'activeFilesUnchanged': True,
        'restartRequested': False, 'runtimeReadinessProbeExecuted': False,
        'productionSchemaInitialized': False, 'newDatabase': 'dailyfortunes',
        'newDatabaseTables': 0, 'appPrivileges': ['SELECT','INSERT','UPDATE','DELETE','CREATE'],
        'clientSync': 'future AutoModpack config only; current manifest unchanged',
        'activationOrder': ['old web native backup', 'DailyFortunes config/schema preflight',
            'web 0.5.2-web.1', 'existing reminder/FramedBlocks/Manager/Titles', 'DailyFortunes 0.1.2', 'game JVM']}
    atomic(S / 'staging-report.json', json.dumps(report, indent=2) + '\n')
    atomic(WS / 'staging-report.json', json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
