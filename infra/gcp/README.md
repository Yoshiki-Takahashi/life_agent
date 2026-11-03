# GCP Development Environment

現在の実装はCore API・内部AIサービスのCloud Run、Firestore Standard、Firebase Authentication、
Artifact Registry、Secret Manager、Cloud Loggingを使用します。
2026-11-03時点ではFirestoreの準備と検証を実施し、既存SQLへのトラフィック切替は調整中です。
切替・48時間の観察・旧SQL撤去は[移管ランブック](firestore-migration-runbook.md)で管理します。
[Weekend 5実習](weekend-5-runbook.md)とarchiveは過去のSQL構成の記録です。

## 状態・開始・停止

```bash
./scripts/gcp/status-dev.sh
./scripts/gcp/start-dev.sh
./scripts/gcp/stop-dev.sh
```

startはCloud Runをauto/min 0/max 1へ戻し、stopはmanual 0へ変更します。
Firestoreは停止せずデータを保持します。無料枠超過の保存・操作は課金対象です。
移管中の旧SQLはこれらのコマンドでは操作せず、48時間の確認後に別手順で削除します。
`manage-dev.yml`も同じスクリプトを使います。

`destroy-dev.sh`は既定で候補表示だけを行います。実削除には`--execute`と端末での二重確認が必要です。
対象にFirestoreの全データを含みます。project、Firebase利用者、予算は削除しません。
旧SQLは移管ランブックの対象照合・観察条件を満たしてから扱います。

## Firestore

DBは`lifeagent-505614 / (default)`、東京`asia-northeast1`、Standard / Native modeです。
DB作成は既存一覧・無料枠対象を調べてから明示実行し、setupでは作り直しません。

```bash
./scripts/gcp/setup-firestore.sh
./scripts/gcp/check-firestore.sh
```

Coreの実行アカウントに対象DB限定の`roles/datastore.user`を与えます。
Android向けRulesは全拒否で、サーバーSDKの利用者認可はCore内で行います。
索引と検索しないフィールドの除外は`firestore.indexes.json`、Rulesは`firestore.rules`を配置します。
索引がREADYになるまで切り替えません。定期バックアップ・PITRは設定しません。

非公開のCloud Run検証Jobで実IAM・索引・同時保存を確認できます。Job内だけでFake AIと検証UIDを使います。
公開サービスのFirebase検証を置き換える処理は含みません。

```bash
CORE_API_IMAGE=<Artifact Registryの検証対象image@sha256:digest> ./scripts/gcp/check-firestore-api.sh
```

## デプロイ

`deploy-dev.yml`はテスト、image build/push、内部AIサービスの配置、Firestoreの準備確認を行います。
Coreは`--no-traffic`で候補revisionを作成します。サービスURLのhealthだけでは候補を検証したことになりません。
合意済みの切替枠で、検証済みrevision名を指定してトラフィックを変更します。
旧migration Jobは実行しません。

WIF・権限・GitHub Variablesは`./scripts/gcp/setup-dev-deploy.sh`で準備します。
environment `development`の変数:

```text
GCP_PROJECT_ID=lifeagent-505614
GCP_REGION=asia-northeast1
ARTIFACT_REGISTRY_REPOSITORY=lifeagent-dev
GCP_WORKLOAD_IDENTITY_PROVIDER=<projects/.../providers/...>
GCP_DEPLOYER_SERVICE_ACCOUNT=<deployer email>
CORE_API_SERVICE=lifeagent-core-api
CORE_API_RUNTIME_SERVICE_ACCOUNT=lifeagent-core-api@lifeagent-505614.iam.gserviceaccount.com
GOAL_PLANNER_SERVICE=lifeagent-goal-planner
GOAL_PLANNER_RUNTIME_SERVICE_ACCOUNT=<planner runtime email>
OPENAI_SECRET_NAME=lifeagent-openai-api-key
```

CoreにFirestoreの接続先とFirebase設定を渡し、DBパスワード・Cloud SQL attachmentは外します。
OpenAIキーは内部サービスだけが参照します。provider入力でopenaiを選ぶと、対応するCore backendはhttpになります。
通常CIはFakeで、実AIは明示実行します。キー登録には`register-openai-secret.sh`を使います。

## 課金

`budget-alerts.json`と`apply-budget-alerts.sh`はalerts-only予算を扱います。通知は自動停止ではありません。
Cloud Run Spend Cap `max2000`はPreviewであり、このスクリプトの管理外です。
旧SQL専用の予算整理は撤去時に行い、全体予算を残します。
無料枠と制限は[Firestore料金](https://firebase.google.com/docs/firestore/pricing)、
サービス停止は[Cloud Run manual scaling](https://docs.cloud.google.com/run/docs/configuring/services/manual-scaling)を確認してください。
