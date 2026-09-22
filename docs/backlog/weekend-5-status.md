# Weekend 5 Status

このファイルはWeekend 5の実行台帳です。各ステップの終了時に更新し、再開時はこのファイルの`Current Step`から始めます。Secret値、Token、password、完全な接続文字列は記録しません。

## Summary

| Field | Value |
| --- | --- |
| Overall Status | Complete |
| Current Step | Complete |
| Last Completed Step | W5-90 Weekend 5 completion |
| Next Action | Weekend 6の進捗記録機能を開始する |
| Last Updated | 2026-09-22 |
| Blocker | None |

Statusは`Not started`、`In progress`、`Blocked`、`Complete`のいずれかを使用します。

## Phase Status

| Phase | Status | Exit Condition |
| --- | --- | --- |
| 0. 計画とローカル準備 | Complete | CLI、account、project、billing、regionを確認した |
| 1. BillingとFirebase | Complete | 予算通知とEmail/Password認証をUI・CLIで確認した |
| 2. Artifact Registry | Complete | repositoryとcleanup dry-runを確認した |
| 3. Secret Manager | Complete | Secret metadataとCloud Run用権限を確認した |
| 4. Cloud SQL | Complete | 作成、接続、migration、停止、再開を確認した |
| 5. Cloud Run | Complete | Console確認とCodexによる構成・health確認が成功した |
| 6. Android cloud E2E | Complete | 認証済みGoal操作が一周した |
| 7. CIとLogging | Complete | 品質workflowと代表ログを一度確認した |
| 8. 運用確認 | Complete | scriptsと削除dry-runをCodexが検証した |
| 9. 完了判定 | Complete | 品質検査後に課金対象を停止した |

## Step Checklist

### Phase 0

- [x] W5-00 計画、課金項目、削除方針を確認する
- [x] W5-01 ローカル品質コマンドを実行する
- [x] W5-02 必要なCLIとversionを確認する
- [x] W5-03 user account、project ID、billing account、regionを固定する
- [x] W5-04 CLIの読み取り専用preflightを完了する

### Phase 1

- [x] W5-10 UIで既存Billing Budgetを確認し、Weekend 5で再利用する
- [x] W5-11 CLIでalerts-only Budgetを確認し、Spend CapのCLI非表示理由を記録する
- [x] W5-12 UIでFirebase projectとEmail/Password認証を確認する
- [x] W5-13 Firebase CLIでprojectとAndroid app登録を確認する

### Phase 2

- [x] W5-20 UIでArtifact Registry repositoryを作成する
- [x] W5-21 CLIでrepository設定を確認する
- [x] W5-22 Core API imageをbuildしてpushする
- [x] W5-23 cleanup policyをdry-runで適用する

### Phase 3

- [x] W5-30 UIでSecretを作成する
- [x] W5-31 CLIでSecret metadataだけを確認する
- [x] W5-32 Cloud Run service accountへ最小権限を付与する

### Phase 4

- [x] W5-40〜W5-43 Cloud SQL、DB、user、接続、migrationを完成させる
- [x] W5-44 ユーザーがCLIでCloud SQL停止を体験する
- [x] W5-45 Codexが再開完了とschema保持を確認する
- [x] W5-46 CodexがCloud Run向けCore API imageを実装・検証・pushする

### Phase 5

- [x] W5-50a ユーザーがCLIでCloud Runへ最初のrevisionをdeployする
- [x] W5-50b ユーザーがCloud Run Consoleの「リビジョン履歴」でserviceと成功revisionを確認する
- [x] W5-51 Codexが構成、revision、scaling、IAM、`/health`を一括確認する

### Phase 6

- [x] W5-60 CodexがAndroid cloud設定、build、Emulatorを準備する
- [x] W5-61 ユーザーがログイン、Goal preview・保存・一覧・詳細を一周し、CodexがDBを確認する

### Phase 7

- [x] W5-70 Codexが品質workflowを整備し、GitHub Actionsで成功を確認する
- [x] W5-71 ユーザーが代表ログをUIで確認し、Codexが機密情報非出力を確認する

