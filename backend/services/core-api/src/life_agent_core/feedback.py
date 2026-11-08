"""GitHub owns feedback text; Firestore stores only delivery receipts."""

import hashlib
import json
from datetime import UTC, datetime, timedelta
from typing import Annotated, Literal
from uuid import UUID

import httpx
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, ConfigDict, Field, StringConstraints

from life_agent_core.auth import CurrentUserId
from life_agent_core.config import get_settings
from life_agent_core.database import (
    Database,
    document,
    read_in_transaction,
    request_key,
    run_transaction,
)
from life_agent_core.models import StoredModel

router = APIRouter(prefix="/api/v1/feedback", tags=["feedback"])
Text = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1)]


class FeedbackCreate(BaseModel):
    model_config = ConfigDict(extra="forbid")
    client_request_id: UUID
    body: Annotated[Text, Field(max_length=2000)]
    app_version: Annotated[Text, Field(max_length=100, pattern=r"^[^\r\n]+$")]
    screen: Literal[
        "home",
        "create",
        "questions",
        "preview",
        "goal-detail",
        "review",
        "coach",
        "coach-confirm",
        "feedback",
    ]


class FeedbackResult(BaseModel):
    issue_number: int = Field(gt=0)
    issue_url: str


class FeedbackReceipt(StoredModel):
    schema_version: Literal[1] = 1
    payload_hash: str
    repository: str
    status: Literal["sending", "retryable", "sent"] = "sending"
    created_at: datetime
    issue_number: int | None = None


def unavailable(message: str, *, unknown: bool = True):
    return HTTPException(status_code=503, detail={"message": message, "result_unknown": unknown})


class GitHubFeedback:
    def __init__(self):
        self.settings = get_settings()

    def client(self):
        token = self.settings.feedback_github_token
        if token is None or not token.get_secret_value().strip():
            raise unavailable("フィードバックの送信先が未設定です", unknown=False)
        return httpx.Client(
            base_url="https://api.github.com",
            headers={
                "Authorization": f"Bearer {token.get_secret_value()}",
                "Accept": "application/vnd.github+json",
                "X-GitHub-Api-Version": "2026-03-10",
            },
            timeout=5,
        )

    @staticmethod
    def issue_number(data: dict) -> int:
        number = data["number"]
        if type(number) is not int or number <= 0 or "pull_request" in data:
            raise ValueError("Invalid GitHub issue")
        return number

    def find(self, client, repository: str, marker: str, since: datetime) -> int | None:
        # List API, not search: no search-index delay, and includes closed issues.
        # Bound the work per request; an incomplete scan never authorizes another POST.
        for page in range(1, 6):
            response = client.get(
                f"/repos/{repository}/issues",
                params={
                    "state": "all",
                    "since": (since - timedelta(seconds=1)).replace(microsecond=0).isoformat(),
                    "per_page": 100,
                    "page": page,
                },
            )
            response.raise_for_status()
            issues = response.json()
            if not isinstance(issues, list):
                raise ValueError("Invalid GitHub issues")
            for issue in issues:
                if "pull_request" not in issue and (issue.get("body") or "").endswith(marker):
                    return self.issue_number(issue)
            if "next" not in response.links:
                return None
        return None


GitHub = Annotated[GitHubFeedback, Depends(GitHubFeedback)]


@router.post("", response_model=FeedbackResult)
def send_feedback(payload: FeedbackCreate, user_id: CurrentUserId, db: Database, github: GitHub):
    # Validate credentials before reserving an ID, so missing configuration is retryable.
    with github.client() as client:
        key = request_key(f"{user_id}:{payload.client_request_id}")
        ref = db.collection("feedback_receipts").document(key)
        fingerprint = hashlib.sha256(
            json.dumps(
                payload.model_dump(mode="json"),
                sort_keys=True,
                ensure_ascii=False,
            ).encode()
        ).hexdigest()

        def reserve(transaction):
            snapshot = read_in_transaction(ref, transaction)
            if snapshot.exists:
                receipt = FeedbackReceipt.model_validate(snapshot.to_dict())
                if receipt.payload_hash != fingerprint:
                    raise HTTPException(
                        status_code=409,
                        detail={
                            "message": "同じ送信IDの内容を変更できません",
                            "result_unknown": True,
                        },
                    )
                if receipt.status != "retryable":
                    return receipt, False
                receipt.status = "sending"
            else:
                receipt = FeedbackReceipt(
                    payload_hash=fingerprint,
                    repository=github.settings.feedback_github_repository,
                    created_at=datetime.now(UTC),
                )
            transaction.set(ref, document(receipt))
            return receipt, True

        receipt, should_create = run_transaction(db, reserve)
        repository = receipt.repository
        number = receipt.issue_number
        marker = f"<!-- lifeagent-feedback:{key} -->"
        if receipt.status != "sent":
            try:
                if should_create:
                    # No external call is made inside a retried Firestore transaction.
                    response = client.post(
                        f"/repos/{repository}/issues",
                        json={
                            "title": f"[Feedback] {payload.body.splitlines()[0][:100]}",
                            "body": (
                                f"## 改善メモ\n\n{payload.body}\n\n## 送信情報\n\n"
                                f"- 送信日時: {receipt.created_at.isoformat()}\n"
                                f"- アプリ: {payload.app_version}\n"
                                f"- 画面: {payload.screen}\n\n{marker}"
                            ),
                            "labels": ["feedback", "needs-triage"],
                        },
                    )
                    if response.status_code in (400, 401, 403, 404, 410, 422, 429):
                        # Explicit rejection: GitHub did not create an issue.
                        ref.update({"status": "retryable"})
                        raise unavailable(
                            "GitHubへ送信できませんでした。設定や権限を確認して再試行してください",
                            unknown=False,
                        )
                    response.raise_for_status()
                    number = github.issue_number(response.json())
                else:
                    number = github.find(client, repository, marker, receipt.created_at)
                if number is None:
                    raise unavailable(
                        "送信結果を確認できません。同じ内容で再確認してください。"
                        "解消しない場合はGitHubのIssueを確認してください"
                    )
            except (httpx.HTTPError, ValueError, KeyError, TypeError) as from_error:
                raise unavailable(
                    "送信結果を確認できません。同じ内容で再確認してください"
                ) from from_error
            ref.update({"status": "sent", "issue_number": number})
        return FeedbackResult(
            issue_number=number, issue_url=f"https://github.com/{repository}/issues/{number}"
        )
