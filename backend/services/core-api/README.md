# Core API

Androidに公開する唯一のBackend APIです。

[現在の仕様](../../../docs/README.md)と[API](../../../docs/api.md)を参照してください。

## Setup

[Backend手順](../../README.md)でFirestore Emulatorを起動し、次を実行します。

```bash
cd backend/services/core-api
uv sync --frozen
export FIRESTORE_PROJECT_ID=demo-lifeagent
export FIRESTORE_EMULATOR_HOST=127.0.0.1:8080
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
Cloud RunではCoreの実行アカウントを対象Firestore DBの`roles/datastore.user`へ限定し、次を設定します。

```text
FIRESTORE_PROJECT_ID=lifeagent-505614
FIRESTORE_DATABASE_ID=(default)
MAINTENANCE_MODE=false
```

クラウドではEmulator環境変数を設定しません。DBパスワード・Cloud SQL接続は使いません。
`MAINTENANCE_MODE=true`は`/api/`へ503を返し、healthを維持します。
Rules・索引の配置と切替手順は[クラウド運用](../../../infra/gcp/README.md)を参照してください。
公開APIは維持し、SQLテストデータは取り込みません。

キー不要の独立サービスE2Eはリポジトリルートで実行します。

```bash
./scripts/test-weekend-3.sh
./scripts/test-weekend-7.sh
```

独立したAIサービスへの接続設定:

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

解析・助言を内部AIサービスへ接続する設定:

```bash
GOAL_PLANNER_BACKEND=http \
GOAL_PLANNER_URL=http://127.0.0.1:8001 \
PROGRESS_PARSER_BACKEND=http \
ADVISOR_BACKEND=http \
uv run uvicorn life_agent_core.main:app
```

隔離DB・サービス間検証は[結合テスト](../../../tests/e2e/README.md)を参照してください。

### ユーザー価値の評価

有益さの受け入れは[解析・助言品質設計](../../../docs/progress-ai-quality.md)で定義しています。合成の主19ケースと追加6ケースを使い、数値の完全一致に加えて制約への適合、最初の行動、負担、主体性を評価します。実AI評価はGoal Planner Serviceから明示的に実行します。

```bash
cd ../goal-planner
OPENAI_API_KEY=... uv run python -m evaluations.progress_quality --repeat 2 --output /tmp/progress-quality.json
OPENAI_API_KEY=... uv run python -m evaluations.progress_quality --suite holdout --repeat 2 --output /tmp/progress-quality-holdout.json
```

このコマンドは実OpenAIを呼び出します。通常CIでは実行しません。終了コード0は機械チェックの合格のみで、全返答の内容レビューも必要です。キー・providerエラー全文はレポートに記録しません。Weekend 7時点の内容レビュー結果は[進捗解析・助言 実AI品質評価結果](../../../docs/archive/progress-ai-quality-results.md)に記録しています。

## 全目標コーチの接続

`COACH_BACKEND=fake`（既定）または`http`。内部サービス側は`COACH_PROVIDER=fake|openai`。
URL・timeout・IAM認証は他のAI機能と共有します。CoreへOpenAIキーを設定しません。
