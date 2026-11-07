# Development Scripts

複数コンポーネントの起動、検証、デプロイを補助する小さなスクリプトを配置します。スクリプトは対話操作に依存せず、READMEにある標準コマンドを呼び出す形にします。

GitHub Actionsでは、`.github/workflows/deploy-dev.yml`で開発環境へdeployし、`.github/workflows/manage-dev.yml`で状態確認、開始、停止を実行します。

- `test-weekend-3.sh`: 隔離されたFirestore Emulator、独立Fake Goal Planner、Core APIを通すキー不要E2E
- `start-auth-emulator.sh`: Firebase Authentication Emulatorをローカルで起動
- `gcp/status-dev.sh`: Weekend 5 GCP開発環境の状態を変更せず表示
- `gcp/start-dev.sh`: Cloud Runをauto/min 0/max 1へ戻す
- `gcp/stop-dev.sh`: Cloud Runをmanual 0へ変更する（Firestoreデータは保持）
- `gcp/setup-dev-deploy.sh`: Weekend 6のWorkload Identity Federation、deploy用service account、GitHub Variablesを設定
- `gcp/register-openai-secret.sh`: `.secrets/openai-api-key.txt`の改行を除去してSecret Managerへ登録し、権限を更新
- `gcp/register-feedback-secret.sh`: `.secrets/feedback-github-token.txt`をCore専用Secretへ登録し、改善メモ用ラベル・デプロイ変数を準備
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

Java 21、gcloud Firestore Emulator、uv、起動済みAndroidエミュレータを用意します。

```bash
./scripts/test-weekend-7.sh
ANDROID_HOME="$HOME/Library/Android/sdk" ./scripts/test-weekend-7.sh --android
```

一時Firestore EmulatorとFake認証・Fake AIのCore API（18007）を起動し、終了時に専用プロセスを終了します。通常の開発DBは使いません。ポート使用中は停止し、既存プロセスを終了しません。Android確認はadb reverseを使います。検証ログとinstrumentation結果は`android/app/build/weekend7/`（`W7_OUTPUT_DIR`で変更可能）へ保存されます。クラウドFirebase・Cloud Runの確認はこのテストに含みません。

## 対話コーチのローカル検証
test-weekend-7.shのAndroid実行を指定クラスに絞るには、
W7_ANDROID_TEST_CLASS=com.yoshiki.lifeagent.AppFlowTestを設定します。
クラウドを起動せず、既存の隔離DBとFake APIで候補取消・修正・保存・参照、
応答喪失後の同一payload再送を検証できます。W7_OUTPUT_DIRで証跡の出力先を変更できます。

start-auth-emulator.shはローカルアカウントを.secrets/firebase-auth-emulator/へ
正常終了時にexportし、次回起動時にimportします。永続化導入前の失われたアカウントは復元できません。
実ユーザーのクラウド認証情報と混同せず、exportデータをGitへ追加しないでください。

## Firestore移管

- `run-with-firestore.py -- <command>`: 一時Emulatorの安全な起動・終了と`demo-`接続設定。
- `gcp/setup-firestore.sh`: 作成済みDBへのCore IAMとRules・索引を配置。
- `gcp/check-firestore.sh`: Standard・Tokyo・無料枠・PITR無効・索引READYを確認。
- `gcp/check-firestore-api.sh`: `CORE_API_IMAGE`の不変digestからCloud Run検証Jobを明示実行。実データは使わない。
- `gcp/backup-legacy-sql.py --output <repo外の絶対パス>`: SQL Auth Proxy（127.0.0.1:15438）経由でローカルdumpし、隔離Docker PostgreSQL 17へ復元検証。パスワードはSecretから環境変数へ一時取得する。

バックアップはローカルPCだけに保管し、保存期限はユーザーが管理します。
切替・ユーザー指示によるSQL即時撤去の記録は[運用手順](../infra/gcp/firestore-migration-runbook.md)を参照してください。

## Google認証のローカル検証

`start-auth-emulator.sh`で認証エミュレーターを起動した状態で、
`ANDROID_HOME="$HOME/Library/Android/sdk" ./scripts/test-google-auth.sh`を実行します。
一時Firestore EmulatorとCore APIを使い、Firebase SDKのGoogle連携・再ログイン・所有者分離を検証します。
Googleのアカウント選択だけを差し替え、実Firebaseや個人のGoogleアカウントは使用しません。
実装と外部設定の受け入れ状況は[Issue #20](https://github.com/Yoshiki-Takahashi/life_agent/issues/20)で管理します。

## main更新後のBackend反映

`.github/workflows/deploy-dev.yml`がBackend検査・image build後に`gcp/deploy-dev.py`を実行します。
同じmainコミットの候補版を確認して切り替え、通常の失敗時は元の配信先へ復旧します。
`check-deployment.py`は非公開のCloud Run Job専用で、候補の実AI連携と検証データの保存・片付けを確認します。
必要な設定と結果の見方は[GCP運用](../infra/gcp/README.md#デプロイ)を参照してください。
