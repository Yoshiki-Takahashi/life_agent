# Weekend 5 GCP実習ランブック（短縮版）

## 目的

Google Cloudの主要サービスを一度ずつ作成・接続し、AndroidからCloud Run上のCore APIを経由してCloud SQLへGoalを保存する。学習のための重複操作は避け、最後まで動く縦切りと課金停止を優先する。

進捗と証跡は[Weekend 5 Status](../../docs/backlog/weekend-5-status.md)へ記録する。

## 実行方針

- ユーザーは理解に重要なUI操作と、代表的な開始・停止操作だけを行う。
- Codexはローカル実装、build、test、読み取り確認、migration、反復CLI操作を行う。
- 一度確認したUIとCLIの対応は繰り返さない。
- Secret、Token、password、完全な接続文字列をterminal、Git、statusへ記録しない。
- Cloud SQLは必要な間だけ起動し、完了時に必ず停止する。
- Cloud Runはrequest-based billing、min 0、max 1を維持する。
- 完全削除はWeekend 5の必須実習から外し、対象一覧のdry-runだけ確認する。

## 役割分担

| 種類 | 担当 |
| --- | --- |
| 初回の主要クラウドリソース作成 | User / UI |
| Cloud SQLの代表的な停止・開始 | User / CLI |
| ローカル実装、環境構築、build、test | Codex |
| GCPの読み取り確認、migration、反復CLI | Codex |
| Androidの操作を伴うcloud E2E | User |
| 最終状態確認と課金停止 | Codex |

## 固定値

| 項目 | 値 |
| --- | --- |
| Project | `lifeagent-505614` |
| Region | `asia-northeast1` |
| Artifact Registry | `lifeagent-dev` |
| Cloud SQL | `lifeagent-dev-db` |
| Database / user | `lifeagent` / `lifeagent_app` |
| Runtime service account | `lifeagent-core-api@lifeagent-505614.iam.gserviceaccount.com` |
| App DB password Secret | `lifeagent-db-password` version 2 |

## 完了済み学習ブロック

### W5-00〜W5-13: 準備、Billing、Firebase

- CLI、project、billing、regionを確認した。
- 予算通知とCloud Run Spend Capの違いを確認した。
- Firebase Email/Password認証を実Androidアプリで確認した。

### W5-20〜W5-23: Artifact Registry

- Docker standard repositoryをUIで作成した。
- Core API imageをbuildしてpushした。
- cleanup policyをdry-runで設定した。

### W5-30〜W5-32: Secret ManagerとIAM

- アプリ用・管理者用DB passwordを別Secretとして保存した。
- Cloud Run専用service accountへCloud SQL Clientを付与した。
- アプリ用SecretだけにSecret Accessorを付与した。

### W5-40〜W5-44: Cloud SQL作成と停止

- PostgreSQL 17、db-f1-micro、10 GB HDD、single-zoneで作成した。
- `lifeagent` DBと`lifeagent_app` userを作成した。
- Auth Proxy経由でmigrationをheadまで適用した。
- CLIから停止し、`STOPPED / NEVER`を確認した。

## 残りの短縮実行パス

### W5-45: Cloud SQL再開確認 — Codex

ユーザーが開始した再開処理をCodexが監視し、`RUNNABLE / ALWAYS`とmigration revisionを確認する。ユーザーによる追加describeは行わない。

### W5-46: Cloud Run向けCore API準備 — Codex

1. Core APIがCloud RunのCloud SQL Unix socketと分離されたDB設定を扱えるようにする。
2. Secretは`lifeagent-db-password` version 2を参照し、管理者Secretは使用しない。
3. lint、test、container healthを実行する。
4. version付きimageをbuildしてArtifact Registryへpushする。

### W5-50: Cloud Run初回deploy — User / CLI、UI確認

ユーザーが学習する最後の主要リソース作成とする。

1. Codexが用意したimageからCLIで`lifeagent-core-api` serviceを作成する。
2. regionは`asia-northeast1`、request-based billing、min 0、max 1にする。
3. 専用service account、Cloud SQL connection、Secret version 2を設定する。
4. Consoleで作成されたserviceとrevisionを確認する。
5. Core APIはFirebase ID Tokenを検証するため、HTTP到達性とアプリ認証を分けて確認する。

### W5-51: Cloud Run検証 — Codex

Codexがservice、revision、image digest、service account、Cloud SQL、Secret、scalingをCLIで一括確認し、`GET /health`を実行する。Cloud Runの停止・再開とCLI再deployの重複実習は行わない。

### W5-60〜W5-61: Android cloud E2E — Codex + User

Codexがcloud API URL設定、Git除外確認、build、Emulator準備を行う。ユーザーは次の一周だけを操作する。

1. Firebaseでログインする。
2. Goalを入力してpreviewを確認する。
3. 保存し、一覧と詳細へ反映されたことを確認する。

CodexはDBにowner付きGoalが存在することだけを確認し、入力全文を証跡に残さない。

### W5-70: CIとLogging最小確認 — Codex + User

- Codexが品質workflowを整備し、ローカルとGitHub Actionsで同じ品質commandを使えるようにする。
- ユーザーはGitHub UIで品質workflowを一度実行または結果確認する。
- ユーザーはLogs ExplorerでhealthまたはE2E requestを一度確認する。
- CodexがToken、Secret、password、入力全文がログにないことを確認する。
- Cloud Run自動deployとWorkload Identity FederationはWeekend 6へ延期する。

### W5-80〜W5-90: 運用スクリプト、最終品質、停止 — Codex

1. `status-dev.sh`、`start-dev.sh`、`stop-dev.sh`を実装・検証する。
2. `destroy-dev.sh`は削除対象一覧と確認機構だけをdry-runで検証する。
3. Backend、Android、cloud E2Eの最終品質検査を行う。
4. Cloud SQLを`NEVER`へ停止する。
5. Cloud Runがmin 0、max 1であることを確認する。
6. cleanup policy、Container Scanning、不要なSecret versionを確認する。
7. Weekend 5をCompleteにする。

## 削除方針

- GCP project、Firebase project、Budgetは削除しない。
- Weekend 5ではCloud SQL、Cloud Run、Artifact Registry、Secretの実削除を必須にしない。
- `destroy-dev.sh`は既定で一覧表示のみとし、実削除にはproject IDと環境名の再入力を要求する。
- 日常の課金抑制はCloud SQL停止、Cloud Run min 0、Artifact cleanup policyで行う。

## Codexへ依頼できる操作

- 「現在状態を確認して」: 対象リソースを読み取り確認する。
- 「今日の開発環境を停止して」: Cloud SQLを停止し、Cloud Run scalingを確認する。
- 「開発環境を開始して」: Cloud SQLを開始し、healthを確認する。
- 「削除候補を確認して」: 実削除せず対象一覧だけを表示する。

完全削除は一般的な停止依頼から推測して実行しない。
