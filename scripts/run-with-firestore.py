"""Run a command against an isolated, ephemeral Firestore emulator, never the cloud."""

import argparse
import os
import signal
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
from uuid import uuid4


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command
    if command[:1] == ["--"]:
        command = command[1:]
    if not command:
        parser.error("provide a command after --")
    env = os.environ.copy()
    project = env.get("FIRESTORE_PROJECT_ID", f"demo-lifeagent-{uuid4().hex[:12]}")
    if not project.startswith("demo-"):
        parser.error("FIRESTORE_PROJECT_ID must start with demo-")
    env["FIRESTORE_PROJECT_ID"] = project
    host = env.get("FIRESTORE_EMULATOR_HOST")
    if host:
        if not host.startswith(("127.0.0.1:", "localhost:")):
            parser.error("only a loopback FIRESTORE_EMULATOR_HOST is allowed")
        return subprocess.call(command, env=env)
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    host = f"127.0.0.1:{port}"
    env["FIRESTORE_EMULATOR_HOST"] = host
    with tempfile.TemporaryFile(mode="w+") as log:
        process = subprocess.Popen(
            [
                "gcloud",
                "emulators",
                "firestore",
                "start",
                f"--host-port={host}",
                f"--project={project}",
                "--quiet",
            ],
            stdout=log,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )
        try:
            for _ in range(120):
                if process.poll() is not None:
                    log.seek(0)
                    raise RuntimeError(f"Emulator failed: {log.read()}")
                try:
                    with urllib.request.urlopen(f"http://{host}/", timeout=0.2):
                        pass
                    break
                except urllib.error.HTTPError:
                    break
                except (urllib.error.URLError, TimeoutError):
                    time.sleep(0.25)
            else:
                raise RuntimeError("Firestore emulator did not start")
            return subprocess.call(command, env=env)
        finally:
            try:
                os.killpg(process.pid, signal.SIGTERM)
                process.wait(timeout=10)
            except ProcessLookupError:
                pass
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()


if __name__ == "__main__":
    raise SystemExit(main())
