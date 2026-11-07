import re
from datetime import UTC, datetime
from functools import lru_cache
from typing import Annotated, Protocol

from fastapi import APIRouter, Depends, HTTPException
from google.cloud.firestore_v1.base_query import FieldFilter

from life_agent_core.ai_service import (
    AIUnavailableError,
    HttpCoach,
)
from life_agent_core.auth import CurrentUserId
from life_agent_core.config import get_settings
from life_agent_core.database import Database, decode
from life_agent_core.models import (
    Goal,
)
from life_agent_core.schemas import (
    CoachAIRequest,
    CoachGoal,
    CoachMetric,
    CoachReply,
    CoachRequest,
)

router = APIRouter()


class Coach(Protocol):
    def respond(self, request: CoachAIRequest) -> CoachReply: ...


def validate_reply(reply: CoachReply, request: CoachAIRequest) -> CoachReply:
    if reply.intent == "record":
        if reply.goal_id not in {goal.id for goal in request.goals}:
            raise ValueError("Unknown goal")
        sources = [request.body] + [item.content for item in request.history if item.role == "user"]
        if reply.record_body not in sources:
            raise ValueError("Record body must quote a user message")
    return reply


class FakeCoach:
    def respond(self, request: CoachAIRequest) -> CoachReply:

        # Deliberately conservative: a demo, not a natural-language classifier.
        current = request.body
        source = current
        matches = [
            goal for goal in request.goals if goal.title in current or str(goal.id) in current
        ]
        if not matches:
            matches = [
                goal
                for goal in request.goals
                if any(
                    re.search(r"[0-9]+(?:\.[0-9]+)?\s*" + re.escape(metric.unit), current)
                    for metric in goal.metrics
                )
            ]

        def performed(text: str) -> bool:
            return bool(
                re.search(r"[0-9]+", text)
                and re.search(
                    r"読み終えた|読んだ|完成させた|実施した|トレーニングした|記録して|記録したい",
                    text,
                )
                and not re.search(r"予定|つもり|合計|累計|ない|なかった|[?？]", text)
            )

        if len(matches) == 1:
            goal = matches[0]
            if not performed(source) and current in (goal.title, str(goal.id)):
                source = next(
                    (
                        item.content
                        for item in reversed(request.history)
                        if item.role == "user" and performed(item.content)
                    ),
                    "",
                )
            if performed(source):
                return CoachReply(
                    reply=f"[確認用Fakeの応答]\n「{goal.title}」の記録候補を作ります。まだ保存しません。",
                    intent="record",
                    goal_id=goal.id,
                    record_body=source,
                )
        lines = [
            f"・{goal.title}: "
            + "、".join(
                f"{m.name} {m.current_value:g}/{m.target_value:g}{m.unit}" for m in goal.metrics
            )
            + f"（期限 {goal.target_date}）"
            for goal in request.goals
        ]
        text = (
            "保存済みの全目標を確認しました。\n" + "\n".join(lines)
            if lines
            else "まだ目標がありません。下部の「新規作成」から始められます。"
        )
        if len(matches) != 1 and re.search(r"[0-9]+", current):
            text += "\nどの目標の実績ですか？目標名も添えて教えてください。"
        else:
            text += "\n今日はどのように進めたいですか？"
        return CoachReply(
            reply=("[確認用Fakeの応答]\n" + text)[:2000],
            intent="discuss",
            goal_id=None,
            record_body=None,
        )


@lru_cache
def get_coach() -> Coach:
    settings = get_settings()
    return HttpCoach(settings) if settings.coach_backend == "http" else FakeCoach()


@router.post("/api/v1/coach/messages", response_model=CoachReply)
def coach_message(
    payload: CoachRequest,
    db: Database,
    user_id: CurrentUserId,
    coach: Annotated[Coach, Depends(get_coach)],
) -> CoachReply:
    snapshots = (
        db.collection("goals")
        .where(filter=FieldFilter("owner_id", "==", user_id))
        .order_by("created_at")
        .order_by("id")
        .stream()
    )
    goals = [Goal.model_validate(decode(snapshot.to_dict())) for snapshot in snapshots]
    goals = [goal for goal in goals if goal.status == "active"]
    request = CoachAIRequest(
        body=payload.body,
        history=payload.history,
        as_of_date=datetime.now(UTC).date(),
        goals=[
            CoachGoal(
                id=goal.id,
                title=goal.title,
                target_date=goal.target_date,
                metrics=[
                    CoachMetric(
                        name=m.name,
                        current_value=float(m.current_value),
                        target_value=float(m.target_value),
                        unit=m.unit,
                    )
                    for m in goal.metrics
                    if m.archived_at is None
                ],
            )
            for goal in goals
        ],
    )
    try:
        return validate_reply(CoachReply.model_validate(coach.respond(request)), request)
    except (AIUnavailableError, ValueError, TypeError) as error:
        raise HTTPException(
            status_code=503, detail="返答を作れませんでした。再試行してください"
        ) from error
