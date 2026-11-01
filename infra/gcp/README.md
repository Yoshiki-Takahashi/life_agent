# GCP Development Environment

対象リソース:

- Cloud Run
- Artifact Registry
- Firebase Authentication
- Secret Manager
- Cloud Logging
- Cloud SQL（クラウドDB結合が必要になるまで作成しない）

リソース作成時は、設定値、課金抑制策、削除手順を同じ変更で記録します。

Weekend 5では、Google Cloud Consoleによる初回作成とユーザー端末のCLIによる確認・開始・停止・削除訓練を[Weekend 5 GCP実習ランブック](weekend-5-runbook.md)の順序で行います。進行状況と作成済みリソースは[Weekend 5 Status](../../docs/archive/backlog/weekend-5-status.md)へ記録します。

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

Core APIのdeploy前に、同じimageを使うCloud Run Job `lifeagent-db-migrate`で
`alembic upgrade head`を実行します。Cloud SQLを起動してからworkflowを実行してください。
Jobの失敗時はCore APIのdeployを停止します。既存Core APIのruntime service accountとDB Secretだけを
使い、OpenAI Secretは渡しません。1 task、再試行0回、timeout120秒で実行し、常時稼働しません。
接続情報とDB SecretをログやGitへ保存しないでください。

ローカルAuth Proxyを`127.0.0.1:15438`で起動した後、`bash scripts/gcp/migrate-dev.sh`で
Secret Managerから一時的にDB接続情報を取得し、migrationと現在のrevisionの確認を実行できます。
ローカルからCloud SQLの3307番ポートへ到達できない場合は、workflowのmigration Jobを使います。

Weekend 8は対象ブランチの`deploy-dev.yml`を指定し、`replanner_backend=openai`を選択します。
Goal Plannerには`REPLANNER_PROVIDER=openai`、Core APIには`REPLANNER_BACKEND=http`を設定し、
OpenAI Secretは引き続きGoal Plannerだけに渡します。適用migrationは`20261004_05`です。
検証後も環境を利用する場合は起動を維持し、利用終了時は`stop-dev.sh`で停止します。

WIF、deploy用service account、Goal Planner runtime service account、GitHub Variablesは次のスクリプトで作成・更新します。

```bash
./scripts/gcp/setup-dev-deploy.sh
```

このスクリプトはSecret値を作成しません。`lifeagent-openai-api-key`が未作成の場合はIAM bindingだけをskipし、Secret登録後に再実行します。

OpenAI API keyは次のスクリプトで登録します。保存ファイルの末尾改行を除去してからSecret Managerへ送るため、Cloud Runの`OPENAI_API_KEY`環境変数へ不正な改行が入りません。

```bash
./scripts/gcp/register-openai-secret.sh
```

## Cloud Runも無効化した場合の再開

2026-10-25の開発終了時、全実行サービス停止の依頼により、次の状態を確認しました。

| リソース | 停止状態 |
| --- | --- |
| lifeagent-dev-db | STOPPED / activationPolicy=NEVER |
| lifeagent-core-api | scalingMode=manual / manualInstanceCount=0 |
| lifeagent-goal-planner | scalingMode=manual / manualInstanceCount=0 |

Core APIのhealthはHTTP 503となり、トラフィックタグによる別revisionの公開はありません。
Cloud Run migration Jobは完了済み、実行中Cloud Build・Compute Engine VM・Pub/Sub subscriptionはなし。
App Engine applicationは未作成、Cloud Scheduler APIは未使用または無効でした。
DB・サービス定義・保存イメージ・秘密情報は削除していません。

通常のstop-dev.shはCloud Runをmin=0のリクエスト駆動として残す手順です。
今回のmanual=0はリクエストでも再起動しない無効化です。
start-dev.shだけではCloud Runが再有効化されないため、次回はDBを起動してから
両サービスをautoへ戻してください。下記は再開時に明示実行するコマンドで、
今回の停止時には実行していません。

~~~bash
gcloud sql instances patch lifeagent-dev-db --activation-policy=ALWAYS --project=lifeagent-505614 --quiet
gcloud run services update lifeagent-goal-planner --scaling=auto --project=lifeagent-505614 --region=asia-northeast1 --quiet
gcloud run services update lifeagent-core-api --scaling=auto --project=lifeagent-505614 --region=asia-northeast1 --quiet
./scripts/gcp/start-dev.sh
~~~

停止でも保存領域等の費用は残ります。Cloud SQLは停止後もstorage・IPの料金対象です。
- [Cloud Runの無効化・再有効化](https://docs.cloud.google.com/run/docs/configuring/services/manual-scaling)
- [Cloud SQLの停止と継続課金](https://docs.cloud.google.com/sql/docs/postgres/start-stop-restart-instance)

### 全目標コーチの設定（UI刷新2）
手動deploy-devの`coach_backend`は既定`fake`。実AIを使うときだけ`openai`を選ぶと、
内部サービスに`COACH_PROVIDER=openai`、Coreに`COACH_BACKEND=http`を設定する。
既存の内部サービスURL・IAM・OpenAI Secretを使い、新しいサービスやDBは作らない。
今回のUI開発ではworkflowを実行せず、停止中のクラウドも再起動していない。
