"""Run explicitly: uv run python -m evaluations.progress_quality --output /tmp/quality.json.

Mechanical checks are necessary, not a verdict on advice usefulness. Review every full output
against docs/progress-ai-quality.md and record scores separately. Only synthetic fixtures are used.
"""

import argparse
import hashlib
import json
from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime
from pathlib import Path
from time import monotonic

from evaluations.progress_cases import (
    advisor_cases,
    holdout_advisor_cases,
    holdout_parser_cases,
    parser_cases,
)
from life_agent_core.config import Settings
from life_agent_core.openai_progress import (
    ADVISOR_PROMPT,
    PARSER_PROMPT,
    OpenAIAdvisor,
    OpenAIProgressParser,
)
from life_agent_core.progress_ai import validate_candidates


def evaluate(case, kind, settings, repeat):
    started = monotonic()
    row = dict(
        id=case["id"],
        kind=kind,
        repeat=repeat,
        expectation=case["expectation"],
        context=case["context"].model_dump(mode="json"),
    )
    if kind == "parser":
        row["body"] = case["body"]
        row["expected"] = {str(key): value for key, value in case["expected"].items()}
    try:
        if kind == "parser":
            output = OpenAIProgressParser(settings).parse(case["context"], case["body"])
            validate_candidates(output, case["context"])
            actual = {item.metric_id.int: item.value for item in output.metric_updates}
            checks = dict(
                exact_candidates=actual == case["expected"],
                warning_present=not case["warning_required"] or bool(output.warnings),
            )
        else:
            output = OpenAIAdvisor(settings).advise(case["context"])
            checks = dict(
                summary_length=len(output.summary) <= 140,
                action_length=all(len(action) <= 100 for action in output.next_actions),
                total_length=len(output.summary) + sum(map(len, output.next_actions)) <= 360,
            )
        row.update(
            output=output.model_dump(mode="json"),
            checks=checks,
            mechanical_pass=all(checks.values()),
        )
    except Exception as error:
        # Never serialize SDK exception text or tracebacks, which may contain request details.
        row.update(
            error_type=type(error).__name__,
            cause_type=type(error.__cause__).__name__ if error.__cause__ else None,
            mechanical_pass=False,
        )
    row["elapsed_seconds"] = round(monotonic() - started, 2)
    return row


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--repeat", type=int, default=1, choices=range(1, 4))
    parser.add_argument("--only", choices=["all", "parser", "advisor"], default="all")
    parser.add_argument("--suite", choices=["main", "holdout"], default="main")
    args = parser.parse_args()
    settings = Settings()
    if not settings.openai_api_key:
        parser.error("Configure OPENAI_API_KEY for this explicit live evaluation")
    selected_parser = parser_cases if args.suite == "main" else holdout_parser_cases
    selected_advisor = advisor_cases if args.suite == "main" else holdout_advisor_cases
    cases = []
    for repeat in range(1, args.repeat + 1):
        if args.only in ("all", "parser"):
            cases += [(case, "parser", settings, repeat) for case in selected_parser()]
        if args.only in ("all", "advisor"):
            cases += [(case, "advisor", settings, repeat) for case in selected_advisor()]
    report = dict(
        started_at=datetime.now(UTC).isoformat(),
        model=settings.openai_model,
        parser_prompt=PARSER_PROMPT,
        advisor_prompt=ADVISOR_PROMPT,
        prompt_sha256=hashlib.sha256((PARSER_PROMPT + ADVISOR_PROMPT).encode()).hexdigest(),
        requested_calls=len(cases),
        results=[],
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with ThreadPoolExecutor(max_workers=3) as pool:
        for row in pool.map(lambda item: evaluate(*item), cases):
            report["results"].append(row)
            args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
            print(
                f"{row['id']} run={row['repeat']} mechanical={row['mechanical_pass']}", flush=True
            )
    report["completed_at"] = datetime.now(UTC).isoformat()
    report["mechanical_pass_count"] = sum(row["mechanical_pass"] for row in report["results"])
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print("Advice quality still requires content review; see docs/progress-ai-quality.md.")
    return 0 if all(row["mechanical_pass"] for row in report["results"]) else 1


if __name__ == "__main__":
    raise SystemExit(main())
