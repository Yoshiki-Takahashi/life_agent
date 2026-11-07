# Backend

Python + FastAPIの独立サービスを配置します。各サービスのPython環境と依存関係はuvで管理します。

- `services/core-api/`: Android向けAPIと正本データの管理
- `services/goal-planner/`: Goalから初期計画を生成するAIサービス

## ローカルFirestore

Google Cloud CLIの`cloud-firestore-emulator` componentとJava 21を用意します。
別ターミナルで次を起動します。終了時はCtrl+Cを使います。既定ではデータは永続化しません。

```bash
gcloud emulators firestore start --host-port=127.0.0.1:8080 --project=demo-lifeagent
```

Core APIには`FIRESTORE_PROJECT_ID=demo-lifeagent`、`FIRESTORE_EMULATOR_HOST=127.0.0.1:8080`を設定します。
`.env.example`をCore APIの作業ディレクトリの`.env`へコピーし、必要な認証設定を調整できます。
Auth Emulatorは別ターミナルで`./scripts/start-auth-emulator.sh`を起動します。
Core APIに`FIREBASE_AUTH_EMULATOR_HOST=127.0.0.1:9099`を設定してください。
秘密情報・`.env`・Emulator exportをGitへ追加しません。

`demo-`以外のプロジェクトでEmulatorへ接続したり、Emulator未設定の`demo-`プロジェクトから
クラウドへ接続したりしないようCoreで検証します。pytestはloopbackの一時Emulatorを自動起動します。
SQLの旧テストデータは移しません。切替状況は[移管計画](../docs/plans/firestore-standard-migration.md)を参照してください。

サービス間ではDBを共有せず、明示的なAPI契約で連携します。Core APIだけがアプリケーションDBを所有します。

## Goal Planner

独立Goal PlannerはOpenAI APIキー取得後に次のように起動します。

```bash
cd backend
docker compose --profile ai up -d goal-planner
```

Core APIの`GOAL_PLANNER_BACKEND=http`と`GOAL_PLANNER_URL=http://127.0.0.1:8001`を設定すると、プレビューだけが内部HTTP経由になります。キー未設定でもサービスのhealth checkと全通常テストは実行できます。

## 改善メモのGitHub連携

GitHubの対象リポジトリだけにアクセスできるfine-grained personal access tokenを用意します。
必要なRepository permissionsは`Issues: Read and write`です。TokenはCoreだけが保持し、Androidへ含めません。
GitHub側でIssuesを有効にし、`feedback`・`needs-triage`ラベルをあらかじめ作成してください。
[GitHub Issue作成API](https://docs.github.com/en/rest/issues/issues#create-an-issue)を使用します。

ローカルのCore `.env`では`FEEDBACK_GITHUB_REPOSITORY=Yoshiki-Takahashi/life_agent`と
`FEEDBACK_GITHUB_TOKEN`を設定します。この設定でアプリから送信すると実際にGitHubへIssueを作成します。
通常のpytestはHTTP境界を差し替え、GitHubへは送信しません。Token未設定でも既存機能とテストは動き、
フィードバック送信だけが503（結果不明ではない設定エラー）になります。

クラウドでは、TokenをGit管理外の`.secrets/feedback-github-token.txt`へ保存して、リポジトリルートから実行します。

```bash
./scripts/gcp/register-feedback-secret.sh
```

このスクリプトは開発者のghログインでラベルを準備し、ファイルのTokenをSecret Managerへ登録します。
Coreの実行アカウントだけにそのSecretの読取り権限を付け、GitHubの`development`環境へ
`FEEDBACK_GITHUB_REPOSITORY`・`FEEDBACK_GITHUB_SECRET_NAME`を設定します。
次のmainデプロイでCoreに`FEEDBACK_GITHUB_TOKEN`をSecretとして割り当てます。
既存のghログイン用Tokenをアプリ実行用にコピーしません。Tokenの更新は同じスクリプトの再実行と再デプロイです。
Secret名を未設定に戻すと、次のデプロイはCoreのToken割当てを外します。

送信結果が不明のときは、同じメモの「送信結果を確認」を選びます。
サーバーはclosedを含むIssueを照会し、本文末尾の`lifeagent-feedback`マーカーで特定します。
一覧照会は初回送信以降、最大500件です。送信控えは`feedback_receipts`にあります。
未作成・未検出でも自動ではIssueを再作成しません。

予約直後のサーバー停止などで解消しない場合は、開発者が次を確認して復旧します。

1. 対象の送信控えの文書IDと初回日時から、GitHubの全状態のIssue本文末尾を確認する。
2. 作成済みなら、該当控えの`status=sent`・`issue_number`を設定する（古いIssueなど照会上限外でも復旧可能）。
3. 未作成の場合は、元の送信処理が終了していることとIssueが存在しないことを確認してから、
   控えの`status=retryable`へ変更し、アプリで同じ内容を再送する。

確認前に控えを削除・再試行可能へ変更すると重複作成の原因になります。
本文を含まない控えも再送防止に使うため自動削除しません。メモ本文・整理・対応状況はGitHubで管理します。
