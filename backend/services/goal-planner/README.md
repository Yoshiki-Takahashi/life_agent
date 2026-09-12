# Goal Planner Service

自然言語のGoalからMetricと3〜5個のMilestoneを生成する、状態を持たない内部サービスです。

責務:

- OpenAI APIの呼び出し
- Structured Outputの検証
- プロンプトとSchemaのバージョン管理
- 読書、アプリ開発、筋トレの回帰評価

Goalの保存やユーザー認可はCore APIが担当します。OpenAI APIキーはこのサービスだけに設定し、AndroidやCore APIへ渡しません。

## Setup

```bash
cd backend/services/goal-planner
uv sync --frozen
OPENAI_API_KEY=... uv run uvicorn life_agent_goal_planner.main:app --port 8001 --reload
```

APIは`http://127.0.0.1:8001`です。`GET /health`はAPIキーなしでも利用できます。`POST /internal/v1/goal-plans`はキー未設定またはOpenAI障害時に再試行可能な`503`を返します。

設定:

- `GOAL_PLANNER_PROVIDER`: 通常は`openai`。キー不要E2Eだけ`fake`を使用
- `OPENAI_API_KEY`: 実AI利用時に必須
- `OPENAI_MODEL`: 既定値`gpt-5.6-luna`
- `OPENAI_TIMEOUT_SECONDS`: 既定値20秒

## Quality checks

通常テストはOpenAI APIを呼びません。

```bash
uv run ruff check .
uv run ruff format --check .
uv run pytest -m "not live_ai"
```

APIキー取得後、読書・アプリ開発・筋トレの実AI評価を明示的に実行します。

```bash
OPENAI_API_KEY=... uv run pytest -m live_ai
```

`fake` Providerは独立サービス境界をToken消費なしで検証するためのものです。通常のアプリ確認では使用しません。リポジトリルートから次を実行すると、隔離されたPostgreSQL、Fake Goal Planner、Core APIを起動し、終了時に自動削除します。

```bash
scripts/test-weekend-3.sh
```
