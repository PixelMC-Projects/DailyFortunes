#!/usr/bin/env python3
"""Apply the reviewed DailyFortunes offsite-backup extension, without running a backup.

Only the exact reviewed predecessor or exact output is accepted. No network is used
by this updater. Run as the account owning the target (root on the production host).
"""
from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import py_compile
import stat
import tempfile

ORIGINAL_SHA256 = "ec3c3b538d560feb2872a506ca9b569080303d59ce9ae1611b494cbe8cf268c0"
PATCHED_SHA256 = "88406e65005ef8f4563e1a634649058c3e30c428badbb9c47bf1557ca7aacc06"
MARKER = "# dailyfortunes-offsite-extension: 20261027-v1"

HELPERS = '''
def discover_databases(defaults):
    # Query schema names only; both schemas are exported in ONE consistent transaction.
    query = ("SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA "
             "WHERE SCHEMA_NAME IN ('memorialtitles','dailyfortunes') ORDER BY SCHEMA_NAME")
    result = subprocess.run(
        ["docker", "exec", "-i", "--user", "0", CONF["database_container"],
         "nice", "-n", "19", "mariadb", "--defaults-extra-file=/dev/stdin",
         "--batch", "--skip-column-names", "--execute", query],
        input=defaults, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
    if result.returncode:
        (BASE / "last-dump-error.log").write_text(redact(result.stderr.decode(errors="replace")))
        raise RuntimeError("Database discovery failed; no incomplete offsite snapshot uploaded")
    try:
        names = result.stdout.decode("utf-8", errors="strict").splitlines()
    except UnicodeDecodeError:
        raise RuntimeError("Unexpected database discovery response") from None
    if (len(names) != len(set(names)) or "memorialtitles" not in names
            or any(name not in {"memorialtitles", "dailyfortunes"} for name in names)):
        raise RuntimeError("Unexpected database discovery response")
    databases = ["memorialtitles"]
    if "dailyfortunes" in names:
        credential = ROOT / "secrets/dailyfortunes-password"
        if credential.is_symlink() or not credential.is_file() or not credential.read_text().strip():
            raise RuntimeError("DailyFortunes credentials missing; no incomplete offsite snapshot uploaded")
        databases.append("dailyfortunes")
    return databases


def running_manager_service_jar():
    # Never run a newly staged service against an older live Manager database.
    allowed = {
        "/data/releases/0.5.1/pixelmc-manager-service.jar",
        "/data/deployment/manager-ui-0.5.2-20261026/pixelmc-manager-service-0.5.2-web.1.jar",
    }
    result = subprocess.run(
        ["docker", "exec", "pixelmc-manager-web", "cat", "/proc/1/cmdline"],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=15)
    if result.returncode:
        raise RuntimeError("Cannot inspect running Manager; no incomplete offsite snapshot uploaded")
    try:
        arguments = result.stdout.decode("utf-8", errors="strict").rstrip("\\x00").split("\\x00")
    except UnicodeDecodeError:
        raise RuntimeError("Cannot verify running Manager process") from None
    if not arguments or Path(arguments[0]).name != "java" or arguments.count("-jar") != 1:
        raise RuntimeError("Cannot verify running Manager Java process; no incomplete offsite snapshot uploaded")
    index = arguments.index("-jar") + 1
    if index >= len(arguments):
        raise RuntimeError("Unrecognized running Manager service; no incomplete offsite snapshot uploaded")
    candidate = arguments[index]
    if candidate == "releases/0.5.1/pixelmc-manager-service.jar":
        # The deployed 0.5.1 launch command uses this exact relative path.
        # Accept no arbitrary relative paths or traversal; verify its actual cwd first.
        working_directory = subprocess.run(
            ["docker", "exec", "pixelmc-manager-web", "readlink", "/proc/1/cwd"],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=15)
        try:
            cwd = working_directory.stdout.decode("utf-8", errors="strict").rstrip("\\n")
        except UnicodeDecodeError:
            raise RuntimeError("Cannot verify running Manager working directory") from None
        if working_directory.returncode or cwd != "/data":
            raise RuntimeError("Cannot verify running Manager working directory; no incomplete offsite snapshot uploaded")
        candidate = "/data/" + candidate
    if candidate not in allowed:
        raise RuntimeError("Unrecognized running Manager service; no incomplete offsite snapshot uploaded")
    return candidate


'''


def replace_once(source: str, before: str, after: str) -> str:
    if source.count(before) != 1:
        raise ValueError("Reviewed source anchor missing or ambiguous")
    return source.replace(before, after, 1)


