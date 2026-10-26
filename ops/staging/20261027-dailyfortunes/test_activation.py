"""Offline activation fixtures: fake payload, fake Java, no service/network access.

Run with Python 3. Uses Git for Windows Bash when available, otherwise system
Bash. Every script execution is confined to a new temporary game directory.
"""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


HERE = Path(__file__).resolve().parent
SCRIPT = HERE / "activate-game.sh"
JAR = "dailyfortunes-forge-1.20.1-0.1.2.jar"
MANAGER = "pixelmcmanager-forge-1.20.1-0.5.2.jar"
GIT_BASH = Path("C:/Program Files/Git/bin/bash.exe")
BASH = str(GIT_BASH) if GIT_BASH.is_file() else shutil.which("bash")


def bash_path(path):
    value = Path(path).resolve().as_posix()
    if os.name == "nt":
        return "/" + value[0].lower() + value[2:]
    return value


def write(path, payload):
    path.parent.mkdir(parents=True, exist_ok=True)
    if isinstance(payload, str):
        payload = payload.encode("utf-8")
    path.write_bytes(payload)


def checksums(base, relative_names):
    return "".join(
        hashlib.sha256((base / name).read_bytes()).hexdigest() + "  " + name + "\n"
        for name in relative_names
    )


@unittest.skipUnless(BASH, "Bash unavailable; run this isolated fixture on a machine with Bash")
class ActivationFixtures(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="dailyfortunes-activation-")
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.root = self.base / "fake-game"
        self.stage = self.root / "pixelmcmanager/deploy-dailyfortunes-fixture"
        self.bin = self.base / "fake-bin"
        self.log = self.base / "fake-java.log"
        self.cp_log = self.base / "fake-cp.log"
        self.manager_marker = self.root / "pixelmcmanager/deploy-manager-ui-0.5.2-20261026/applied.txt"
        self.titles_marker = self.root / "pixelmcmanager/deploy-titles-ui-0.4.2-20261026/applied.txt"
        self.old_sync = {
            "generateModpackOnStart": True,
            "autoExcludeServerSideMods": True,
            "syncedFiles": ["/mods/memorialtitles-fixture.jar", "/mods/FramedBlocks-fixture.jar"],
        }
        self.new_sync = {**self.old_sync, "syncedFiles": [*self.old_sync["syncedFiles"], "/mods/" + JAR]}
        self.jar_bytes = b"NOT-A-REAL-JAR: DailyFortunes activation fixture only\n"
        write(self.root / "automodpack/automodpack-server.json", json.dumps(self.old_sync) + "\n")
        write(self.root / "mods" / MANAGER, b"NOT-A-REAL-MANAGER-JAR\n")
        write(self.root / "mods/unrelated-fixture.jar", b"unchanged unrelated payload\n")
        write(self.root / "config/pixelmcmanager-bridge.json", '{"fixture":"offline-only"}\n')
        write(self.root / "libraries/com/google/code/gson/gson/2.10/gson-2.10.jar", b"FAKE-GSON\n")
        write(self.manager_marker, "fixture complete\n")
        write(self.titles_marker, "fixture complete\n")
        write(self.stage / "activate-game.sh", SCRIPT.read_text("utf-8").replace("\r\n", "\n"))
        write(self.stage / JAR, self.jar_bytes)
        write(self.stage / "server.json", '{"mode":"auto"}\n')
        write(self.stage / "automodpack-server.next.json", json.dumps(self.new_sync) + "\n")
        write(self.stage / "classes/DailyFortunesReadiness.class", b"FAKE-CLASS-NEVER-EXECUTED\n")
        write(self.stage / "expected-future.sha256", checksums(self.root, [
            "automodpack/automodpack-server.json", "mods/" + MANAGER]))
        write(self.stage / "SHA256SUMS", checksums(self.stage, [JAR, "activate-game.sh",
            "classes/DailyFortunesReadiness.class", "server.json",
            "automodpack-server.next.json", "expected-future.sha256"]))
        write(self.bin / "java", """#!/usr/bin/env bash
set -eu
printf '%s\\0' "$@" >> "$FIXTURE_JAVA_LOG"
printf '\\n' >> "$FIXTURE_JAVA_LOG"
[[ "$1" == '-Xms16m' && "$2" == '-Xmx64m' && "$3" == '-cp' ]]
[[ "$5" == 'DailyFortunesReadiness' ]]
[[ -f "${4%:*}/DailyFortunesReadiness.class" && -f "${4##*:}" && -f "$6" ]]
exit "${FIXTURE_JAVA_EXIT:-0}"
""")
        write(self.bin / "cp", """#!/usr/bin/env bash
set -eu
printf '%s\\n' "${@: -1}" >> "$FIXTURE_CP_LOG"
if [[ -n "${FIXTURE_CP_FAIL_DEST:-}" && "${@: -1}" == "$FIXTURE_CP_FAIL_DEST" ]]; then
  echo 'injected fixture cp failure' >&2
  exit 77
fi
exec /usr/bin/cp "$@"
""")
        write(self.bin / "mv", """#!/usr/bin/env bash
set -eu
if [[ -n "${FIXTURE_MV_FAIL_DEST:-}" && "${@: -1}" == "$FIXTURE_MV_FAIL_DEST" ]]; then
  echo 'injected fixture mv failure' >&2
  exit 78
fi
exec /usr/bin/mv "$@"
""")
        for stub in (self.bin / "java", self.bin / "cp", self.bin / "mv"):
            stub.chmod(0o700)
        self.initial = self.snapshot()

    def snapshot(self):
        return {p.relative_to(self.root).as_posix(): p.read_bytes()
                for prefix in ("mods", "config", "automodpack")
                for p in (self.root / prefix).rglob("*") if p.is_file()}

    def run_script(self, *args, java_exit=0, cp_fail="", mv_fail=""):
        env = os.environ.copy()
        env.update(PIXELMC_GAME_ROOT=bash_path(self.root),
                   FIXTURE_BIN=bash_path(self.bin),
                   FIXTURE_SCRIPT=bash_path(self.stage / "activate-game.sh"),
                   FIXTURE_JAVA_LOG=bash_path(self.log),
                   FIXTURE_CP_LOG=bash_path(self.cp_log),
                   FIXTURE_JAVA_EXIT=str(java_exit), FIXTURE_CP_FAIL_DEST=cp_fail,
                   FIXTURE_MV_FAIL_DEST=mv_fail)
        # All executables resolve from this temporary fake-bin first. Real Java
        # and the real readiness helper are never invoked.
        return subprocess.run([BASH, "--noprofile", "--norc", "-c",
            'export PATH="$FIXTURE_BIN:/usr/bin:/bin"; exec bash "$FIXTURE_SCRIPT" "$@"',
            "fixture", *args], cwd=self.root, env=env, capture_output=True,
            text=True, encoding="utf-8", timeout=20)

    def assert_deferred(self, result, *, java_called=False, success=True):
        if success:
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        else:
            self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.snapshot(), self.initial)
        self.assertFalse((self.stage / "applied.txt").exists())
        self.assertEqual(self.log.exists(), java_called)

    def test_check_payload_only(self):
        self.assert_deferred(self.run_script("--check-payload"))

    def test_check_payload_rejects_tampering(self):
        write(self.stage / JAR, b"TAMPERED\n")
        self.assert_deferred(self.run_script("--check-payload"), success=False)

    def test_activation_rejects_payload_tampering_before_gate(self):
        write(self.stage / "server.json", '{"mode":"database"}\n')
        self.assert_deferred(self.run_script(), success=False)

    def test_manager_activation_dependency_missing(self):
        self.manager_marker.unlink()
        self.assert_deferred(self.run_script())

    def test_titles_activation_dependency_missing(self):
        self.titles_marker.unlink()
        self.assert_deferred(self.run_script())

    def test_prior_startup_file_drift(self):
        write(self.root / "mods" / MANAGER, b"DIFFERENT-MANAGER\n")
        self.initial = self.snapshot()
        self.assert_deferred(self.run_script())

    def test_existing_mod_is_not_overwritten(self):
        write(self.root / "mods/DailyFortunes-custom.jar", b"DO-NOT-REPLACE\n")
        self.initial = self.snapshot()
        self.assert_deferred(self.run_script())

    def test_existing_config_is_not_overwritten(self):
        write(self.root / "config/dailyfortunes/server.json", '{"existing":true}\n')
        self.initial = self.snapshot()
        self.assert_deferred(self.run_script())

    def test_capability_gate_failure_defers_install(self):
        self.assert_deferred(self.run_script(java_exit=2), java_called=True)

    def test_missing_gson_fails_fake_java_classpath_gate(self):
        (self.root / "libraries/com/google/code/gson/gson/2.10/gson-2.10.jar").unlink()
        self.assert_deferred(self.run_script(), java_called=True)

    def test_interrupted_pending_activation_blocks_startup(self):
        write(self.stage / "activation.pending", "previous interrupted fixture\n")
        result = self.run_script()
        self.assert_deferred(result, success=False)
        self.assertIn("Interrupted activation needs review", result.stderr)
        self.assertTrue((self.stage / "activation.pending").exists())

    def test_success_installs_exact_payload_auto_mode_and_sync(self):
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        expected = dict(self.initial)
        expected["mods/" + JAR] = self.jar_bytes
        expected["config/dailyfortunes/server.json"] = (self.stage / "server.json").read_bytes()
        expected["automodpack/automodpack-server.json"] = (self.stage / "automodpack-server.next.json").read_bytes()
        self.assertEqual(self.snapshot(), expected)
        self.assertEqual(json.loads((self.root / "config/dailyfortunes/server.json").read_text()), {"mode": "auto"})
        self.assertEqual(json.loads((self.root / "automodpack/automodpack-server.json").read_text()), self.new_sync)
        self.assertTrue((self.stage / "applied.txt").is_file())
        self.assertFalse((self.stage / ".applied.tmp").exists())
        self.assertFalse((self.stage / "activation.pending").exists())
        copies = self.cp_log.read_text("utf-8").splitlines()
        self.assertLess(copies.index("config/dailyfortunes/.server.new"), copies.index("mods/.dailyfortunes.new"))
        self.assertLess(copies.index("automodpack/.dailyfortunes.new"), copies.index("mods/.dailyfortunes.new"))
        args = self.log.read_bytes().rstrip(b"\n").split(b"\0")[:-1]
        self.assertEqual(args[:3], [b"-Xms16m", b"-Xmx64m", b"-cp"])
        self.assertEqual(args[4], b"DailyFortunesReadiness")
        self.assertEqual(len(args), 6)

    def test_repeat_execution_is_idempotent(self):
        first = self.run_script()
        self.assertEqual(first.returncode, 0, first.stdout + first.stderr)
        snapshot = self.snapshot()
        marker = (self.stage / "applied.txt").read_bytes()
        calls = self.log.read_bytes()
        # Completion is authoritative: a second execution must do no writes or
        # probes, even if a staged file is subsequently no longer available.
        (self.stage / JAR).unlink()
        second = self.run_script(java_exit=2, cp_fail="mods/.dailyfortunes.new")
        self.assertEqual(second.returncode, 0, second.stdout + second.stderr)
        self.assertEqual(self.snapshot(), snapshot)
        self.assertEqual((self.stage / "applied.txt").read_bytes(), marker)
        self.assertEqual(self.log.read_bytes(), calls)

    def test_rollback_when_config_copy_fails_before_mod_install(self):
        result = self.run_script(cp_fail="config/dailyfortunes/.server.new")
        self.assert_deferred(result, java_called=True)
        self.assertIn("Previous files restored", result.stdout)
        self.assertEqual((self.stage / "before-activation/automodpack-server.json").read_bytes(),
                         self.initial["automodpack/automodpack-server.json"])
        self.assertFalse((self.stage / "activation.pending").exists())

    def test_rollback_when_sync_copy_fails_after_config_install(self):
        result = self.run_script(cp_fail="automodpack/.dailyfortunes.new")
        self.assert_deferred(result, java_called=True)
        self.assertIn("Previous files restored", result.stdout)
        self.assertFalse((self.stage / "activation.pending").exists())

    def test_rollback_when_jar_copy_fails_after_both_configs_install(self):
        result = self.run_script(cp_fail="mods/.dailyfortunes.new")
        self.assert_deferred(result, java_called=True)
        self.assertIn("Previous files restored", result.stdout)
        self.assertFalse((self.stage / "activation.pending").exists())

    def test_rollback_when_marker_publish_fails_after_jar_install(self):
        result = self.run_script(mv_fail=bash_path(self.stage / "applied.txt"))
        self.assert_deferred(result, java_called=True)
        self.assertIn("Previous files restored", result.stdout)
        self.assertFalse((self.stage / "activation.pending").exists())
        self.assertFalse((self.stage / ".applied.tmp").exists())


if __name__ == "__main__":
    unittest.main(verbosity=2)
