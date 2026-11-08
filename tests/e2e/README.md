# 結合テスト

通常のAPIテストと重複する画面・フィールドごとのケースは置きません。
外部境界が必要な次の確認だけを残します。いずれも専用の一時DBを使います。

- `test_services.py`: Core → 内部AIサービス → Firestore Emulator。固定入出力と確認後の保存。
  リポジトリルートで`./scripts/test-weekend-3.sh`。
- `test_progress_concurrency.py`: Firestore transactionと保存IDによる同時実行・重複防止。
  別IDの6件同時保存では目標値を超えても現在値・全履歴が揃うことを確認する。
  `./scripts/test-weekend-7.sh`。
- Androidの`AppFlowTest`: 一つのユーザー経路で全目標対話・確認・再送・ナビゲーションを検証。
  起動済みEmulatorで`ANDROID_HOME=... ./scripts/test-weekend-7.sh --android`。
- `weekend8_cloud.py`: 明示実行専用のクラウド診断。通常CI・ローカル検証では実行しない。

実AIの読書・開発・筋トレ回帰は内部AIサービスの`tests/test_live.py`、詳細な品質評価は
同サービスの`evaluations/`にあります。通常テストはFakeで、実AIは明示実行のみです。

Java 21とgcloudの`cloud-firestore-emulator` componentが必要です。
各スクリプトはloopbackのEmulatorを一時起動し、ランダムな`demo-`プロジェクトを使います。
Emulatorのロック解放には最大30秒程度かかる場合があり、HTTP競合テストのtimeoutは240秒です。
これは実環境の性能基準ではありません。索引・IAMは実Firestoreで別途確認します。
実Firestore用の`check-firestore-api.py`は非公開Cloud Run Job専用で、Fake AIと検証専用UIDを使い、作成データだけを終了時に削除します。

`firestore_cloud.py`は`firestore-check`タグの候補URLと公開Firebase APIキーを環境変数で指定する明示実行用です。
実Firebase利用者を一時作成し、既存のAI provider設定でAPIを一周した後、作成したGoalと一時利用者だけを削除します。
Core実行アカウントのIAM検証は別のCloud Run Jobで行います。通常CIでは実AIもクラウドも呼びません。

Androidの`CloudPersistenceTest`は明示実行専用です。APKをbuild/installしたうえで、
`uv run --project backend/services/core-api python scripts/gcp/check-android-cloud.py --firebase-sdk <firebase apps:sdkconfigのJSONファイル>`
をリポジトリルートから実行します。実Firebaseの一時利用者を作成し、Tokenをアプリの非公開一時ファイル経由で渡します。
Tokenは引数・ログへ出さず、テスト終了後に検証用Goal/実績/候補と一時利用者を削除します。通常CIはクラウドを呼びません。

Google連携の`./scripts/test-google-auth.sh`もFirestore Emulatorを自動起動します。
Auth Emulatorを別途起動し、同じFirebase uidのGoal保持・Google連携・再ログイン・他所有者404を確認します。
ローカル検証用Gradleは`-Plifeagent.useCloudFirebase=false`を明示し、端末確認用の既定値に依存しません。

専用アカウントによるPR版Android→現行GCPの検査は[Android GCP検証手順](../../docs/android-gcp-check.md)を参照。
未設定時のcloud jobはskipで、実GCP確認済みとは扱わない。