def rewrite_source(original: bytes) -> bytes:
    if hashlib.sha256(original).hexdigest() != ORIGINAL_SHA256:
        raise ValueError("Original SHA256 differs from the reviewed backup.py; no changes made")
    source = original.decode("utf-8")
    if MARKER in source:
        raise ValueError("Unexpected pre-existing patch marker")
    source = replace_once(source, "import datetime as dt\n", MARKER + "\nimport datetime as dt\n")
    source = replace_once(
        source,
        'for path in (SECRETS / "restic-password", ROOT / "secrets/root-password", ROOT / "secrets/app-password"):',
        'for path in (SECRETS / "restic-password", ROOT / "secrets/root-password", ROOT / "secrets/app-password", ROOT / "secrets/dailyfortunes-password"):')
    source = replace_once(source, "def prepare_snapshot():\n", HELPERS + "def prepare_snapshot():\n")
    source = replace_once(
        source,
        '        command = ["docker", "exec", "-i", "--user", "0", CONF["database_container"],\n',
        '        databases = discover_databases(defaults)\n'
        '        command = ["docker", "exec", "-i", "--user", "0", CONF["database_container"],\n')
    source = replace_once(source, '                   "--databases", "memorialtitles"]',
                          '                   "--databases", *databases]')
    source = replace_once(
        source,
        '            result = subprocess.run(["docker", "exec", "pixelmc-manager-web", "nice", "-n", "19", "java", "-Xmx256m", "-jar", "/data/releases/0.5.1/pixelmc-manager-service.jar", "backup", "/data/manager.json"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=150)',
        '            manager_jar = running_manager_service_jar()\n'
        '            result = subprocess.run(["docker", "exec", "pixelmc-manager-web", "nice", "-n", "19", "java", "-Xmx256m", "-jar", manager_jar, "backup", "/data/manager.json"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=150)')
    source = replace_once(
        source, 'manifest = {"format": 1, "created_at": timestamp(), "database": "memorialtitles",',
        'manifest = {"format": 1, "created_at": timestamp(), "databases": databases,')
    return source.encode("utf-8")


def compile_checked(path: Path) -> None:
    # py_compile validates syntax only; it never imports or runs the backup script.
    descriptor, name = tempfile.mkstemp(prefix=".backup-compile-", suffix=".pyc", dir=path.parent)
    os.close(descriptor)
    compiled = Path(name)
    try:
        py_compile.compile(str(path), cfile=str(compiled), doraise=True)
    finally:
        compiled.unlink(missing_ok=True)


def sync_directory(directory: Path) -> None:
    if os.name == "posix":
        descriptor = os.open(directory, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)


def apply_update(target: Path) -> str:
    target = target.absolute()
    if target.is_symlink() or not target.is_file():
        raise ValueError("Target must be an existing regular file, not a symlink")
    metadata = target.stat()
    original = target.read_bytes()
    original_hash = hashlib.sha256(original).hexdigest()
    if original_hash == PATCHED_SHA256:
        if original.count(MARKER.encode()) != 1:
            raise ValueError("Patched SHA256/marker mismatch")
        compile_checked(target)
        return "already-current"
    if MARKER.encode() in original:
        raise ValueError("Existing patch marker has an unreviewed SHA256; no changes made")
    updated = rewrite_source(original)
    if hashlib.sha256(updated).hexdigest() != PATCHED_SHA256:
        raise ValueError("Generated patch SHA256 mismatch; no changes made")
    descriptor, name = tempfile.mkstemp(prefix=".backup-update-", suffix=".py", dir=target.parent)
    temporary = Path(name)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(updated)
            stream.flush()
            os.fsync(stream.fileno())
            if hasattr(os, "fchown"):
                os.fchown(stream.fileno(), metadata.st_uid, metadata.st_gid)
            os.fchmod(stream.fileno(), stat.S_IMODE(metadata.st_mode)) if hasattr(os, "fchmod") else None
        compile_checked(temporary)
        if target.is_symlink() or target.read_bytes() != original:
            raise ValueError("Target changed during preparation; no changes made")
        backup = target.with_name(target.name + ".before-dailyfortunes-20261027." + original_hash[:16] + ".bak")
        if backup.exists() or backup.is_symlink():
            if backup.is_symlink() or not backup.is_file() or backup.read_bytes() != original:
                raise ValueError("Existing original backup does not match; no changes made")
        else:
            backup_fd = os.open(backup, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(backup_fd, "wb") as stream:
                stream.write(original)
                stream.flush()
                os.fsync(stream.fileno())
        sync_directory(target.parent)
        os.replace(temporary, target)
        sync_directory(target.parent)
        return "updated"
    finally:
        temporary.unlink(missing_ok=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("target", type=Path, help="Absolute path to the reviewed offsite/backup.py")
    options = parser.parse_args()
    if not options.target.is_absolute():
        parser.error("Target path must be absolute")
    try:
        result = apply_update(options.target)
    except (OSError, ValueError, py_compile.PyCompileError) as error:
        # Do not echo source contents or commands, which might contain credentials.
        raise SystemExit(f"Updater refused: {type(error).__name__}; no backup was run") from None
    print(f"Offsite backup updater: {result}; no backup was run")


if __name__ == "__main__":
    main()
