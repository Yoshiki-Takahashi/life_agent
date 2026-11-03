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
