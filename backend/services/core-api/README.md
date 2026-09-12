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

PostgreSQLとCore APIを起動した状態で、リポジトリルートからE2Eを実行できます。

```bash
cd backend/services/core-api
CORE_API_URL=http://127.0.0.1:8000 uv run pytest ../../../tests/e2e
```

公開API:

- `GET /health`
- `POST /api/v1/goals/preview`
- `POST /api/v1/goals/confirm`
- `GET /api/v1/goals/{goal_id}`

`preview`は設定されたPlannerを呼び、DBへ保存せずに計画案を返します。通常のローカル実行とテストでは決定論的Fakeを使用します。独立Goal Plannerを使う場合は次を設定します。

```bash
GOAL_PLANNER_BACKEND=http
GOAL_PLANNER_URL=http://127.0.0.1:8001
GOAL_PLANNER_TIMEOUT_SECONDS=5
```

Plannerの通信障害や契約違反は`503`となり、Goalは保存されません。`confirm`は編集済みのMetricとMilestoneを再検証し、Goalと同じTransactionで保存します。
