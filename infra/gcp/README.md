# GCP Development Environment

対象リソース:

- Cloud Run
- Artifact Registry
- Firebase Authentication
- Secret Manager
- Cloud Logging
- Cloud SQL（クラウドDB結合が必要になるまで作成しない）

リソース作成時は、設定値、課金抑制策、削除手順を同じ変更で記録します。

Weekend 5では、Google Cloud Consoleによる初回作成とユーザー端末のCLIによる確認・開始・停止・削除訓練を[Weekend 5 GCP実習ランブック](weekend-5-runbook.md)の順序で行います。進行状況と作成済みリソースは[Weekend 5 Status](../../docs/backlog/weekend-5-status.md)へ記録します。

日常の状態確認、開始、課金抑制のための停止には、リポジトリルートから次を実行します。

```bash
./scripts/gcp/status-dev.sh
./scripts/gcp/start-dev.sh
./scripts/gcp/stop-dev.sh
```

`destroy-dev.sh`は既定では削除候補の表示だけを行います。実削除は`--execute`、対話可能な端末、project IDと確認語の一致がすべて必要です。GCP project、Firebase project、Firebase user、Billing Budgetは削除対象に含みません。

## Billing alerts

CLIで管理するalerts-only予算は[`budget-alerts.json`](budget-alerts.json)に定義し、次のコマンドで未作成分を適用します。

```bash
./scripts/gcp/apply-budget-alerts.sh
```

Cloud Run Spend Cap `max2000`はPreview機能であり、`gcloud billing budgets`の管理対象外です。設定ファイルには期待状態を記録しますが、変更はGoogle Cloud Consoleで行います。通常のalerts-only予算は通知を行うだけで、利用を自動停止しません。

## Weekend 6 deploy workflow

開発環境へのBackend deployは`.github/workflows/deploy-dev.yml`から手動実行します。GitHub ActionsはWorkload Identity FederationでGCPへ接続し、長期サービスアカウントキーを保存しません。

GitHub environment `development` には次のVariablesを設定します。

```text
GCP_PROJECT_ID=lifeagent-505614
GCP_REGION=asia-northeast1
ARTIFACT_REGISTRY_REPOSITORY=lifeagent-dev
GCP_WORKLOAD_IDENTITY_PROVIDER=<projects/.../providers/...>
GCP_DEPLOYER_SERVICE_ACCOUNT=<deployer service account email>
CORE_API_SERVICE=lifeagent-core-api
CORE_API_RUNTIME_SERVICE_ACCOUNT=lifeagent-core-api@lifeagent-505614.iam.gserviceaccount.com
GOAL_PLANNER_SERVICE=lifeagent-goal-planner
GOAL_PLANNER_RUNTIME_SERVICE_ACCOUNT=<goal planner runtime service account email>
CLOUD_SQL_CONNECTION_NAME=lifeagent-505614:asia-northeast1:lifeagent-dev-db
OPENAI_SECRET_NAME=lifeagent-openai-api-key
```

`deploy-dev`は通常テスト、container build、Artifact Registry push、Cloud Run deploy、health確認を行います。Goal Plannerは非公開のAI接続serviceとしてdeployし、Core APIはPlanner URLを`GOAL_PLANNER_ID_TOKEN_AUDIENCE`にも設定して認証付きで呼び出します。OpenAI Secret登録前はGoal Planner / Progress Parser / Advisor providerを`fake`にしてservice境界だけを検証し、Secret登録後に必要なproviderを`openai`へ切り替えます。OpenAI SecretはGoal Planner runtime service accountだけに付与し、Core APIには渡しません。

開発環境の状態確認、開始、停止は`.github/workflows/manage-dev.yml`から手動実行します。`stop`はCloud SQLを`activationPolicy=NEVER`へ変更し、Core APIとGoal Plannerが`min=0`、`max=1`であることを検証します。Cloud Run service自体は削除せず、revisionを保持します。

DB migrationはWeekend 6の進捗記録migrationを追加する時点で、deploy前に失敗時停止できる明示手順へ組み込みます。現時点のworkflowは既存schemaのimage deployを対象にしています。

WIF、deploy用service account、Goal Planner runtime service account、GitHub Variablesは次のスクリプトで作成・更新します。

```bash
./scripts/gcp/setup-dev-deploy.sh
```

このスクリプトはSecret値を作成しません。`lifeagent-openai-api-key`が未作成の場合はIAM bindingだけをskipし、Secret登録後に再実行します。

OpenAI API keyは次のスクリプトで登録します。保存ファイルの末尾改行を除去してからSecret Managerへ送るため、Cloud Runの`OPENAI_API_KEY`環境変数へ不正な改行が入りません。

```bash
./scripts/gcp/register-openai-secret.sh
```
