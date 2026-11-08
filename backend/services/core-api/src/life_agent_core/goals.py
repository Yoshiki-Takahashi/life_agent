import uuid
from datetime import UTC, date, datetime, timedelta
from functools import lru_cache
from typing import Annotated, Protocol

from fastapi import APIRouter, Depends, HTTPException, status
from google.cloud import firestore
from google.cloud.firestore_v1.base_query import FieldFilter

from life_agent_core.ai_service import AIUnavailableError, InternalAIClient
from life_agent_core.auth import CurrentUserId
from life_agent_core.config import Settings, get_settings
from life_agent_core.database import (
    Database,
    decode,
    document,
    read_in_transaction,
    run_transaction,
)
from life_agent_core.models import Goal, GoalStatus, Metric, Milestone, ProgressLog
from life_agent_core.schemas import (
    GoalConfirmRequest,
    GoalInput,
    GoalPlan,
    GoalPreviewRequest,
    GoalQuestions,
    GoalResponse,
    GoalSummary,
    MetricDraft,
    MetricResponse,
    MilestoneDraft,
    MilestoneResponse,
    ProgressLogResponse,
)

router = APIRouter()


class GoalPlanner(Protocol):
    def generate(self, payload: GoalPreviewRequest) -> GoalPlan: ...

    def questions(self, payload: GoalInput) -> GoalQuestions: ...


class FakeGoalPlanner:
    def generate(self, payload: GoalPreviewRequest) -> GoalPlan:
        return create_fake_plan(payload)

    def questions(self, payload: GoalInput) -> GoalQuestions:
        return GoalQuestions(
            questions=[
                f"「{payload.title}」で取り組んだ時間も記録したいですか？",
                "途中で成果を確認する節目を多めに設けたいですか？",
                "すでに取り組みを始めていますか？",
            ]
        )


