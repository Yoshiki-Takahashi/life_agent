# Weekend 5: GCP開発環境

## Status

In Progress

## Sprint Goal

主要なUI作成と代表的なCLI lifecycle操作でLifeAgentのクラウド構成を理解し、AndroidからCloud Run上のCore APIへ接続してCloud SQLへGoalを保存できるようにする。重複する学習操作はCodexへ移し、縦切りの完走と課金停止を優先する。

## User Story

開発者として、クラウドサービスを画面上で一つずつ作成して構成要素の関係を理解したい。その後は同じリソースをCLIで確認・操作し、日常の開始・停止をCodexへ任せられるようにして、学習機会を保ちながら意図しない課金を抑えたい。

## Acceptance Criteria

- 実習は[Weekend 5 GCP実習ランブック](../../infra/gcp/weekend-5-runbook.md)の短縮実行パスに従う。
- ユーザーがGoogle Cloud ConsoleでBilling Budget、Firebase Authentication、Artifact Registry、Secret Manager、Cloud SQL、Cloud Run、Cloud Loggingを確認または構成する。
- ユーザーは`gcloud`、`firebase`、`docker`の代表操作を一度ずつ体験し、反復する読み取り・検証はCodexが行う。
- UIで作成した主要リソースはCodexがCLIで一括検証する。
- ユーザーはCloud SQLの停止・開始をCLIで体験する。Cloud Runの重複する無効化・再有効化実習は省略する。
- Core APIのコンテナをArtifact Registryへ保存し、Cloud Runへデプロイできる。
- Cloud RunからCloud SQLへ接続し、Alembic migrationを適用できる。
- Firebase ID TokenをCloud Run上のCore APIで検証できる。
- Androidのrelease buildからCloud Runへ接続し、Goalの作成・一覧・詳細表示を一周できる。
- GitHub ActionsでBackendの品質検査を再現できる。Cloud Run自動deployはWeekend 6へ延期する。
- Artifact Registryに古いイメージを削除するcleanup policyがあり、最初にdry-runで候補を確認する。
- Cloud Runはrequest-based billing、最小インスタンス0、最大インスタンス1で構成する。
- Cloud SQLは開発終了時に停止され、停止後も残るストレージ・バックアップ料金を認識できる。
- 秘密値、Firebase設定ファイル、project固有の認証情報をGitへコミットしない。
- [Weekend 5ステータス](weekend-5-status.md)に各工程の完了、証跡、作成済みリソース、停止状態を記録する。

## Execution Policy

- 主要サービスの初回作成だけをUIで行い、同種の設定確認を繰り返さない。
- 作成後のCLI読み取り、ローカル環境構築、lint、単体テスト、ビルド、migrationはCodexが実行する。
- ユーザー操作はCloud Run初回CLI deployとUI確認、Android E2E、GitHub/Loggingの目視確認へ限定する。
- 日常の開始、停止、状態確認はリポジトリ内のスクリプトから実行できるようにする。
- 削除は通常の停止処理と分離し、対象projectとresource nameを表示したうえで明示確認を要求する。
- Codexはユーザーが認証したCLIを使用できるが、認証情報やSecret値を出力・保存しない。
- 安全性と課金に関わる差分がなければ、Codexが確認して次の工程へ進む。

## Deliverables

- `infra/gcp/weekend-5-runbook.md`: UIとCLIを交互に使う実習手順
- `docs/backlog/weekend-5-status.md`: 進捗、証跡、リソース状態の台帳
- `scripts/gcp/status-dev.sh`: 読み取り専用の状態確認
- `scripts/gcp/start-dev.sh`: Cloud SQLとCloud Runの開発利用再開
- `scripts/gcp/stop-dev.sh`: 課金抑制のための停止・縮退
- `scripts/gcp/destroy-dev.sh`: 明示確認付きの完全削除
- Core APIのproduction imageを作るDockerfile
- Backendのtest・buildを行うGitHub Actions workflow
- GCP構成値と削除手順を記載したREADME

## Out of Scope

- Terraformによる環境構築
- Production環境、HA、read replica、独自domain、SLA設計
- Cloud SQLの自動バックアップを前提にした本番復旧設計
- Goal Planner ServiceのCloud Runデプロイ
- OpenAI APIを使ったクラウド結合
- Googleログイン、電話番号認証
- Cloud Tasks、Pub/Subによる非同期処理
- 課金アラートによるprojectの自動停止
- Cloud Run自動deployとWorkload Identity Federation（Weekend 6へ延期）
- Cloud Runの無効化・再有効化とCLI再deployの重複実習
- Weekend 5中のクラウドリソース実削除

## Done

- BackendのRuff、pytest、サービス間E2Eが成功する。
- Androidのunit testとDebug/Release buildが成功する。
- 認証済みAndroidからCloud Run、Cloud SQLを通るGoal操作を実機またはEmulatorで確認する。
- 短縮実行パスのユーザー操作とCodex検証が完了し、証跡がステータス台帳に残っている。
- `stop-dev.sh`実行後、Cloud SQLが停止し、Cloud Runに課金を生む常駐instanceがないことをCLIで確認する。
- `destroy-dev.sh`をdry-runまたは一覧表示モードで確認し、削除対象が開発環境だけに限定されている。
- 次の開発対象をWeekend 6の進捗記録として明記する。
