"""Run SDK-authenticated Android acceptance; never print credentials or device output."""

import argparse
import json
import re
import signal
import subprocess
import sys
from uuid import uuid4

from google.cloud import firestore
from google.cloud.firestore_v1.base_query import FieldFilter

PACKAGE = "com.yoshiki.lifeagent"


def command(args, *, data=None, timeout=60):
    result = subprocess.run(
        args, input=data, capture_output=True, timeout=timeout, check=False
    )
    if result.returncode:
        raise RuntimeError(f"{args[0]} failed (output withheld)")
    return result.stdout


def cleanup(db, uid, run_id):
    # A shared account may contain concurrent runs: UID alone is never a deletion boundary.
    prefix = f"gcp-check-{run_id}"
    deleted = 0
    for goal in (
        db.collection("goals").where(filter=FieldFilter("owner_id", "==", uid)).stream()
    ):
        value = goal.to_dict()
        if value.get("owner_id") == uid and value.get("title") == prefix:
            db.recursive_delete(goal.reference)
            deleted += 1
    return deleted


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", default="lifeagent-505614")
    parser.add_argument("--secret", default="lifeagent-gcp-check-credentials")
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--service", default="lifeagent-core-api")
    parser.add_argument("--region", default="asia-northeast1")
    parser.add_argument("--sha", required=True)
    parser.add_argument("--pr-head-sha", default="not-applicable")
    parser.add_argument("--cleanup-run-id")
    args = parser.parse_args()
    run_id = args.cleanup_run_id or uuid4().hex
    if not re.fullmatch(r"[a-f0-9]{32}", run_id):
        parser.error("run id must be 32 lowercase hex characters")
    # Emit the recovery identifier before remote writes; no account information is emitted.
    print(f"Android GCP check: sha={args.sha} run_id={run_id}", flush=True)
    credentials = json.loads(
        command(
            [
                "gcloud",
                "secrets",
                "versions",
                "access",
                "latest",
                f"--secret={args.secret}",
                f"--project={args.project}",
            ]
        )
    )
    if not all(
        isinstance(credentials.get(key), str) and credentials[key]
        for key in ("email", "password", "uid")
    ):
        raise RuntimeError("Secret requires email/password/uid strings")
    db = firestore.Client(project=args.project)
    adb = ["adb", "-s", args.serial]
    device_ready = False
    passed = False
    metadata = {}
    try:
        if args.cleanup_run_id:
            passed = True
            return
        avd = command(adb + ["emu", "avd", "name"]).decode().splitlines()[0]
        if avd != "LifeAgent_GCP_Check":
            raise RuntimeError("Use only the dedicated LifeAgent_GCP_Check AVD")
        device_ready = True
        # This dedicated AVD is disposable; prevent stale credentials and earlier tests being used.
        command(adb + ["shell", "pm", "clear", PACKAGE])
        service = json.loads(
            command(
                [
                    "gcloud",
                    "run",
                    "services",
                    "describe",
                    args.service,
                    f"--project={args.project}",
                    f"--region={args.region}",
                    "--format=json",
                ]
            )
        )
        metadata = {
            "sha": args.sha,
            "pr_head_sha": args.pr_head_sha,
            "run_id": run_id,
            "url": service["status"]["url"],
            "traffic": service["status"].get("traffic", []),
            "ai": "real",
            "backend_scope": "currently deployed; PR backend is not verified",
        }
        print(json.dumps(metadata), flush=True)
        payload = {**credentials, "run_id": run_id, "url": metadata["url"]}
        command(
            adb
            + [
                "shell",
                "run-as",
                PACKAGE,
                "sh",
                "-c",
                "'mkdir -p files && cat > files/gcp-account-check.json'",
            ],
            data=json.dumps(payload).encode(),
        )
        result = command(
            adb
            + [
                "shell",
                "am",
                "instrument",
                "-w",
                "-e",
                "gcpAccountCheck",
                "true",
                "-e",
                "class",
                "com.yoshiki.lifeagent.GcpAccountCheckTest",
                f"{PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner",
            ],
            timeout=600,
        )
        if b"OK (1 test)" not in result or b"FAILURES" in result:
            raise RuntimeError("Android instrumentation failed (output withheld)")
        passed = True
    finally:
        # Cancellation reaches here; SIGKILL/runner loss needs --cleanup-run-id recovery.
        try:
            if device_ready:
                try:
                    command(adb + ["shell", "am", "force-stop", PACKAGE])
                finally:
                    command(adb + ["shell", "pm", "clear", PACKAGE])
        finally:
            try:
                deleted = cleanup(db, credentials["uid"], run_id)
                print(f"cleanup run_id={run_id}: deleted={deleted}", flush=True)
            finally:
                db.close()
        if passed:
            print(
                "Android GCP check passed; test data and device credentials removed",
                flush=True,
            )


if __name__ == "__main__":
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(143))
    try:
        main()
    except Exception:
        # SDK, HTTP and instrumentation exceptions can contain passwords or tokens.
        print(
            "Android GCP check failed; details withheld. Use run_id for cleanup/recovery.",
            file=sys.stderr,
        )
        sys.exit(1)
