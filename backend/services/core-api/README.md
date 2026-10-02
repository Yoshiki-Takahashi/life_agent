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

## Authentication

`/health`以外のGoal APIはFirebase ID Tokenを必要とします。Androidは各リクエストへ
`Authorization: Bearer <ID Token>`を付け、Core APIは検証済みTokenの`uid`を
Goalの`owner_id`として使用します。

Firebase Authentication Emulatorを使うローカル起動では次を設定します。

```bash
export FIREBASE_PROJECT_ID=life-agent-local
export FIREBASE_AUTH_EMULATOR_HOST=127.0.0.1:9099
uv run uvicorn life_agent_core.main:app --reload
```

`FIREBASE_AUTH_EMULATOR_HOST`は本番環境で設定しません。`AUTH_BACKEND=fake`は
既存のキー不要E2E専用で、通常実行と本番では既定値の`firebase`を使用します。

## Quality checks

```bash
uv run ruff check .
uv run ruff format --check .
uv run pytest
```

## Container

Cloud Run向けのコンテナをローカルでbuildして、health endpointを確認します。

```bash
docker build --platform linux/amd64 -t lifeagent-core-api:local .
docker run --rm -p 8080:8080 lifeagent-core-api:local
curl http://127.0.0.1:8080/health
```

本番設定や秘密値はimageへ含めず、Cloud Runの環境変数とSecret Managerから渡します。
Database migrationはコンテナ起動時には実行せず、deploy前の明示的な手順として適用します。

Cloud RunではCloud SQL connectionをserviceへ追加し、次の環境変数を設定します。

```text
CLOUD_SQL_CONNECTION_NAME=lifeagent-505614:asia-northeast1:lifeagent-dev-db
DB_USER=lifeagent_app
DB_NAME=lifeagent
DB_PASSWORD=<Secret Managerのlifeagent-db-password version 2>
```

`DB_PASSWORD`は通常の環境変数へ直接入力せず、Cloud RunのSecret参照として設定します。
ローカル開発では従来どおり`DATABASE_URL`を使用します。

PostgreSQLとCore APIを起動した状態で、リポジトリルートからE2Eを実行できます。

```bash
cd backend/services/core-api
CORE_API_URL=http://127.0.0.1:8000 uv run pytest ../../../tests/e2e
```

公開API:

- `GET /health`
- `GET /api/v1/goals`
- `POST /api/v1/goals/preview`
- `POST /api/v1/goals/confirm`
- `GET /api/v1/goals/{goal_id}`

Goal一覧はTokenの`uid`で所有者を絞り、更新日時の新しい順で概要を返します。`preview`は設定されたPlannerを呼び、DBへ保存せずに計画案を返します。通常のローカル実行とテストでは決定論的Fakeを使用します。独立Goal Plannerを使う場合は次を設定します。

```bash
GOAL_PLANNER_BACKEND=http
GOAL_PLANNER_URL=http://127.0.0.1:8001
GOAL_PLANNER_TIMEOUT_SECONDS=25
```

非公開Cloud Run上のGoal Plannerを呼ぶ場合は、Planner serviceのURLをaudienceに設定します。Core APIはこの値がある場合だけID tokenを取得し、`Authorization` headerを付けてPlannerへ送ります。

```bash
GOAL_PLANNER_BACKEND=http
GOAL_PLANNER_URL=https://<goal-planner-service-url>
GOAL_PLANNER_ID_TOKEN_AUDIENCE=https://<goal-planner-service-url>
```

Plannerの通信障害や契約違反は`503`となり、Goalは保存されません。`confirm`は編集済みのMetricとMilestoneを再検証し、Goalと同じTransactionで保存します。
Core APIのPlanner待機時間は、Goal Planner側のOpenAI待機時間より長く設定してください。