### Phase 8

- [x] W5-80 Codexがstatus/start/stop scriptsとdestroy dry-runを一括検証する

### Phase 9

- [x] W5-90 Codexが最終品質検査、証跡確認、Cloud SQL停止、Cloud Run縮退を行いCompleteにする

## Resource Inventory

値が確定した時点で記入します。Secret値そのものは記録しません。

| Resource | Name or ID | Region | State | Created By | Delete/Stop Method |
| --- | --- | --- | --- | --- | --- |
| GCP project | lifeagent-505614 | global | ACTIVE | Existing | project deletionは対象外 |
| Billing budget | max5000 | global | Existing / LifeAgent Cloud Run spend cap | Existing UI | Weekend 5では削除しない |
| Billing budget | ¥2,001 1か月の予算のアラート | global | Existing / billing account alerts-only | Existing UI | Weekend 5では削除しない |
| Firebase project | lifeagent-505614 | global | ACTIVE / Android app registered | Existing + UI | Weekend 5では削除しない |
| Artifact Registry | lifeagent-dev | asia-northeast1 | ACTIVE | UI | cleanup policy / repository delete |
| Secret | lifeagent-db-password | automatic | ACTIVE / version 2 enabled、version 1 disabled | UI + CLI | version disable/destroy / secret delete |
| Secret | lifeagent-db-admin-password | automatic | ACTIVE / version 1 | CLI | version disable/destroy / secret delete |
| Cloud SQL | lifeagent-dev-db | asia-northeast1 | STOPPED / activation policy NEVER | UI | activation policy ALWAYS / instance delete |
| Cloud Run | lifeagent-core-api | asia-northeast1 | ACTIVE / revision `lifeagent-core-api-00002-9pm` / traffic 100% | User CLI | scaling 0 / service delete |
| Service account | lifeagent-core-api@lifeagent-505614.iam.gserviceaccount.com | global | ACTIVE / Cloud SQL Client | UI | IAM解除後にdelete |

## Evidence Log

機密情報を除いたcommand、結果の要約、Console画面名、commitまたはworkflow URLを記録します。

