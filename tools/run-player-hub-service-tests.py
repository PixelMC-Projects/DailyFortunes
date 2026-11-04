"""Own one disposable loopback MariaDB; never reads server config, credentials or databases."""
from pathlib import Path
import os
import socket
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parent.parent
BIN = ROOT.parent / "MemorialTitles/tools/mariadb-test/mariadb-11.4.9-winx64/bin"
RUN = ROOT / "build/hub-tests" / uuid.uuid4().hex[:12]
PORT = 43317


def main():
    with socket.socket() as probe:
        if probe.connect_ex(("127.0.0.1", PORT)) == 0:
            raise RuntimeError("Isolated test port is occupied; refusing to reuse an unknown database")
    RUN.mkdir(parents=True)
    data = RUN / "data"
    env = os.environ.copy()
    env["JAVA_HOME"] = r"C:\Program Files\Java\jdk-17"
    env["PATH"] = env["JAVA_HOME"] + r"\bin;" + env["PATH"]
    env["JAVA_TOOL_OPTIONS"] = r"-Dfile.encoding=UTF-8 -Djdk.net.unixdomain.tmpdir=D:\Gradle\.tmp"
    env["DF_TEST_JDBC"] = "jdbc:mariadb://127.0.0.1:43317/"
    with (RUN / "bootstrap.log").open("w", encoding="utf-8") as log:
        subprocess.run([str(BIN / "mariadb-install-db.exe"), "--datadir=" + str(data),
                        "--port=" + str(PORT), "--silent"], stdout=log, stderr=subprocess.STDOUT,
                       check=True, creationflags=subprocess.CREATE_NO_WINDOW)
    with (RUN / "database.log").open("w", encoding="utf-8") as log:
        database = subprocess.Popen([str(BIN / "mariadbd.exe"), "--no-defaults", "--datadir=" + str(data),
                                     "--port=" + str(PORT), "--bind-address=127.0.0.1", "--skip-name-resolve", "--console"],
                                    stdout=log, stderr=subprocess.STDOUT, creationflags=subprocess.CREATE_NO_WINDOW)
        try:
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                if database.poll() is not None:
                    raise RuntimeError("Disposable MariaDB failed; inspect build/hub-tests log")
                with socket.socket() as probe:
                    if probe.connect_ex(("127.0.0.1", PORT)) == 0:
                        break
                time.sleep(.2)
            else:
                raise TimeoutError("Disposable MariaDB startup timed out")
            with (RUN / "build.log").open("w", encoding="utf-8") as build_log:
                result = subprocess.run([str(ROOT / "gradlew.bat"), "serviceChecks", "serviceRegressionChecks", "packagedStorageChecks", "playerHubChecks", "--offline", "--console=plain"],
                                        cwd=ROOT, env=env, stdout=build_log, stderr=subprocess.STDOUT,
                                        creationflags=subprocess.CREATE_NO_WINDOW)
            if result.returncode:
                raise SystemExit(result.returncode)
        finally:
            # This handle is solely the process created above, never another service/process.
            database.terminate()
            try:
                database.wait(timeout=10)
            except subprocess.TimeoutExpired:
                database.kill()
                database.wait(timeout=5)
    print("ISOLATED_DB_LOGS", RUN)


if __name__ == "__main__":
    main()
