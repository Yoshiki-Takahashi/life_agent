"""Explicit Android cloud acceptance using a disposable Firebase account."""

import argparse
import json
import secrets
import subprocess
from pathlib import Path
from uuid import uuid4

import httpx
from google.cloud import firestore
from google.cloud.firestore_v1.base_query import FieldFilter
from google.oauth2.credentials import Credentials


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--firebase-sdk", type=Path, required=True)
    parser.add_argument("--serial", default="emulator-5554")
    args = parser.parse_args()
    result = json.loads(args.firebase_sdk.read_text())["result"]
    config = json.loads(result if isinstance(result, str) else result["fileContents"])
    api_key = config["client"][0]["api_key"][0]["current_key"]
    adb = [str(Path.home() / "Library/Android/sdk/platform-tools/adb"), "-s", args.serial]
    access_token = subprocess.check_output(
        ["gcloud", "auth", "print-access-token"], text=True
    ).strip()
    db = firestore.Client(project="lifeagent-505614", credentials=Credentials(access_token))
    user = None
    with httpx.Client(timeout=60) as client:
        try:
            response = client.post(
                "https://identitytoolkit.googleapis.com/v1/accounts:signUp",
                params={"key": api_key},
                json={
                    "email": f"firestore-android-check-{uuid4().hex}@example.com",
                    "password": secrets.token_urlsafe(32),
                    "returnSecureToken": True,
                },
            )
            assert response.status_code == 200, f"Firebase signup HTTP {response.status_code}"
            user = response.json()
            direct = client.get(
                "https://firestore.googleapis.com/v1/projects/lifeagent-505614/"
                "databases/(default)/documents/goals/client-access-check",
                headers={"Authorization": "Bearer " + user["idToken"]},
            )
            assert direct.status_code == 403, "Firebase clients must not access Firestore directly"
            check = {
                "uid": user["localId"],
                "token": user["idToken"],
                "url": "https://lifeagent-core-api-pwcjpwxpfa-an.a.run.app/",
            }
            subprocess.run(
                adb
                + [
                    "shell",
                    "run-as",
                    "com.yoshiki.lifeagent",
                    "sh",
                    "-c",
                    "'mkdir -p files && cat > files/cloud-persistence-check.json'",
                ],
                input=json.dumps(check),
                text=True,
                capture_output=True,
                check=True,
            )
            # Neither argv nor instrumentation output contains the ID Token.
            result = subprocess.run(
                adb
                + [
                    "shell",
                    "am",
                    "instrument",
                    "-w",
                    "-e",
                    "cloudPersistenceE2E",
                    "true",
                    "-e",
                    "class",
                    "com.yoshiki.lifeagent.CloudPersistenceTest",
                    "com.yoshiki.lifeagent.test/androidx.test.runner.AndroidJUnitRunner",
                ],
                check=True,
                capture_output=True,
                text=True,
                timeout=240,
            )
            print(result.stdout)
            assert "OK (1 test)" in result.stdout, "Android cloud acceptance failed"
        finally:
            subprocess.run(
                adb
                + [
                    "shell",
                    "run-as",
                    "com.yoshiki.lifeagent",
                    "rm",
                    "-f",
                    "files/cloud-persistence-check.json",
                ],
                capture_output=True,
                check=False,
            )
            if user is not None:
                goals = db.collection("goals").where(
                    filter=FieldFilter("owner_id", "==", user["localId"])
                )
                for goal in goals.stream():
                    if goal.to_dict()["owner_id"] == user["localId"]:
                        db.recursive_delete(goal.reference)
                response = client.post(
                    "https://identitytoolkit.googleapis.com/v1/accounts:delete",
                    params={"key": api_key},
                    json={"idToken": user["idToken"]},
                )
                assert response.status_code == 200, "Test account cleanup failed"
            db.close()
    print("Android cloud acceptance and test-data cleanup: passed")


if __name__ == "__main__":
    main()
