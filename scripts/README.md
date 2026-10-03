# Development Scripts

複数コンポーネントの起動、検証、デプロイを補助する小さなスクリプトを配置します。スクリプトは対話操作に依存せず、READMEにある標準コマンドを呼び出す形にします。

GitHub Actionsでは、`.github/workflows/deploy-dev.yml`で開発環境へdeployし、`.github/workflows/manage-dev.yml`で状態確認、開始、停止を実行します。

- `test-weekend-3.sh`: 隔離されたPostgreSQL、独立Fake Goal Planner、Core APIを通すキー不要E2E
- `start-auth-emulator.sh`: Firebase Authentication Emulatorをローカルで起動
- `gcp/status-dev.sh`: Weekend 5 GCP開発環境の状態を変更せず表示
- `gcp/start-dev.sh`: Cloud SQLを開始し、Cloud Runのhealthを確認
- `gcp/stop-dev.sh`: Cloud SQLを停止し、Cloud Runがmin 0 / max 1であることを確認
- `gcp/setup-dev-deploy.sh`: Weekend 6のWorkload Identity Federation、deploy用service account、GitHub Variablesを設定
- `gcp/register-openai-secret.sh`: `.secrets/openai-api-key.txt`の改行を除去してSecret Managerへ登録し、権限を更新
- `gcp/destroy-dev.sh`: 削除対象を表示。`--execute`と二重の対話確認がある場合だけ完全削除
- `gcp/apply-budget-alerts.sh`: `infra/gcp/budget-alerts.json`に定義した未作成のalerts-only予算を作成

日常の開始・終了では次を使用します。

```bash
./scripts/gcp/start-dev.sh
./scripts/gcp/stop-dev.sh
```

課金アラートは次で適用します。同じ表示名の予算がすでにある場合は重複作成しません。

```bash
./scripts/gcp/apply-budget-alerts.sh
```

Weekend 6のdeploy権限とGitHub Variablesは次で設定します。

```bash
./scripts/gcp/setup-dev-deploy.sh
```

OpenAI API keyを登録またはrotateする場合は、`.secrets/openai-api-key.txt`に保存したうえで次を実行します。末尾改行は登録前に除去されます。

```bash
./scripts/gcp/register-openai-secret.sh
```

削除候補の確認は引数なしで実行します。このコマンドだけでは削除されません。

```bash
./scripts/gcp/destroy-dev.sh
```

## Weekend 7のローカル確認

Docker、uv、起動済みAndroidエミュレータを用意します。

```bash
./scripts/test-weekend-7.sh
ANDROID_HOME="$HOME/Library/Android/sdk" ./scripts/test-weekend-7.sh --android
```

専用PostgreSQL（15437）とFake認証・Fake AIのCore API（18007）を起動し、終了時に専用コンテナとデータを削除します。通常の開発DBは使いません。ポート使用中は停止し、既存プロセスを終了しません。Android確認はadb reverseを使います。スクリーンショットとinstrumentation結果は`android/app/build/weekend7/`（`W7_OUTPUT_DIR`で変更可能）へ保存されます。クラウドFirebase・Cloud Runの確認はこのテストに含みません。
