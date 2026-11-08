# 検証手順

[開発からリリースまで](development-workflow.md)の各段階で、Agentとユーザーが実施する検証です。
開発中は変更部分を中心に確認し、PRではActionsで共通の検査を行います。

## 環境の役割

| 環境 | 役割 |
| --- | --- |
| Mac | 実装・ローカルテスト・Androidの操作確認。必要な実AI評価、配備後のアプリ確認 |
| Codex Cloud | 実装・実行可能なローカルテスト・Draft PR作成・Actions結果の確認。GCP認証・Android SDKの有無を確認し、不足時は検証を引き継ぐ |
| GitHub Actions | PRのBackend・Android検査。mainマージ後のGCP配備・候補版検証 |

PRのAndroidとBackendは同じcheckoutから起動します。接続先はActions内の開発版Core APIと
Firestore Emulatorで、AI・認証はFakeです。稼働中のGCP、実Firebase、実OpenAI、専用アカウントを使いません。
Firestore Emulatorの起動にgcloudとJavaを使いますが、GCPへのログインや接続は不要です。

## 変更別の確認

| 変更内容 | 開発中・マージ前に必要な確認 |
| --- | --- |
| Core API・保存・認可 | Core APIのlint・pytest。保存・再送・競合変更はHTTP競合テストも実施 |
| 内部AIサービス・通信 | Goal Plannerのlint・通常pytest。境界変更はサービス結合テストも実施 |
| Android・公開API・保存形式 | Android単体・lint・ビルドと、同じコミットのBackendとの端末結合テスト。既存アプリ・保存データとの互換性も確認 |
| 画面・通知・音声・ログイン | 上記に加え、自動テスト外の変更した操作をMacで確認。Google認証はローカルAuth Emulatorの専用手順を利用 |
| プロンプト・モデル・AI出力の解釈 | Fakeの通常テストに加え、実AIの読書・アプリ開発・筋トレ等の既存回帰と生成内容を確認。通常PR Actionsとは分けて明示実行 |
| デプロイ処理 | `tests/deployment`と関連lint。GCPでの権限・索引・実AI疎通はマージ後の候補版検証 |
| 文書のみ | 記述・コマンド・リンクの整合を確認。無関係な動作テストを追加しない |

コード変更PRの標準はBackend QualityとAndroid Qualityの成功です。チェックの必須化はGitHubの
branch protection / rulesetでも設定します。旧Android GCP Checkを必須にしていた場合はAndroid Qualityへ更新してください。

## 標準コマンド

リポジトリルートで実行します。Pythonはuv、AndroidはGradle Wrapperを使います。

```sh
# Core APIとデプロイ補助の検査。pytestが一時Firestore Emulatorを起動する。
cd backend/services/core-api
uv run --frozen ruff check --config pyproject.toml . ../../../scripts/gcp/deploy-dev.py ../../../scripts/check-deployment.py ../../../tests/deployment
uv run --frozen pytest tests ../../../tests/deployment
cd ../../..

# 内部AIサービス。通常テストは実OpenAIを呼ばない。
cd backend/services/goal-planner
uv run --frozen ruff check .
uv run --frozen pytest -m 'not live_ai'
cd ../../..

# 独立Fake Goal Planner → Core API → 一時DB
./scripts/test-weekend-3.sh
# Core APIと一時DBを通す同時保存・再送
./scripts/test-weekend-7.sh

# Android単体・lint・アプリ/テストAPKビルド（端末不要）
cd android
./gradlew -Plifeagent.useCloudFirebase=false testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
cd ..
# 起動済みAndroid Emulatorで、上記に加えて開発版Core APIへの端末結合テスト
./scripts/test-weekend-7.sh --android
```

Android結合の前提はJava 21、Android SDK/adb、起動済みEmulator、uv、Firestore Emulatorです。
`ANDROID_HOME`をSDKへ設定し、複数端末があれば`ANDROID_SERIAL`で選びます。
`--android`は同じcheckoutのCore APIを18007で起動し、`adb reverse`で端末のloopbackへ接続します。
既定は`AppFlowTest`、`MetricTypesFlowTest#typedEntriesPersistAndCoachRecordsAnObservation`、
`GoalCompletionFlowTest`です。実AI用・GCP用のテストは選択しません。
終了時は今回起動したAPI・一時DB・ポート転送を片付け、結果を`android/app/build/weekend7/instrumentation.txt`へ残します。

Cloud等でgcloudがなければ、環境で用意したloopbackのFirestore Emulatorを
`FIRESTORE_EMULATOR_HOST=127.0.0.1:<port>`で指定できます。専用の`demo-`プロジェクトと
デフォルトDBを使い、通常の開発DBやクラウドDBへ向けません。用意できなければDB検査を未実施と記録します。
ローカルの`.env`にクラウド向け接続・許可UID設定を入れたままテストしないでください。

Google認証と実AIの明示実行は[Android](../android/README.md)・
[Goal Planner](../backend/services/goal-planner/README.md)の専用手順を参照してください。

## マージ後の確認

`deploy-dev.yml`はマージしたコミットでBackend検査を再実行し、実GCPの候補版を確認してから
切り替えます。IAM・索引・実AI疎通・保存・再送を確認し、通常の失敗時は旧配信先へ復旧します。
DBの書込みは巻き戻りません。詳細は[GCP運用](../infra/gcp/README.md)を参照してください。
その後はMacのエミュレーターへ通常のクラウド接続debugアプリを入れ、本人アカウントで
[手動確認](android-gcp-check.md)します。検証専用アカウント・Secret・追加UID許可・検証用WIFは不要です。

## 結果の記録

PRへ次を記載し、修正後は影響する検査を再実行します。

- 対象コミット、実行環境、実行コマンドまたはActions runへのリンク
- 接続先とFake／実AIの区別、成功・失敗・未実施・skip
- 未実施の理由と、次にどの環境で何を確認するか

例: 「Backendテスト: Cloudで成功。Android端末結合: Actionsで確認待ち（CloudにEmulatorなし）。
実AI評価: AI変更なしのため対象外。GCP: マージ後の候補版検証で確認」。
Draft PR作成、マージ可能、Backend反映完了、リリース確認完了を分け、未実施を成功に含めません。
