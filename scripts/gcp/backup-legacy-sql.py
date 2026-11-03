"""Local-only SQL dump and restore rehearsal. Requires a loopback SQL Auth Proxy."""

import argparse
import hashlib
import os
import secrets
import subprocess
import time
from pathlib import Path


def run(args, **kwargs):
    return subprocess.run(args, check=True, **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    if not args.output.expanduser().is_absolute():
        parser.error("Choose an absolute local path")
    target = args.output.expanduser().resolve()
    if target.is_relative_to(root) or not target.is_absolute():
        parser.error("Choose a private local path outside the repository")
    target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    if target.parent.stat().st_mode & 0o077:
        parser.error("Backup directory must have mode 700")
    start = time.monotonic()
    password = run(
        [
            "gcloud",
            "secrets",
            "versions",
            "access",
            "2",
            "--secret=lifeagent-db-password",
            "--project=lifeagent-505614",
        ],
        capture_output=True,
        text=True,
    ).stdout.strip()
    env = {**os.environ, "PGPASSWORD": password}
    # Password travels through the environment, never argv, files, or logs.
    with target.open("xb") as backup:
        target.chmod(0o600)
        run(
            [
                "docker",
                "run",
                "--rm",
                "-e",
                "PGPASSWORD",
                "postgres:17",
                "pg_dump",
                "--host=host.docker.internal",
                "--port=15438",
                "--username=lifeagent_app",
                "--dbname=lifeagent",
                "--format=custom",
                "--no-owner",
                "--no-acl",
            ],
            env=env,
            stdout=backup,
        )
    run(
        ["docker", "run", "--rm", "-i", "postgres:17", "pg_restore", "--list"],
        stdin=target.open("rb"),
        stdout=subprocess.DEVNULL,
    )
    name = "lifeagent-restore-" + secrets.token_hex(4)
    try:
        run(
            [
                "docker",
                "run",
                "--detach",
                "--name",
                name,
                "--network=none",
                "-e",
                "POSTGRES_HOST_AUTH_METHOD=trust",
                "postgres:17",
            ],
            stdout=subprocess.DEVNULL,
        )
        for _ in range(100):
            # The image first starts a socket-only initialization server.
            # Wait for TCP so restore cannot race that temporary server's shutdown.
            result = subprocess.run(
                ["docker", "exec", name, "pg_isready", "-h", "127.0.0.1", "-U", "postgres"],
                capture_output=True,
                check=False,
            )
            if result.returncode == 0:
                break
            time.sleep(0.1)
        else:
            raise RuntimeError("Local restore database did not start")
        with target.open("rb") as backup:
            run(
                [
                    "docker",
                    "exec",
                    "-i",
                    name,
                    "pg_restore",
                    "-U",
                    "postgres",
                    "-d",
                    "postgres",
                    "--no-owner",
                    "--no-acl",
                    "--exit-on-error",
                ],
                stdin=backup,
            )
        run(
            [
                "docker",
                "exec",
                name,
                "psql",
                "-U",
                "postgres",
                "-d",
                "postgres",
                "-Atc",
                (
                    "SELECT 'goals=' || count(*) FROM goals UNION ALL "
                    "SELECT 'progress_logs=' || count(*) FROM progress_logs UNION ALL "
                    "SELECT 'replan_proposals=' || count(*) FROM replan_proposals"
                ),
            ]
        )
    finally:
        run(["docker", "rm", "--force", "--volumes", name], stdout=subprocess.DEVNULL)
    checksum = hashlib.sha256(target.read_bytes()).hexdigest()
    target.with_suffix(target.suffix + ".sha256").write_text(checksum + "\n")
    target.with_suffix(target.suffix + ".sha256").chmod(0o600)
    print(
        f"Dump and local restore passed: {target}; {target.stat().st_size} bytes; "
        f"{time.monotonic() - start:.1f}s; SHA256 {checksum}"
    )


if __name__ == "__main__":
    main()
