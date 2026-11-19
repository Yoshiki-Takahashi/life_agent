import json
import logging
from collections.abc import Callable

import tiktoken
from openai import OpenAI, OpenAIError
from pydantic import BaseModel

from life_agent_goal_planner.config import Settings

logger = logging.getLogger(__name__)


class AIUnavailableError(Exception):
    pass


class AIInputTooLargeError(Exception):
    pass


def generate[T: BaseModel](
    settings: Settings,
    factory: Callable[..., OpenAI],
    schema: type[T],
    prompt: str,
    context: dict,
) -> T:
    if not settings.openai_api_key:
        raise AIUnavailableError("OpenAI key is not configured")
    try:
        context_text = json.dumps(context, ensure_ascii=False)
        schema_text = json.dumps(schema.model_json_schema(), ensure_ascii=False)
        encoding = tiktoken.get_encoding("o200k_base")
        # Allow room for message roles and the SDK's strict Structured Output wrapper.
        input_tokens = 256 + sum(
            len(encoding.encode_ordinary(text)) for text in (prompt, context_text, schema_text)
        )
        if input_tokens > settings.openai_max_input_tokens:
            raise AIInputTooLargeError
        client = factory(
            api_key=settings.openai_api_key,
            timeout=settings.openai_timeout_seconds,
            max_retries=0,
        )
        response = client.responses.parse(
            model=settings.openai_model,
            input=[
                {"role": "system", "content": prompt},
                {"role": "user", "content": context_text},
            ],
            text_format=schema,
            max_output_tokens=2000,
            store=False,
        )
        if response.output_parsed is None:
            raise AIUnavailableError("No parsed output")
        return schema.model_validate(response.output_parsed)
    except (OpenAIError, OSError, ValueError, TypeError) as error:
        logger.warning("AI candidate generation failed: %s", type(error).__name__)
        raise AIUnavailableError("AI generation failed") from error
