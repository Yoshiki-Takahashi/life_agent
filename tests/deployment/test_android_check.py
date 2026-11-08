"""Deletion boundary: shared UID is insufficient to select another run's data."""

import importlib.util
import json
import subprocess
import sys
from pathlib import Path
from types import SimpleNamespace

import pytest

spec = importlib.util.spec_from_file_location(
    "android_check", Path(__file__).parents[2] / "scripts/gcp/check-account-android.py"
)
check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check)


def test_cleanup_leaves_other_runs_and_other_owners():
    run = "a" * 32
    docs = [
        {"owner_id": "check", "title": f"gcp-check-{run}"},
        {"owner_id": "check", "title": f"gcp-check-{run}-other"},
        {"owner_id": "check", "title": "gcp-check-" + "b" * 32},
        {"owner_id": "owner", "title": f"gcp-check-{run}"},
    ]
    deleted = []

    class Collection:
        def where(self, *, filter):
            assert filter.field_path == "owner_id" and filter.value == "check"
            return self

        def stream(self):
            return [
                SimpleNamespace(to_dict=lambda d=d: d, reference=i)
                for i, d in enumerate(docs)
            ]

    db = SimpleNamespace(
        collection=lambda _: Collection(), recursive_delete=deleted.append
    )
    assert check.cleanup(db, "check", run) == 1
    assert deleted == [0]


@pytest.mark.parametrize("instrument_failure", [False, True, "timeout"])
def test_device_and_run_cleanup_even_on_failure(
    monkeypatch, capsys, instrument_failure
):
    secret = "must-never-appear-in-logs"
    calls = []
    cleaned = []
    monkeypatch.setattr(sys, "argv", ["check", "--sha", "tested-sha"])
    monkeypatch.setattr(
        check.firestore, "Client", lambda **_: SimpleNamespace(close=lambda: None)
    )
    monkeypatch.setattr(
        check, "cleanup", lambda db, uid, run_id: cleaned.append((uid, run_id)) or 1
    )

    def run(args, *, data=None, timeout=60):
        calls.append(args)
        if "secrets" in args:
            return json.dumps(
                {
                    "email": "check@example.invalid",
                    "password": secret,
                    "uid": "check-uid",
                }
            ).encode()
        if "avd" in args:
            return b"LifeAgent_GCP_Check\nOK\n"
        if "describe" in args:
            return json.dumps({"status": {"url": "https://example.test"}}).encode()
        if "instrument" in args:
            if instrument_failure == "timeout":
                raise subprocess.TimeoutExpired(args, timeout, output=secret.encode())
            return (
                secret + ("FAILURES!!!" if instrument_failure else "OK (1 test)")
            ).encode()
        if data:
            assert json.loads(data)["password"] == secret
        return b"Success"

    monkeypatch.setattr(check, "command", run)
    if instrument_failure:
        with pytest.raises((RuntimeError, subprocess.TimeoutExpired)):
            check.main()
    else:
        check.main()
    assert len(cleaned) == 1 and cleaned[0][0] == "check-uid"
    assert (
        calls.count(
            ["adb", "-s", "emulator-5554", "shell", "pm", "clear", check.PACKAGE]
        )
        == 2
    )
    output = capsys.readouterr()
    assert secret not in output.out + output.err
