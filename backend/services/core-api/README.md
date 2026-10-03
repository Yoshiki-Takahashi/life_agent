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
- `POST /api/v1/goals/{goal_id}/progress`

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

`progress`は認証済みユーザーが所有するGoalだけを対象に、進捗本文とMetric更新値を保存します。
Metric更新は加算で、`Metric.current_value`が`target_value`を超える更新、別GoalのMetric、
重複Metric、不正な入力は拒否します。`client_request_id`はGoal内で一意に扱い、同じIDの
再送はMetricへ二重反映しません。ProgressLog、Metric更新履歴、Metric現在値、Goal更新日時は
同一Transactionで保存します。

## Weekend 7: Progress Parser / Advisor

Goal詳細の進捗メモを解析し、ユーザーが修正・確認してから既存進捗APIで保存します。保存後の助言取得は独立しているため、助言生成だけ失敗しても履歴は残ります。公開契約は[`contracts/README.md`](../../../contracts/README.md)を参照してください。

通常は両方Fakeです。Fake Parserは明確な数字と単位を含む完了報告のデモ用で、汎用の自然言語解析器ではありません。独立した実AI Adapterへは次で切り替えます。

```bash
PROGRESS_PARSER_BACKEND=openai ADVISOR_BACKEND=openai uv run uvicorn life_agent_core.main:app
```

`OPENAI_API_KEY`を環境変数または無視対象の`.env`に設定します。`OPENAI_MODEL`は既存Plannerと同じ`gpt-5.6-luna`、timeoutは20秒、SDKの自動再試行は0回、出力上限は2000 tokens、`store=False`です。Androidのread timeoutは35秒です。秘密情報や報告全文をエラーログへ出しません。

通常テストは実AIを呼びません。3分野の解析・保存・助言の評価は明示的に実行します（合計6回のAI呼び出し）。

```bash
RUN_LIVE_AI=1 uv run pytest tests/test_live_progress.py -q
```

リポジトリルートから`./scripts/test-weekend-7.sh`で専用PostgreSQL・Fake APIを使った同時保存テストを実行できます。`--android`を付けると起動済みエミュレータで解析・取消・修正・保存・助言障害・再試行・再訪を確認し、スクリーンショットを`android/app/build/weekend7/files/`に保存します。認証はこの隔離E2Eに限りFakeです。

実AI Adapterは[OpenAI Structured Outputsの公式仕様](https://developers.openai.com/api/docs/guides/structured-outputs)に従い、`responses.parse`とPydanticモデルで出力を検証します。拒否・未完了・不正出力・タイムアウトは再試行可能なエラーに変換します。

### ユーザー価値の評価

従来の3分野テストは接続と構造のスモークテストです。有益さの受け入れは[解析・助言品質設計](../../../docs/progress-ai-quality.md)で別に定義しています。合成の主19ケースと追加6ケースを使い、数値の完全一致に加えて制約への適合、最初の行動、負担、主体性を評価します。

```bash
uv run python -m evaluations.progress_quality --repeat 2 --output /tmp/progress-quality.json
uv run python -m evaluations.progress_quality --suite holdout --repeat 2 --output /tmp/progress-quality-holdout.json
```

このコマンドは実OpenAIを呼び出します。通常CIでは実行しません。終了コード0は機械チェックの合格のみで、全返答の内容レビューも必要です。キー・providerエラー全文はレポートに記録しません。
Weekend 7時点の内容レビュー結果は[進捗解析・助言 実AI品質評価結果](../../../docs/progress-ai-quality-results.md)に記録しています。
