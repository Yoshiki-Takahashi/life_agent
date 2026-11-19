import logging
from datetime import UTC, datetime, time, timedelta, timezone
from email.utils import format_datetime

from fastapi import HTTPException
from google.api_core.exceptions import GoogleAPICallError, RetryError
from google.cloud import firestore
from pydantic import ValidationError

from life_agent_core.database import document, read_in_transaction, request_key, run_transaction
from life_agent_core.models import AIDailyUsage, now

AI_USAGE_COLLECTION = "ai_daily_usage"
DAILY_AI_LIMIT = 20
JAPAN_TIME = timezone(timedelta(hours=9), "JST")


def reserve_ai_call(db: firestore.Client, user_id: str) -> None:
    local_date = now().astimezone(JAPAN_TIME).date()
    owner_key = request_key(user_id)
    ref = db.collection(AI_USAGE_COLLECTION).document(f"{owner_key}_{local_date.isoformat()}")

    def reserve(transaction):
        snapshot = read_in_transaction(ref, transaction)
        usage = (
            AIDailyUsage.model_validate(snapshot.to_dict())
            if snapshot.exists
            else AIDailyUsage(owner_key=owner_key, local_date=local_date)
        )
        if usage.count >= DAILY_AI_LIMIT:
            return False
        usage.count += 1
        transaction.set(ref, document(usage))
        return True

    try:
        accepted = run_transaction(db, reserve)
    except (GoogleAPICallError, RetryError, ValidationError) as error:
        logging.getLogger(__name__).warning("AI usage reservation failed: %s", type(error).__name__)
        raise HTTPException(
            status_code=503,
            detail="AI利用回数を確認できませんでした。時間をおいて再試行してください",
        ) from error
    if not accepted:
        reset = datetime.combine(local_date + timedelta(days=1), time.min, JAPAN_TIME)
        raise HTTPException(
            status_code=429,
            detail="AIの利用上限（1日20回）に達しました。日本時間の翌日0時以降に利用できます",
            headers={"Retry-After": format_datetime(reset.astimezone(UTC), usegmt=True)},
        )
