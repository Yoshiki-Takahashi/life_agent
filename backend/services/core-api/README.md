# Core API

Androidに公開する唯一のBackend APIです。

責務:

- Firebase ID Tokenの検証とユーザー認可
- Goal、Metric、Milestone、ProgressLogの業務ルール
- PostgreSQLへの永続化とトランザクション
- Goal Planner / AI接続サービス等の内部サービス呼び出し
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
- `POST /api/v1/goals/{goal_id}/progress/preview`
- `POST /api/v1/goals/{goal_id}/progress`
- `POST /api/v1/goals/{goal_id}/advice`

Goal一覧はTokenの`uid`で所有者を絞り、更新日時の新しい順で概要を返します。`preview`は設定されたPlannerを呼び、DBへ保存せずに計画案を返します。通常のローカル実行とテストでは決定論的Fakeを使用します。独立したGoal Planner / AI接続サービスを使う場合は次を設定します。

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

内部サービスの通信障害や契約違反は`503`となり、Goalや進捗は保存されません。`confirm`は編集済みのMetricとMilestoneを再検証し、Goalと同じTransactionで保存します。
Core APIの内部サービス待機時間は、AI接続サービス側のOpenAI待機時間より長く設定してください。

`progress`は認証済みユーザーが所有するGoalだけを対象に、進捗本文とMetric更新値を保存します。
Metric更新は加算で、`Metric.current_value`が`target_value`を超える更新、別GoalのMetric、
重複Metric、不正な入力は拒否します。`client_request_id`はGoal内で一意に扱い、同じIDの
再送はMetricへ二重反映しません。ProgressLog、Metric更新履歴、Metric現在値、Goal更新日時は
同一Transactionで保存します。

## Weekend 7: Progress Parser / Advisor

Goal詳細の進捗メモを解析し、ユーザーが修正・確認してから既存進捗APIで保存します。保存後の助言取得は独立しているため、助言生成だけ失敗しても履歴は残ります。公開契約は[`contracts/README.md`](../../../contracts/README.md)を参照してください。

Core APIはOpenAIへ直接接続しません。`PROGRESS_PARSER_BACKEND=http`と`ADVISOR_BACKEND=http`を設定すると、`GOAL_PLANNER_URL`で指定した内部AI接続サービスへ`/internal/v1/progress-preview`と`/internal/v1/advice`を呼びます。移行互換のため`openai`も内部HTTP呼び出しとして解釈しますが、Core APIに`OPENAI_API_KEY`は設定しません。通常テストとキー不要E2Eでは両方`fake`です。

```bash
GOAL_PLANNER_BACKEND=http \
GOAL_PLANNER_URL=http://127.0.0.1:8001 \
PROGRESS_PARSER_BACKEND=http \
ADVISOR_BACKEND=http \
uv run uvicorn life_agent_core.main:app
```

Core APIの責務は、所有者確認済みGoalだけをAI接続サービスへ渡すこと、返ってきた候補をPydantic Schemaとドメインルールで再検証すること、ユーザー確認後の保存をTransactionで行うことです。OpenAI SDK、プロンプト、Structured Output schema、Provider timeout/retry/refusal処理、実AI回帰評価はGoal Planner Service側に置きます。

リポジトリルートから`./scripts/test-weekend-7.sh`で専用PostgreSQL・Fake APIを使った同時保存テストを実行できます。`--android`を付けると起動済みエミュレータで解析・取消・修正・保存・助言障害・再試行・再訪を確認し、スクリーンショットを`android/app/build/weekend7/files/`に保存します。認証はこの隔離E2Eに限りFakeです。

### ユーザー価値の評価

有益さの受け入れは[解析・助言品質設計](../../../docs/progress-ai-quality.md)で定義しています。合成の主19ケースと追加6ケースを使い、数値の完全一致に加えて制約への適合、最初の行動、負担、主体性を評価します。実AI評価はGoal Planner Serviceから明示的に実行します。

```bash
cd ../goal-planner
OPENAI_API_KEY=... uv run python -m evaluations.progress_quality --repeat 2 --output /tmp/progress-quality.json
OPENAI_API_KEY=... uv run python -m evaluations.progress_quality --suite holdout --repeat 2 --output /tmp/progress-quality-holdout.json
```

このコマンドは実OpenAIを呼び出します。通常CIでは実行しません。終了コード0は機械チェックの合格のみで、全返答の内容レビューも必要です。キー・providerエラー全文はレポートに記録しません。Weekend 7時点の内容レビュー結果は[進捗解析・助言 実AI品質評価結果](../../../docs/progress-ai-quality-results.md)に記録しています。
