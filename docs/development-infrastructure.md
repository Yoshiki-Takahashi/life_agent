# 開発フェーズのリソースと技術構成

## 方針

開発中はローカル実行と無料枠を優先し、常時課金されるリソースは必要になるまで作成しません。本番に近い確認が必要な機能だけをGCPへデプロイします。

## 技術コンポーネント

| 領域 | 採用技術 | 開発時の実行場所 |
| --- | --- | --- |
| Android | Kotlin、Jetpack Compose、ViewModel、StateFlow | Android Emulator / 実機 |
| API通信 | Retrofit、Kotlin Serialization | Androidアプリ内 |
| Backend | Python 3.13、FastAPI、Pydantic v2 | ローカル / Cloud Run |
| Python管理 | uv | ローカル / CI |
| Database | Firestore Standard / Native mode、サーバーSDK | ローカル・CIはEmulator |
| 認証 | Firebase Authentication | Firebase |
| AI | OpenAI API、Structured Outputs | FastAPIから呼び出す |
| AIテスト | 決定論的なFake実装 | ローカル / CI |
| コンテナ | Docker | ローカル / Artifact Registry |
| CI/CD | GitHub Actions | GitHub |
| ログ | Python logging、Cloud Logging | ローカル / GCP |

AI APIキーをAndroidへ含めません。AndroidはFirebase ID TokenをFastAPIへ送り、Backendが認証、DB操作、AI呼び出しを担当します。

## 開発中に使用するクラウドリソース

| サービス | 用途 | 課金抑制方針 |
| --- | --- | --- |
| Firestore Standard | Core APIの正本データ | 通常無料枠内を目標とし、不要な索引・全件取得を減らす |
| Cloud Run | FastAPIの結合確認環境 | request-based billing、最小インスタンス0、最大1〜2 |
| Artifact Registry | Backendのコンテナ保存 | 古いイメージを自動削除し、保存量を抑える |
| Firebase Authentication | Google/メール認証 | SMS課金のある電話番号認証は使用しない |
| Secret Manager | OpenAI APIキーなどの保存 | 有効なSecret versionを最小限にする |
| Cloud Logging | Cloud Runのログ | DEBUGログを常用せず、機密情報を出力しない |
| OpenAI API | 実AIによる限定的な結合・回帰評価 | 通常テストはFakeを使用し、呼び出し回数を制限する |

Cloud Run、Artifact Registry、Secret Manager、Cloud Loggingは無料枠超過後に従量課金されます。OpenAI APIはGCPとは別に従量課金されます。

## Firestoreと移管期間

`lifeagent-505614`の`(default)`を東京`asia-northeast1`、Standard / Native modeで使用します。
2026-11-03にDB・IAM・Rules・索引を準備し、23:39（日本時間）にFirestore版へ切り替えました。
当初の48時間保持はユーザーの即時削除指示で撤回し、旧Cloud SQLを同日23:48に削除しました。
SQL専用Job・Secret・権限・GitHub変数・予算も撤去済みです。請求表示への反映は後日確認します。
設計は[ADR 0003](decisions/0003-firestore-standard.md)、実施条件は[移管計画](plans/firestore-standard-migration.md)を参照してください。

無料枠は保存1GiB、日次読取り5万・書込み2万・削除2万、外向き転送10GiB/月です。
課金上限ではなく、超過分には料金が発生し得ます。Core APIだけがDBに接続し、クライアント向けRulesは全拒否です。
定期バックアップ・PITRは設けず、切替時の旧SQL dumpだけをローカルPCに保管します。
保存期限はユーザーが管理します。Firestoreの新規データはこのdumpから戻せません。

Cloud Runの停止ではFirestoreを削除しません。`stop-dev.sh`はサービスをmanual 0にし、
`start-dev.sh`はauto/min 0/max 1に戻します。旧SQLの再作成・再起動は行いません。

### 後続フェーズの候補

今後の開発は[音声入力（Weekend 10 / #19）](https://github.com/Yoshiki-Takahashi/life_agent/issues/19)と
[Google認証（Weekend 11 / #20）](https://github.com/Yoshiki-Takahashi/life_agent/issues/20)です。
音声入力にはAndroid標準の認識サービスを使い、新規クラウドリソースは作成しません。
基盤強化と本番化は予定に含めません。Cloud Tasks、Terraform等は、今後のIssueで必要性が示された場合に個別に検討します。
端末内の既存通知にCloud Schedulerは使用しません。

## 開発環境の段階

1. **Local:** Android Emulator、FastAPI、Firestore Emulator、Fake AI
2. **Cloud API:** AndroidからCloud Runへ接続。DBはまだ機能範囲に応じて限定する
3. **Cloud Integration:** Cloud Run、Firestore、Firebase Auth、実OpenAIを接続

AndroidのCore API接続先は、`android/config/api-debug.properties`と`api-release.properties`でビルド種別ごとに管理します。debugはEmulatorからローカルFastAPIへ、releaseはCloud Runへ接続します。Cloud Run作成後にrelease設定を発行済みHTTPS URLへ更新します。APIキーやDB接続情報はAndroidへ含めません。

## コスト管理ルール

- GCPの予算アラートを低額で設定する。予算アラートは自動的な利用停止ではない点に注意する。
- Cloud Runの最小・最大インスタンス数を明示する。
- 実AIテストは手動または専用CIに分離する。
- 開発環境に本番相当のHAや常時稼働リソースを作らない。
- リソース作成時は、削除条件と担当チケットを同時に記録する。
- 月ごとにBillingレポートを確認する。

料金は変更されるため、作成前に公式の[Cloud Run料金](https://cloud.google.com/run/pricing)、[Firestore料金](https://firebase.google.com/docs/firestore/pricing)、[Firebase料金](https://firebase.google.com/pricing)を確認します。
