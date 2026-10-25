"""Bounded Re-planner provider calls, explicitly enabled."""

import json
import os
from datetime import UTC, datetime, timedelta
from pathlib import Path
from uuid import UUID

import pytest

from evaluations.progress_cases import goal
from life_agent_goal_planner.config import Settings
from life_agent_goal_planner.replan import OpenAIReplanner
from life_agent_goal_planner.schemas import MilestoneResponse

pytestmark = [
    pytest.mark.live_ai,
    pytest.mark.skipif(
        os.getenv("RUN_LIVE_AI") != "1",
        reason="Set RUN_LIVE_AI=1 to explicitly enable live evaluation",
    ),
]


@pytest.mark.parametrize(
    "title,metric,reason",
    [
        ("12冊の本を読む", ("読了冊数", "冊", 2, 12), "予定より読書時間が少なく、冊数を見直したい"),
        (
            "アプリを開発する",
            ("完成した成果物", "件", 0, 2),
            "認証まわりで詰まり、期限とMilestoneを現実的にしたい",
        ),
        ("筋トレを続ける", ("トレーニング回数", "回", 3, 12), "膝に痛みがあり回数目標を調整したい"),
    ],
)
def test_live_replan_categories_produce_reviewable_candidates(title, metric, reason):
    settings = Settings()
    assert settings.openai_api_key, "Configure OPENAI_API_KEY for explicit live evaluation"
    context = goal(title, "90日で達成したい", [metric], deadline_days=45)
    context.milestones = [
        MilestoneResponse(
            id=UUID(int=200 + index),
            title=f"既存計画の進捗を確認する {index + 1}",
            target_date=datetime.now(UTC).date() + timedelta(days=days),
            position=index,
            created_at=datetime.now(UTC),
        )
        for index, days in enumerate((15, 30, 45))
    ]

    result = OpenAIReplanner(settings).propose(context, reason)

    assert result.diff
    assert 1 <= len(result.proposed_plan.metrics) <= 3
    assert 3 <= len(result.proposed_plan.milestones) <= 5
    assert result.proposed_plan.target_date >= result.proposed_plan.milestones[-1].target_date
    assert result.proposed_plan.metrics[0].target_value >= context.metrics[0].current_value
    if "痛み" in reason:
        assert result.proposed_plan.metrics[0].target_value == context.metrics[0].current_value
    report_dir = os.getenv("REPLAN_EVALUATION_OUTPUT_DIR")
    if report_dir:
        output = Path(report_dir)
        output.mkdir(parents=True, exist_ok=True)
        (output / f"replan-{context.metrics[0].unit}.json").write_text(
            json.dumps(
                {
                    "model": settings.openai_model,
                    "goal": context.model_dump(mode="json"),
                    "reason": reason,
                    "candidate": result.model_dump(mode="json"),
                },
                ensure_ascii=False,
                indent=2,
            )
        )
