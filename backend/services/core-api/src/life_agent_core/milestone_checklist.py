from collections.abc import Iterable, Sequence
from decimal import ROUND_CEILING, ROUND_FLOOR, Decimal
from uuid import UUID

from life_agent_core.models import Metric, ProgressMetricUpdate
from life_agent_core.schemas import MetricMilestoneChecklist, MilestoneCheck


def measurement_starts(
    metric_ids: set[UUID], updates_in_order: Iterable[Iterable[ProgressMetricUpdate]]
) -> dict[UUID, Decimal]:
    if not metric_ids:
        return {}
    starts: dict[UUID, Decimal] = {}
    for updates in updates_in_order:
        for update in updates:
            if update.metric_id in metric_ids and update.kind == "measurement":
                starts.setdefault(update.metric_id, update.value)
        if len(starts) == len(metric_ids):
            break
    return starts


def milestone_checklist(
    metrics: Sequence[Metric], starts: dict[UUID, Decimal]
) -> list[MetricMilestoneChecklist]:
    return [
        MetricMilestoneChecklist(
            metric_id=metric.id,
            metric_name=metric.name,
            items=metric_checks(metric, starts.get(metric.id)),
        )
        for metric in metrics
    ]


def metric_checks(metric: Metric, first_value: Decimal | None) -> list[MilestoneCheck]:
    if metric.kind == "checklist":
        completed = set(metric.completed_items)
        return [
            MilestoneCheck(label=item, completed=item in completed)
            for item in metric.checklist_items
        ]

    target = metric.target_value
    current = metric.current_value
    if metric.kind == "measurement" and (first_value is None or first_value == target):
        return [
            MilestoneCheck(
                label=f"{format_value(target)} {metric.unit} に到達",
                completed=first_value is not None and current == target,
            )
        ]

    start = first_value if first_value is not None else Decimal(0)
    decreasing = start > target
    whole_steps = metric.kind == "occurrence" or (
        metric.kind == "cumulative" and target == target.to_integral_value()
    )
    precision = Decimal("1") if whole_steps else Decimal("0.01")
    # Round towards the target so a checkpoint is only checked upon reaching its displayed value.
    rounding = ROUND_FLOOR if decreasing else ROUND_CEILING
    thresholds = dict.fromkeys(
        (start + (target - start) * quarter / 4).quantize(precision, rounding=rounding)
        for quarter in range(1, 5)
    )
    condition = "以下" if decreasing else "以上"
    return [
        MilestoneCheck(
            label=f"{format_value(value)} {metric.unit}{condition}",
            completed=current <= value if decreasing else current >= value,
        )
        for value in thresholds
    ]


def format_value(value: Decimal) -> str:
    return format(value.normalize(), "f")


def achievement_percent(checklist: Sequence[MetricMilestoneChecklist]) -> float:
    if not checklist:
        return 0
    return (
        100
        * sum(
            sum(item.completed for item in group.items) / len(group.items) if group.items else 0
            for group in checklist
        )
        / len(checklist)
    )
