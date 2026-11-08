"""Explicit operator command: create/rotate the dedicated account and Secret Manager version."""

import argparse
import json
import secrets
import subprocess
import sys

import firebase_admin
from firebase_admin import auth


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", default="lifeagent-505614")
    parser.add_argument("--email", default="lifeagent-gcp-check@example.invalid")
    parser.add_argument("--secret", default="lifeagent-gcp-check-credentials")
    parser.add_argument("--rotate", action="store_true")
    args = parser.parse_args()
    firebase_admin.initialize_app(options={"projectId": args.project})
    password = secrets.token_urlsafe(40)
    try:
        user = auth.get_user_by_email(args.email)
    except auth.UserNotFoundError:
        if args.rotate:
            raise RuntimeError("Account does not exist") from None
        user = auth.create_user(email=args.email, password=password)
    else:
        if not args.rotate:
            raise RuntimeError("Account exists; use --rotate explicitly")
        user = auth.update_user(user.uid, password=password, disabled=False)
    payload = json.dumps(
        {"email": args.email, "password": password, "uid": user.uid}
    ).encode()
    base = ["gcloud", "secrets"]
    exists = (
        subprocess.run(
            base + ["describe", args.secret, f"--project={args.project}"],
            capture_output=True,
        ).returncode
        == 0
    )
    action = (
        ["versions", "add", args.secret]
        if exists
        else ["create", args.secret, "--replication-policy=automatic"]
    )
    result = subprocess.run(
        base + action + [f"--project={args.project}", "--data-file=-"],
        input=payload,
        capture_output=True,
    )
    if result.returncode:
        raise RuntimeError(
            "Secret write failed; retry --rotate after fixing permissions"
        )
    print(
        f"Account and Secret registered. Add UID to CORE_API_ALLOWED_USER_IDS: {user.uid}"
    )


if __name__ == "__main__":
    try:
        main()
    except Exception:
        print(
            "Registration failed (details withheld). Check account/Secret permissions; "
            "recover with --rotate after correcting permissions.",
            file=sys.stderr,
        )
        sys.exit(1)
