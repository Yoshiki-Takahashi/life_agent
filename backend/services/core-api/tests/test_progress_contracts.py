import json
from pathlib import Path

import pytest

from life_agent_core.progress_ai import Advice, ProgressPreview, ProgressPreviewRequest
from life_agent_core.schemas import (
    ReplanApplyRequest,
    ReplanCandidate,
    ReplanProposal,
    ReplanRequest,
)


@pytest.mark.parametrize(
    "name,model",
    [
        ("progress-preview-request", ProgressPreviewRequest),
        ("progress-preview", ProgressPreview),
        ("advice", Advice),
        ("replan-request", ReplanRequest),
        ("replan-candidate", ReplanCandidate),
        ("replan-proposal", ReplanProposal),
        ("replan-apply-request", ReplanApplyRequest),
    ],
)
def test_checked_in_schema_matches_model(name, model):
    root = Path(__file__).resolve().parents[4]
    schema = json.loads((root / "contracts" / "schemas" / f"{name}.schema.json").read_text())
    assert schema == model.model_json_schema()
