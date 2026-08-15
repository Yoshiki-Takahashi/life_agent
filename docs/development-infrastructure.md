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
| Database | PostgreSQL、SQLAlchemy 2、Alembic | 通常はローカル |
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
| Cloud Run | FastAPIの結合確認環境 | request-based billing、最小インスタンス0、最大1〜2 |
| Artifact Registry | Backendのコンテナ保存 | 古いイメージを自動削除し、保存量を抑える |
| Firebase Authentication | Google/メール認証 | SMS課金のある電話番号認証は使用しない |
| Secret Manager | OpenAI APIキーなどの保存 | 有効なSecret versionを最小限にする |
| Cloud Logging | Cloud Runのログ | DEBUGログを常用せず、機密情報を出力しない |
| OpenAI API | 実AIによる限定的な結合・回帰評価 | 通常テストはFakeを使用し、呼び出し回数を制限する |

Cloud Run、Artifact Registry、Secret Manager、Cloud Loggingは無料枠超過後に従量課金されます。OpenAI APIはGCPとは別に従量課金されます。

## 必要になるまで作成しないリソース

### Cloud SQL for PostgreSQL

Cloud SQLはCPU、メモリ、ストレージの継続費用が発生するため、通常開発ではローカルPostgreSQLを使用します。クラウド上で永続化を含むE2E確認が必要になった時点で、以下の最小構成から開始します。

- 東京リージョン
- Enterprise editionの最小構成
- Single Zone
- High Availabilityなし
- 開発用データのみ

停止中もストレージ等の費用が残り得るため、長期間使わない開発インスタンスはバックアップ要否を確認して削除します。

### 後続フェーズの候補

- Cloud Tasks / Pub/Sub: 非同期AI処理が必要になった場合
- Cloud Scheduler: 定期的な振り返り処理が必要になった場合
- Cloud Storage: 画像、音声、エクスポートファイルを扱う場合
- Terraform: クラウド構成が安定し、複数環境を再現する段階

## 開発環境の段階

1. **Local:** Android Emulator、FastAPI、PostgreSQL、Fake AI
2. **Cloud API:** AndroidからCloud Runへ接続。DBはまだ機能範囲に応じて限定する
3. **Cloud Integration:** Cloud Run、Cloud SQL、Firebase Auth、実OpenAIを接続
4. **Production:** 可用性、バックアップ、監視、セキュリティを再評価して本番構成を決定

## コスト管理ルール

- GCPの予算アラートを低額で設定する。予算アラートは自動的な利用停止ではない点に注意する。
- Cloud Runの最小・最大インスタンス数を明示する。
- 実AIテストは手動または専用CIに分離する。
- 開発環境に本番相当のHAや常時稼働リソースを作らない。
- リソース作成時は、削除条件と担当チケットを同時に記録する。
- 月ごとにBillingレポートを確認する。

料金は変更されるため、作成前に公式の[Cloud Run料金](https://cloud.google.com/run/pricing)、[Cloud SQL料金](https://cloud.google.com/sql/pricing)、[Firebase料金](https://firebase.google.com/pricing)を確認します。
