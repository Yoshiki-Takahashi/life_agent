from datetime import date, timedelta

from life_agent_core.schemas import GoalPlan, GoalPreviewRequest, MetricDraft, MilestoneDraft


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

    days = max((payload.target_date - start).days, 0)
    dates = (
        start + timedelta(days=days // 3),
        start + timedelta(days=(days * 2) // 3),
        payload.target_date,
    )
    milestones = [
        MilestoneDraft(title=title, target_date=target_date)
        for title, target_date in zip(titles, dates, strict=True)
    ]
    return GoalPlan(
        title=payload.title,
        description=payload.description,
        target_date=payload.target_date,
        metrics=[metric],
        milestones=milestones,
    )