| Date/Time | Step | Result | Evidence | Operator |
| --- | --- | --- | --- | --- |
| 2026-09-21 | W5-00 | Pass | 東京region、課金抑制、停止・削除、安全管理方針をユーザーが承認 | User |
| 2026-09-21 | W5-01a | Pass | Core API `uv run ruff check .`: All checks passed | User terminal |
| 2026-09-21 | W5-01b | Pass | Core API `uv run pytest`: 30 passed、依存ライブラリ由来のwarning 1件 | User terminal |
| 2026-09-21 | W5-01c | Blocked | Android test: SDK location not found | User terminal |
| 2026-09-21 | W5-01d | Pass | Android `testDebugUnitTest`: BUILD SUCCESSFUL | Codex |
| 2026-09-21 | W5-01e | Pass | Android `assembleDebug`: BUILD SUCCESSFUL。native library strip warningはpackage継続済み | Codex |
| 2026-09-21 | W5-02 | Pass | gcloud 585.0.0、Firebase CLI 15.30.2、Docker 29.7.2、gh 2.97.0を確認 | Codex |
| 2026-09-21 | W5-03a | Pass | `gcloud auth login`をユーザーが完了 | User terminal |
| 2026-09-21 | W5-03b | Pass | 利用可能なprojectを確認し、`lifeagent-505614`をWeekend 5対象に選択 | User terminal |
| 2026-09-21 | W5-03c | Pass | active gcloud configurationのprojectを`lifeagent-505614`へ設定 | User terminal |
| 2026-09-21 | W5-03d | Pass | active configurationが`default`であることを確認。LifeAgent専用名へ分離する方針を決定 | User terminal |
| 2026-09-21 | W5-03e | Skipped | activeな`default`は直接renameできないため、専用configuration作成を省略。各操作でproject IDを明示する | User terminal |
| 2026-09-21 | W5-03f | Pass | Cloud Runの既定regionを`asia-northeast1`へ設定 | User terminal |
| 2026-09-21 | W5-03g | Pass | `firebase login`をユーザーが完了 | User terminal |
| 2026-09-21 | W5-03h | Pass | GitHub CLIが認証済みであることを確認 | User terminal |
| 2026-09-21 | W5-04a | Pass | active project=`lifeagent-505614`、Cloud Run region=`asia-northeast1`を確認 | User terminal |
| 2026-09-21 | W5-04b | Pass | GCP project `lifeagent-505614`がACTIVEであることを確認 | User terminal |
| 2026-09-21 | W5-04c | Pass | Billing接続が有効であることを確認 | User terminal |
| 2026-09-21 | W5-10a | Pass | 既存budgetを確認。`max5000`はLifeAgent project対象だがCloud Runのみに限定されている | User / UI |
| 2026-09-21 | W5-10b | Decision | `max5000`はspend cap budgetのため単一serviceが必須。Cloud Run対象のまま維持し、請求先全体のalerts-only budgetと併用する | User / UI |
| 2026-09-21 | W5-10c | Pass | 設定を変更せず予算一覧へ戻り、既存2予算を再利用することを確認 | User / UI |
| 2026-09-21 | W5-11a | Partial | Billing Budgets APIを有効化し、CLI一覧にアラート予算1件を確認。UIの`max5000`は未表示 | User terminal |
| 2026-09-21 | W5-11b | Pass | `max5000`はCloud Run単一サービスのSpend Cap Preview。`gcloud billing budgets list`はbillingbudgets/v1を使用し、Console専用のSpend Cap拡張設定はCLI一覧で取得できない | Official docs / User terminal |
| 2026-09-21 | W5-12a | Decision | FirebaseはBlazeを維持。Cloud Run利用にBilling連携が必要で、Sparkへ戻すと有料Google Cloudサービスへアクセスできなくなるため | Official docs / User |
| 2026-09-21 | W5-12b | Pass | Firebase projectを確認。Email/Password有効、Phone未設定（無効） | User / UI |
| 2026-09-21 | W5-13a | Pass | `firebase projects:list`で`lifeagent-505614`を確認 | User terminal |
| 2026-09-21 | W5-13b | Setup required | Firebase projectにAndroid appが未登録であることを確認 | User terminal |
| 2026-09-21 | W5-13c | Pass | Firebase ConsoleでAndroid app `com.yoshiki.lifeagent`を登録 | User / UI |
| 2026-09-21 | W5-13d | Pass | Firebase CLIで登録済みAndroid appとApp IDを確認 | User terminal |
| 2026-09-21 | W5-13e | Pass | 実Firebaseを明示選択できるDebug build設定とGit管理外のclient configを追加 | Codex |
| 2026-09-21 | W5-13f | Pass | Pixel_8 Emulatorを起動し、実Firebase設定のDebug APKをinstall。ログイン画面を確認 | Codex |
| 2026-09-21 | W5-13g | Pass | 実Firebaseでアカウント作成、ログアウト、誤パスワード拒否を確認 | User / Android Emulator |
| 2026-09-21 | W5-20 | Pass | Docker standard repository `lifeagent-dev`を`asia-northeast1`に作成 | User / UI |
| 2026-09-21 | W5-21 | Pass | `lifeagent-dev`がDocker standard repository、東京region、Google-managed encryption、scanning無効、0 MBであることをCLI確認 | User terminal |
| 2026-09-21 | W5-22a | Pass | Core APIのproduction Dockerfileとdockerignoreを追加。ruff、format、pytest 30件が成功 | Codex |
| 2026-09-21 | W5-22b | Pass | `linux/amd64` imageをlocal buildし、containerの`GET /health`が200 OKを返すことを確認 | Codex |
| 2026-09-21 | W5-22c | Pass | Docker credential helperを`asia-northeast1-docker.pkg.dev`向けに設定 | User terminal |
| 2026-09-21 | W5-22d | Pass | local imageへArtifact Registry用tag `lifeagent-core-api:w5-initial`を付与し、`linux/amd64`であることを確認 | User terminal / Codex |
| 2026-09-21 | W5-22e | Pass | `lifeagent-core-api:w5-initial`をArtifact Registryへpush | User terminal |
| 2026-09-21 | W5-22f | Pass | Artifact Registry上でtag `w5-initial`とindex digestを確認。関連manifestとbuild attestationも保存済み | User terminal |
| 2026-09-21 | W5-23a | Pass | cleanup policyをdry-runで設定。30日超を削除候補、各packageの最新5 versionを保持 | User terminal |
| 2026-09-21 | W5-23b | Pass | Consoleでcleanup policyがdry-runであることを確認し、実削除へは切り替えず維持 | User / UI |
| 2026-09-21 | W5-30 | Pass | DB passwordをlocal生成し、Secret `lifeagent-db-password`を自動replicationで作成。値は記録していない | User terminal / UI |
| 2026-09-21 | W5-31 | Pass | Secret metadataは自動replication・DB credential type、version 1はenabled。秘密値は読み出していない | User terminal |
| 2026-09-21 | W5-32a | Pass | Cloud Run専用service accountを作成し、project-level roleがCloud SQL ClientだけであることをCLI確認 | User / UI / Codex |
| 2026-09-21 | W5-32b | Pass | Secret `lifeagent-db-password`だけに専用service accountのSecret Accessorを付与し、CLI確認 | User / UI / Codex |
| 2026-09-21 | W5-40a | In progress | PostgreSQL 17、Enterprise、db-f1-micro、10 GB HDD、single-zone、backup/PITR/storage auto-resize/deletion protection無効で作成開始 | User / UI / Codex |
| 2026-09-21 | W5-40b | Pass | `postgres`管理者パスワードを専用Secret version 1へ保存。Cloud Run向けIAM bindingなし、local clipboard消去済み | User terminal / Codex |
| 2026-09-21 | W5-40c | Pass | Cloud SQLがRUNNABLE。PostgreSQL 17、Enterprise、db-f1-micro、zonal、10 GB HDDで作成完了 | User / UI / Codex |
| 2026-09-21 | W5-41 | Pass | CLIでRUNNABLE、PostgreSQL 17、Enterprise、db-f1-micro、zonal、10 GB HDD、backup/PITR/auto-resize/deletion protection無効を再確認 | Codex |
| 2026-09-21 | W5-42a | Pass | Cloud SQL database `lifeagent`を作成。UTF8、`en_US.UTF8`をCLI確認 | User terminal / Codex |
| 2026-09-21 | W5-42b | Pass | built-in DB user `lifeagent_app`をSecret `lifeagent-db-password`の値で作成し、CLI確認 | User / UI / Codex |
| 2026-09-21 | W5-42c | Pass | Cloud SQL Auth Proxy 2.25.4をlocalへ導入。接続用port 15432が空いていることを確認 | Codex |
| 2026-09-21 | W5-42d | Fixed | Secret version 1はCloud SQL password policy不適合で認証失敗。要件を満たすversion 2へrotateし、`lifeagent_app`へ設定。version 1はdisabled | Codex |
| 2026-09-21 | W5-42e | Pass | Auth Proxy経由で`lifeagent_app`としてdatabase `lifeagent`へ接続。管理者資格情報は未使用 | Codex |
| 2026-09-21 | W5-43 | Pass | Alembicをhead `20260912_03`まで適用し、`alembic_version`、`goals`、`metrics`、`milestones`を確認 | Codex |
| 2026-09-21 | W5-44 | Pass | CLIでactivation policyをNEVERへ変更し、operation DONE、instance STOPPEDを確認 | User terminal / Codex |
| 2026-09-21 | Plan revision | Decision | 残りを7 checkpointへ統合。ユーザー操作はCloud Run UI、Android E2E、GitHub/Logging確認に限定し、反復CLIとlocal作業はCodexが担当 | User / Codex |
| 2026-09-21 | W5-45 | Pass | Cloud SQLがRUNNABLE / ALWAYSへ復帰し、更新operation DONE後にAlembic head `20260912_03`を再確認 | Codex |
| 2026-09-21 | W5-46a | Pass | Cloud SQL Unix socketとSecret `DB_PASSWORD`を使う設定を実装。ruff、format、pytest 33件が成功 | Codex |
| 2026-09-21 | W5-46b | Pass | `linux/amd64` containerのlocal health成功。Artifact Registryへ`w5-cloud` tagをpushしdigestを確認 | Codex |
| 2026-09-21 | W5-50a | Retry required | 初回deployはimage importとIAM設定まで成功したが、revision作成時にCloud Run内部エラーで終了。container logは生成されず、アプリ起動前の失敗と確認 | User CLI / Codex |
| 2026-09-22 | W5-50a | Pass | 同一serviceへの再deployが成功。revision `lifeagent-core-api-00002-9pm`がReady、traffic 100% | User CLI / Codex |
| 2026-09-22 | W5-51 | Pass | 専用service account、Cloud SQL attachment、Secret version 2、request-based billing、service max 1、公開Invokerを確認。実URLの`GET /health`が`{"status":"ok"}`を返した | Codex |
| 2026-09-22 | W5-50b | Pass | Cloud Run Consoleの「リビジョン履歴」で成功revisionを確認 | User / UI |
| 2026-09-22 | W5-60 | Pass | 実Firebaseを選ぶDebug buildがCloud Run URLも選ぶよう構成。unit test・assemble・install成功、EmulatorでMainActivity起動を確認 | Codex |
| 2026-09-22 | W5-61 | Pass | Cloud Run request logでpreview 200、confirm 201、一覧・詳細200を確認。DBにはowner付きGoal 3件が保存され、入力本文を取得せず検証完了 | User / Android Emulator / Codex |
| 2026-09-22 | W5-70a | Pass | Backend Quality workflowを追加。Core API 33件、Goal Planner 12件、両container build、Fake Plannerサービス間E2E 1件がlocalで成功 | Codex |
| 2026-09-22 | W5-71a | Pass | Cloud Run request logに成功statusのみを確認し、Secret、Token、password、request bodyの出力がないことを確認 | Codex |
| 2026-09-22 | W5-71b | Pass | Logs ExplorerでCore APIの代表requestを確認 | User / UI |
| 2026-09-22 | W5-80 | Pass | status、start、stop scriptを検証。destroyは固定された削除対象のpreviewだけを実行し、実削除なし | Codex |
| 2026-09-22 | W5-90a | Pass | Backend品質、両container build、サービス間E2E、Android unit test・Debug/Release buildが成功。Cloud SQLをSTOPPED / NEVERへ停止 | Codex |
| 2026-09-22 | W5-70b | Pass | GitHub Actions `Backend Quality` run 35679646620が41秒で成功。test、container build、サービス間E2Eをremote runnerで再現 | Codex / GitHub Actions |
| 2026-09-22 | W5-90 | Complete | Weekend 5の受け入れ条件、証跡、課金停止を確認。次の開発対象をWeekend 6の進捗記録へ更新 | Codex |

## Cost-Control Check

開発を終了するたびに確認します。

- [x] Cloud SQLのactivation policyが`NEVER`である
- [x] Cloud Runの最小instanceが0で、最大instanceが1以下である、またはserviceが無効である
- [x] 不要なCloud Run revisionへtrafficがない
- [x] Artifact Registry cleanup policyが存在する
- [x] Container Scanningを意図せず有効化していない
- [x] Secretの不要なactive versionが残っていない
- [x] DEBUGログを継続的に出していない
- [x] Billing Budgetと通知先が有効である

## Blockers and Decisions

| Date | Step | Type | Detail | Resolution |
| --- | --- | --- | --- | --- |
| 2026-09-21 | W5-01 | Environment | GradleがAndroid SDK locationを検出できなかった | `/Users/yoshiki/Library/Android/sdk`を確認し、Git除外済みlocal.propertiesへ設定した |
| 2026-09-21 | W5-03 | Decision | 複数projectへの誤操作防止 | `default`を維持し、変更系commandで`--project=lifeagent-505614`を明示する |
