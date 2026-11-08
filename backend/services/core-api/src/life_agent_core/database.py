import hashlib
import json
import logging
import os
from datetime import date, datetime
from decimal import Decimal
from functools import lru_cache
from typing import Annotated
from uuid import UUID

from fastapi import Depends, HTTPException
from google.api_core.exceptions import (
    Aborted,
    DeadlineExceeded,
    InternalServerError,
    ServiceUnavailable,
)
from google.api_core.retry import Retry, if_exception_type
from google.cloud import firestore

from life_agent_core.config import get_settings
from life_agent_core.models import Goal, ProgressLog, StoredModel


@lru_cache
def get_db() -> firestore.Client:
    settings = get_settings()
    emulator = settings.firestore_emulator_host
    if os.getenv("K_SERVICE") and emulator:
        raise RuntimeError("Cloud Run must not use the Firestore emulator")
    if emulator and not settings.firestore_project_id.startswith("demo-"):
        raise RuntimeError("The emulator requires an isolated demo- project")
    if not emulator and settings.firestore_project_id.startswith("demo-"):
        raise RuntimeError("Start the Firestore emulator and set FIRESTORE_EMULATOR_HOST")
    if emulator:
        # The Google SDK reads this setting from the environment, not our .env file.
        os.environ["FIRESTORE_EMULATOR_HOST"] = emulator
    return firestore.Client(
        project=settings.firestore_project_id, database=settings.firestore_database_id
    )


Database = Annotated[firestore.Client, Depends(get_db)]
NUMERIC_FIELDS = {"target_value", "current_value", "value"}


def encode(value, field: str = ""):
    if field in NUMERIC_FIELDS:
        scaled = Decimal(str(value)) * 100
        if not scaled.is_finite() or scaled != scaled.to_integral_value():
            raise ValueError("Stored values must have at most two decimal places")
        return int(scaled)
    if isinstance(value, dict):
        return {key: encode(item, key) for key, item in value.items()}
    if isinstance(value, list):
        return [encode(item) for item in value]
    if isinstance(value, UUID):
        return str(value)
    if isinstance(value, date) and not isinstance(value, datetime):
        return value.isoformat()
    return value


def decode(value, field: str = ""):
    if field in NUMERIC_FIELDS:
        return Decimal(value) / 100
    if isinstance(value, dict):
        return {key: decode(item, key) for key, item in value.items()}
    if isinstance(value, list):
        return [decode(item) for item in value]
    return value


def document(model: StoredModel) -> dict:
    data = encode(model.model_dump())
    if isinstance(model, (Goal, ProgressLog)):
        data["schema_version"] = 2
    # Leave room for Firestore field/path overhead below its 1 MiB document limit.
    if len(json.dumps(data, ensure_ascii=False, default=str).encode()) > 900_000:
        raise HTTPException(status_code=500, detail="保存できませんでした。再試行してください")
    return data


def request_key(client_request_id: str) -> str:
    return hashlib.sha256(client_request_id.encode()).hexdigest()


def read_in_transaction(reference: firestore.DocumentReference, transaction: firestore.Transaction):
    # Retrying a read inside the same failed transaction can hold its locks for
    # the SDK's 300s batch-get budget. Let the outer retry start a fresh snapshot.
    try:
        return reference.get(transaction=transaction, retry=None, timeout=10)
    except (DeadlineExceeded, InternalServerError, ServiceUnavailable) as error:
        logging.getLogger(__name__).warning(
            "Restarting Firestore transaction after read %s", type(error).__name__
        )
        raise Aborted("Transient transaction read failure") from error


def run_transaction(db: firestore.Client, operation):
    # The SDK retries commit conflicts, but a pessimistic transaction can also
    # abort or time out while reading. Retry that whole transaction with a fresh snapshot.
    # Emulator contention can keep locks for 30s per attempt. Its verification
    # budget is longer; Cloud Run retains the 45s production retry budget.
    timeout = 180 if get_settings().firestore_emulator_host else 45

    @Retry(predicate=if_exception_type(Aborted), initial=0.05, maximum=1, timeout=timeout)
    def execute():
        try:
            return firestore.transactional(operation)(db.transaction(max_attempts=10))
        except ValueError as error:
            if isinstance(error.__cause__, Aborted):
                # Exhausting the SDK's immediate commit retries still represents
                # contention; apply the bounded backoff before trying afresh.
                raise error.__cause__ from error
            raise

    return execute()