def create_fake_plan(payload: GoalPreviewRequest, start_date: date | None = None) -> GoalPlan:
    start = start_date or date.today()
    text = f"{payload.title} {payload.description or ''}".lower()

    if any(keyword in text for keyword in ("読書", "本", "読む", "book", "read")):
        metric = MetricDraft(name="読了冊数", target_value=12, unit="冊")
        titles = ("読む本を決める", "目標の半分を読み終える", "目標冊数を読み終える")
    elif any(keyword in text for keyword in ("アプリ", "開発", "実装", "app", "develop")):
        metric = MetricDraft(name="完成した成果物", target_value=1, unit="件")
        titles = ("要件を決める", "主要機能を実装する", "動作確認して完成させる")
    elif any(
        keyword in text for keyword in ("筋トレ", "トレーニング", "運動", "workout", "training")
    ):
        metric = MetricDraft(name="トレーニング回数", target_value=12, unit="回")
        titles = ("メニューと頻度を決める", "計画回数の半分を実施する", "目標回数を達成する")
    else:
        metric = MetricDraft(name="達成率", target_value=100, unit="%")
        titles = ("実行内容を決める", "中間目標を達成する", "Goalを達成する")

    if "体脂肪" in text or "体重" in text:
        metric = MetricDraft(
            name="体脂肪率" if "体脂肪" in text else "体重",
            target_value=15 if "体脂肪" in text else 60,
            unit="%" if "体脂肪" in text else "kg",
            kind="measurement",
        )
    elif "習慣" in text or "ジム" in text:
        metric = MetricDraft(
            name="ジム" if "ジム" in text else "習慣の実行",
            target_value=12,
            unit="日",
            kind="occurrence",
        )
    elif "旅行" in text:
        metric = MetricDraft(
            name="旅行準備",
            target_value=3,
            unit="項目",
            kind="checklist",
            checklist_items=["日付を決める", "宿を決める", "行き先を決める"],
        )
    if not payload.metric_types_enabled:
        metric = (
            MetricDraft(name="完了した取り組み", target_value=12, unit="回")
            if metric.kind != "cumulative"
            else metric
        )
    metrics = [metric]
    if any(item.answer and "時間も記録" in item.question for item in payload.answers):
        metrics.append(MetricDraft(name="取り組んだ時間", target_value=60, unit="分"))
    if any(item.answer and "節目を多め" in item.question for item in payload.answers):
        titles = (titles[0], "最初の成果を確認する", titles[1], "仕上げの準備をする", titles[2])
    if any(item.answer and "始めていますか" in item.question for item in payload.answers):
        titles = ("これまでの取り組みを確認する", *titles[1:])
    days = max((payload.target_date - start).days, 0)
    dates = [start + timedelta(days=days * (i + 1) // len(titles)) for i in range(len(titles))]
    milestones = [
        MilestoneDraft(title=title, target_date=target_date)
        for title, target_date in zip(titles, dates, strict=True)
    ]
    return GoalPlan(
        title=payload.title,
        description=payload.description,
        target_date=payload.target_date,
        metrics=metrics,
        milestones=milestones,
    )


class HttpGoalPlanner:
    def __init__(self, settings: Settings):
        self.client = InternalAIClient(settings)

    def generate(self, payload: GoalPreviewRequest) -> GoalPlan:
        return self.client.generate("goal-plans", payload.model_dump(mode="json"), GoalPlan)

    def questions(self, payload: GoalInput) -> GoalQuestions:
        return self.client.generate(
            "goal-questions", payload.model_dump(mode="json"), GoalQuestions
        )


@lru_cache
def get_goal_planner() -> GoalPlanner:
    settings = get_settings()
    if settings.goal_planner_backend == "http":
        return HttpGoalPlanner(settings)
    return FakeGoalPlanner()


Planner = Annotated[GoalPlanner, Depends(get_goal_planner)]


@router.post("/api/v1/goals/questions", response_model=GoalQuestions)
def goal_questions(payload: GoalInput, planner: Planner, user_id: CurrentUserId) -> GoalQuestions:
    try:
        return GoalQuestions.model_validate(planner.questions(payload))
    except (AIUnavailableError, ValueError, TypeError) as error:
        raise HTTPException(
            status_code=503,
            detail="質問を生成できませんでした。入力内容を保持して再試行してください",
        ) from error


@router.post("/api/v1/goals/preview", response_model=GoalPlan)
def preview_goal(payload: GoalPreviewRequest, planner: Planner, user_id: CurrentUserId) -> GoalPlan:
    try:
        plan = planner.generate(payload)
        if not payload.metric_types_enabled and any(m.kind != "cumulative" for m in plan.metrics):
            raise AIUnavailableError("Legacy client requires cumulative metrics")
        return plan
    except AIUnavailableError as error:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="計画を生成できませんでした。入力内容を保持して再試行してください",
        ) from error


@router.post(
    "/api/v1/goals/confirm", response_model=GoalResponse, status_code=status.HTTP_201_CREATED
)
def confirm_goal(payload: GoalConfirmRequest, db: Database, user_id: CurrentUserId) -> GoalResponse:
    goal = Goal(
        owner_id=user_id,
        title=payload.title,
        description=payload.description,
        target_date=payload.target_date,
        metrics=[
            Metric(**item.model_dump(), position=index)
            for index, item in enumerate(payload.metrics)
        ],
        milestones=[
            Milestone(title=item.title, target_date=item.target_date, position=index)
            for index, item in enumerate(payload.milestones)
        ],
    )
    db.collection("goals").document(str(goal.id)).create(document(goal))
    return goal_response(goal)


@router.get("/api/v1/goals", response_model=list[GoalSummary])
def list_goals(
    db: Database, user_id: CurrentUserId, status: GoalStatus | None = None
) -> list[GoalSummary]:
    snapshots = (
        db.collection("goals")
        .where(filter=FieldFilter("owner_id", "==", user_id))
        .order_by("updated_at", direction=firestore.Query.DESCENDING)
        .order_by("id", direction=firestore.Query.DESCENDING)
        .stream()
    )
    goals = [Goal.model_validate(decode(snapshot.to_dict())) for snapshot in snapshots]
    return [
        GoalSummary(
            id=goal.id,
            title=goal.title,
            target_date=goal.target_date,
            status=goal.status,
            metric_count=sum(1 for metric in goal.metrics if metric.archived_at is None),
            milestone_count=len(goal.milestones),
            created_at=goal.created_at,
            updated_at=goal.updated_at,
        )
        for goal in goals
        if status is None or goal.status == status
    ]


@router.post("/api/v1/goals/{goal_id}/complete", response_model=GoalResponse)
def complete_goal(goal_id: uuid.UUID, db: Database, user_id: CurrentUserId) -> GoalResponse:
    def persist(transaction):
        goal = load_goal(goal_id, db, user_id, transaction)
        if goal.status == GoalStatus.COMPLETED:
            return
        transaction.update(
            db.collection("goals").document(str(goal_id)),
            {
                "status": GoalStatus.COMPLETED.value,
                "updated_at": datetime.now(UTC),
            },
        )

    run_transaction(db, persist)
    return goal_response(load_goal_with_details(goal_id, db, user_id))


def require_active(goal: Goal):
    if goal.status != GoalStatus.ACTIVE:
        raise HTTPException(status_code=409, detail="完了済みの目標は変更できません")


@router.get("/api/v1/goals/{goal_id}", response_model=GoalResponse)
def get_goal(goal_id: uuid.UUID, db: Database, user_id: CurrentUserId) -> GoalResponse:
    return goal_response(load_goal_with_details(goal_id, db, user_id))


def load_goal(goal_id: uuid.UUID, db: firestore.Client, user_id: str, transaction=None) -> Goal:
    reference = db.collection("goals").document(str(goal_id))
    snapshot = (
        reference.get() if transaction is None else read_in_transaction(reference, transaction)
    )
    return owned_goal(snapshot, user_id)


def owned_goal(snapshot, user_id: str) -> Goal:
    if not snapshot.exists:
        raise HTTPException(status_code=404, detail="Goalが見つかりません")
    goal = Goal.model_validate(decode(snapshot.to_dict()))
    if goal.owner_id != user_id:
        raise HTTPException(status_code=404, detail="Goalが見つかりません")
    return goal


def load_goal_with_details(
    goal_id: uuid.UUID, db: firestore.Client, user_id: str, *, history_limit: int | None = None
) -> Goal:
    query = (
        db.collection("goals")
        .document(str(goal_id))
        .collection("progress_logs")
        .order_by("recorded_at", direction=firestore.Query.DESCENDING)
        .order_by("id", direction=firestore.Query.DESCENDING)
    )
    if history_limit is not None:
        query = query.limit(history_limit)

    snapshot = db.collection("goals").document(str(goal_id)).get()
    goal = owned_goal(snapshot, user_id)
    metrics = {metric.id: metric for metric in goal.metrics}
    # Use the Goal's server read time for history too. A snapshot needs no
    # read transaction or locks, even when another progress save arrives here.
    for item in query.stream(read_time=snapshot.read_time):
        log = ProgressLog.model_validate(decode(item.to_dict()))
        for update in log.metric_updates:
            metric = metrics[update.metric_id]
            update.metric_name, update.unit = metric.name, metric.unit
        goal.progress_logs.append(log)
    return goal


def goal_response(goal: Goal) -> GoalResponse:
    return GoalResponse(
        id=goal.id,
        title=goal.title,
        description=goal.description,
        target_date=goal.target_date,
        plan_revision=goal.plan_revision,
        status=goal.status,
        metrics=[
            MetricResponse.model_validate(metric)
            for metric in goal.metrics
            if metric.archived_at is None
        ],
        milestones=[MilestoneResponse.model_validate(milestone) for milestone in goal.milestones],
        progress_logs=[
            ProgressLogResponse.model_validate(progress_log) for progress_log in goal.progress_logs
        ],
        created_at=goal.created_at,
        updated_at=goal.updated_at,
    )
