"""Offline fixture checks. Every Docker, SQL and Restic call is mocked."""
from __future__ import annotations

import ast
import datetime as dt
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("offsite_updater", HERE / "update-offsite-backup.py")
UPDATER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(UPDATER)
OLD_JAR = "/data/releases/0.5.1/pixelmc-manager-service.jar"
NEW_JAR = "/data/deployment/manager-ui-0.5.2-20261026/pixelmc-manager-service-0.5.2-web.1.jar"
ORIGINAL = b""


class BackupFixtures(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="offsite-mock-")
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name) / "root"
        self.base = self.root / "offsite"
        self.base.mkdir(parents=True)
        (self.root / "secrets").mkdir()
        for name in ("root-password", "app-password", "dailyfortunes-password"):
            (self.root / "secrets" / name).write_text("test-" + name)
        self.secrets = self.base / "secrets"
        self.secrets.mkdir()
        (self.secrets / "restic-password").write_text("test-restic-password")
        self.environment = mock.patch.dict(os.environ, {
            "AWS_ACCESS_KEY_ID": "TEST_AWS_ID", "AWS_SECRET_ACCESS_KEY": "TEST_AWS_SECRET"})
        self.environment.start()
        self.addCleanup(self.environment.stop)
        self.game = self.root / "game-config"
        self.game.mkdir()
        (self.game / "sample.json").write_text("{}")
        manager = self.root / "pixelmc-manager"
        manager.mkdir()
        (manager / "manager.json").write_text('{"fixture":true}')
        self.schemas = b"memorialtitles\n"
        self.cmdline = b"/opt/java/bin/java\0-Xmx512m\0-jar\0" + OLD_JAR.encode() + b"\0/data/manager.json\0"
        self.cwd = b"/data\n"
        self.cwd_code = 0
        self.discovery_code = self.proc_code = self.dump_code = 0
        self.calls = []
        self.uploads = []
        tree = ast.parse(UPDATER.rewrite_source(ORIGINAL))
        functions = ast.Module(body=[node for node in tree.body if isinstance(node, ast.FunctionDef)], type_ignores=[])
        self.scope = dict(dt=dt, hashlib=hashlib, json=json, os=os, Path=Path,
                          shutil=shutil, subprocess=SimpleNamespace(run=self.run_mock, PIPE=subprocess.PIPE),
                          sys=sys, tempfile=tempfile, ROOT=self.root, BASE=self.base, SECRETS=self.secrets,
                          CONF={"database_container": "fixture-database", "game_config": str(self.game),
                                "host": "fixture-host", "tag": "fixture-tag"})
        exec(compile(functions, "mock-only-backup.py", "exec"), self.scope)

        def restic_mock(*args, **kwargs):
            self.uploads.append((args, kwargs))
            return '{"message_type":"summary","snapshot_id":"fixture-only","total_bytes_processed":123}\n'

        self.scope["restic"] = restic_mock

    def run_mock(self, command, **kwargs):
        self.calls.append((command, kwargs))
        # No other subprocess commands can run, even on an unexpected code path.
        if "mariadb-dump" in command:
            kwargs["stdout"].write(b"-- mock SQL --\n" + b"SELECT 1;\n" * 20)
            return SimpleNamespace(returncode=self.dump_code, stdout=b"", stderr=b"test-dailyfortunes-password test-root-password")
        if "mariadb" in command:
            self.assertIn("--defaults-extra-file=/dev/stdin", command)
            self.assertIsInstance(kwargs["input"], bytes)
            return SimpleNamespace(returncode=self.discovery_code, stdout=self.schemas,
                                   stderr=b"test-dailyfortunes-password test-root-password")
        if command == ["docker", "exec", "pixelmc-manager-web", "cat", "/proc/1/cmdline"]:
            return SimpleNamespace(returncode=self.proc_code, stdout=self.cmdline, stderr=b"mock error")
        if command == ["docker", "exec", "pixelmc-manager-web", "readlink", "/proc/1/cwd"]:
            return SimpleNamespace(returncode=self.cwd_code, stdout=self.cwd, stderr=b"mock error")
        if "java" in command and "backup" in command:
            archives = self.root / "pixelmc-manager/data/backups/manager"
            archives.mkdir(parents=True, exist_ok=True)
            (archives / "manager-fixture.zip").write_bytes(b"mock archive")
            (archives / "manager-fixture.zip.sha256").write_text("mock checksum")
            return SimpleNamespace(returncode=0, stdout=b"", stderr=b"")
        self.fail("Unexpected subprocess command; real execution is forbidden")

    def capture(self):
        return self.scope["prepare_snapshot"]()

    def test_legacy_database_and_active_legacy_jar(self):
        current, manifest = self.capture()
        self.assertEqual(manifest["databases"], ["memorialtitles"])
        self.assertNotIn("database", manifest)
        self.assertTrue((current / "manager/manager.json").is_file())
        native = next(command for command, _ in self.calls if "backup" in command)
        self.assertEqual(native[native.index("-jar") + 1], OLD_JAR)
        self.assertNotIn(NEW_JAR, native)

    def test_new_service_with_daily_schema_has_one_consistent_dump(self):
        self.schemas = b"dailyfortunes\nmemorialtitles\n"
        self.cmdline = b"java\0-Xmx512m\0-jar\0" + NEW_JAR.encode() + b"\0/data/manager.json\0"
        current, manifest = self.capture()
        self.assertEqual(manifest["databases"], ["memorialtitles", "dailyfortunes"])
        dumps = [command for command, _ in self.calls if "mariadb-dump" in command]
        self.assertEqual(len(dumps), 1)
        self.assertEqual(dumps[0][-3:], ["--databases", "memorialtitles", "dailyfortunes"])
        for option in ("--single-transaction", "--quick", "--skip-lock-tables"):
            self.assertIn(option, dumps[0])
        self.assertIn("nice", dumps[0])
        self.assertEqual((current / "database-instance/secrets/dailyfortunes-password").read_text(), "test-dailyfortunes-password")
        native = next(command for command, _ in self.calls if "backup" in command)
        self.assertEqual(native[native.index("-jar") + 1], NEW_JAR)
        self.assertIn("-Xmx256m", native)

    def test_exact_relative_legacy_jar_requires_verified_data_cwd(self):
        self.cmdline = b"java\0-Xmx512m\0-jar\0releases/0.5.1/pixelmc-manager-service.jar\0run\0manager.json\0"
        _, manifest = self.capture()
        self.assertEqual(manifest["databases"], ["memorialtitles"])
        self.assertTrue(any(command == ["docker", "exec", "pixelmc-manager-web", "readlink", "/proc/1/cwd"] for command, _ in self.calls))
        native = next(command for command, _ in self.calls if "backup" in command)
        self.assertEqual(native[native.index("-jar") + 1], OLD_JAR)

    def test_relative_legacy_jar_rejects_other_or_unreadable_cwd(self):
        self.cmdline = b"java\0-jar\0releases/0.5.1/pixelmc-manager-service.jar\0run\0manager.json\0"
        for value in (b"/tmp\n", b"/data/subdir\n", b"/data/../data\n", b"/data\n/data\n", b"\xff\n"):
            with self.subTest(cwd=value):
                self.cwd = value
                with self.assertRaisesRegex(RuntimeError, "working directory"):
                    self.scope["backup"]()
                self.assertFalse(self.uploads)
        self.cwd = b"/data\n"
        self.cwd_code = 1
        with self.assertRaisesRegex(RuntimeError, "working directory"):
            self.scope["backup"]()
        self.assertFalse(self.uploads)

    def test_arbitrary_relative_and_traversal_jar_paths_rejected(self):
        for value in ("../data/releases/0.5.1/pixelmc-manager-service.jar", "releases/../releases/0.5.1/pixelmc-manager-service.jar", "./releases/0.5.1/pixelmc-manager-service.jar", "/data/releases/../releases/0.5.1/pixelmc-manager-service.jar", "service.jar"):
            with self.subTest(jar=value):
                self.cmdline = b"java\0-jar\0" + value.encode() + b"\0"
                with self.assertRaisesRegex(RuntimeError, "Unrecognized running Manager"):
                    self.scope["backup"]()
                self.assertFalse(self.uploads)

    def test_daily_schema_included_even_when_manager_uses_old_service(self):
        self.schemas = b"dailyfortunes\nmemorialtitles\n"
        _, manifest = self.capture()
        self.assertEqual(manifest["databases"], ["memorialtitles", "dailyfortunes"])
        native = next(command for command, _ in self.calls if "backup" in command)
        self.assertIn(OLD_JAR, native)

    def test_unknown_service_fails_before_replacing_current_or_uploading(self):
        current = self.base / "staging/current"
        current.mkdir(parents=True)
        (current / "keep.txt").write_text("previous verified capture")
        self.cmdline = b"java\0-jar\0/data/unreviewed/service.jar\0"
        with self.assertRaisesRegex(RuntimeError, "Unrecognized running Manager"):
            self.scope["backup"]()
        self.assertEqual((current / "keep.txt").read_text(), "previous verified capture")
        self.assertFalse(self.uploads)
        self.assertEqual(list((self.base / "staging").glob(".capture-*")), [])

    def test_java_process_validation(self):
        for value in (b"sh\0-c\0java -jar " + OLD_JAR.encode(), b"java\0-jar\0", b"java\0-jar\0" + OLD_JAR.encode() + b"\0-jar\0other", b"java\0-jar\0\xff\0"):
            with self.subTest(cmdline=value):
                self.cmdline = value
                with self.assertRaises(RuntimeError):
                    self.scope["running_manager_service_jar"]()
        self.proc_code = 1
        with self.assertRaisesRegex(RuntimeError, "Cannot inspect"):
            self.scope["running_manager_service_jar"]()

    def test_database_probe_rejects_unexpected_or_missing_schema(self):
        for value in (b"", b"dailyfortunes\n", b"mysql\nmemorialtitles\n", b"memorialtitles\nmemorialtitles\n", b"\xff\n"):
            with self.subTest(schemas=value):
                self.schemas = value
                with self.assertRaises(RuntimeError):
                    self.scope["discover_databases"](b"fixture-defaults")

    def test_absent_daily_credentials_allowed_until_schema_exists(self):
        (self.root / "secrets/dailyfortunes-password").unlink()
        self.assertEqual(self.scope["discover_databases"](b"fixture-defaults"), ["memorialtitles"])
        self.schemas = b"dailyfortunes\nmemorialtitles\n"
        with self.assertRaisesRegex(RuntimeError, "credentials missing"):
            self.scope["backup"]()
        self.assertFalse(self.uploads)

    def test_database_failure_logs_are_redacted_and_do_not_upload(self):
        self.discovery_code = 1
        with self.assertRaisesRegex(RuntimeError, "Database discovery failed"):
            self.scope["backup"]()
        log = (self.base / "last-dump-error.log").read_text()
        self.assertNotIn("test-dailyfortunes-password", log)
        self.assertNotIn("test-root-password", log)
        self.assertFalse(self.uploads)

    def test_dump_failure_does_not_upload(self):
        self.dump_code = 1
        with self.assertRaisesRegex(RuntimeError, "SQL export failed"):
            self.scope["backup"]()
        self.assertFalse(self.uploads)
        self.assertNotIn("test-dailyfortunes-password", (self.base / "last-dump-error.log").read_text())

    def test_redacts_all_credentials(self):
        values = ["test-dailyfortunes-password", "test-root-password", "test-app-password", "test-restic-password", "TEST_AWS_ID", "TEST_AWS_SECRET"]
        result = self.scope["redact"](" ".join(values))
        for value in values:
            self.assertNotIn(value, result)

    def test_successful_backup_only_uses_mock_restic_after_capture(self):
        self.schemas = b"dailyfortunes\nmemorialtitles\n"
        state = self.scope["backup"]()
        self.assertEqual(state["snapshot_id"], "fixture-only")
        self.assertEqual(len(self.uploads), 1)
        self.assertEqual(self.uploads[0][0][0], "backup")

    def test_no_manager_config_keeps_native_backup_optional(self):
        (self.root / "pixelmc-manager/manager.json").unlink()
        self.capture()
        self.assertFalse(any("/proc/1/cmdline" in command for command, _ in self.calls))


