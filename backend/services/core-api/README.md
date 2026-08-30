# Core API

Androidに公開する唯一のBackend APIです。

責務:

- Firebase ID Tokenの検証とユーザー認可
- Goal、Metric、Milestone、ProgressLogの業務ルール
- PostgreSQLへの永続化とトランザクション
- Goal Planner等の内部サービス呼び出し
- AI出力の最終検証

このサービスだけがアプリケーションDBを所有します。

## Setup

先に`backend/`でPostgreSQLを起動します。その後、次を実行します。

```bash
cd backend/services/core-api
uv sync --frozen
uv run alembic upgrade head
uv run uvicorn life_agent_core.main:app --reload
```

APIは`http://127.0.0.1:8000`、OpenAPI UIは`http://127.0.0.1:8000/docs`です。

## Quality checks

```bash
uv run ruff check .
uv run ruff format --check .
uv run pytest
```

PostgreSQLとCore APIを起動した状態で、リポジトリルートからWeekend 2のE2Eを実行できます。

```bash
cd backend/services/core-api
CORE_API_URL=http://127.0.0.1:8000 uv run pytest ../../../tests/e2e
```

公開API:

- `GET /health`
- `POST /api/v1/goals/preview`
- `POST /api/v1/goals/confirm`
- `GET /api/v1/goals/{goal_id}`

`preview`はCore API内の決定論的Fake Plannerを呼び、DBへ保存せずに計画案を返します。`confirm`は編集済みのMetricとMilestoneを再検証し、Goalと同じTransactionで保存します。