class UpdaterChecks(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="offsite-updater-")
        self.addCleanup(self.directory.cleanup)
        self.target = Path(self.directory.name) / "backup.py"
        self.target.write_bytes(ORIGINAL)

    def test_atomic_update_and_idempotency_with_original_backup(self):
        before_mode = stat.S_IMODE(self.target.stat().st_mode)
        self.assertEqual(UPDATER.apply_update(self.target), "updated")
        self.assertEqual(hashlib.sha256(self.target.read_bytes()).hexdigest(), UPDATER.PATCHED_SHA256)
        self.assertEqual(UPDATER.apply_update(self.target), "already-current")
        backups = list(self.target.parent.glob("*.bak"))
        self.assertEqual(len(backups), 1)
        self.assertEqual(backups[0].read_bytes(), ORIGINAL)
        if os.name == "posix":
            self.assertEqual(stat.S_IMODE(self.target.stat().st_mode), before_mode)
        self.assertFalse(list(self.target.parent.glob(".backup-*")))

    def test_unknown_input_and_tampered_marker_refused(self):
        for data in (ORIGINAL + b"# unexpected\n", UPDATER.MARKER.encode() + b"\n"):
            self.target.write_bytes(data)
            with self.assertRaises(ValueError):
                UPDATER.apply_update(self.target)
            self.assertEqual(self.target.read_bytes(), data)

    def test_compile_failure_leaves_original_unchanged(self):
        with mock.patch.object(UPDATER, "compile_checked", side_effect=ValueError("fixture syntax failure")):
            with self.assertRaises(ValueError):
                UPDATER.apply_update(self.target)
        self.assertEqual(self.target.read_bytes(), ORIGINAL)
        self.assertFalse(list(self.target.parent.glob("*.bak")))

    def test_conflicting_backup_refused(self):
        backup = self.target.with_name(self.target.name + ".before-dailyfortunes-20261027." + UPDATER.ORIGINAL_SHA256[:16] + ".bak")
        backup.write_text("wrong backup")
        with self.assertRaises(ValueError):
            UPDATER.apply_update(self.target)
        self.assertEqual(self.target.read_bytes(), ORIGINAL)

    def test_existing_controls_unchanged(self):
        old = ast.parse(ORIGINAL)
        new = ast.parse(UPDATER.rewrite_source(ORIGINAL))
        old_functions = {node.name: node for node in old.body if isinstance(node, ast.FunctionDef)}
        new_functions = {node.name: node for node in new.body if isinstance(node, ast.FunctionDef)}
        for name in ("restic", "main", "prune", "backup", "copy_path", "atomic_json", "file_hash"):
            self.assertEqual(ast.dump(old_functions[name]), ast.dump(new_functions[name]), name)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: python test_offsite_backup_update.py /absolute/path/to/backup.before.py")
    fixture = Path(sys.argv.pop())
    ORIGINAL = fixture.read_bytes()
    if hashlib.sha256(ORIGINAL).hexdigest() != UPDATER.ORIGINAL_SHA256:
        raise SystemExit("Fixture SHA256 is not the reviewed predecessor")
    unittest.main(verbosity=2)
