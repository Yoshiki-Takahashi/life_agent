# 週末開発手順書

## 開発方針

最初にローカルで動く最小製品を完成させ、その後に実AI、認証、GCP、進捗管理を順に追加します。各週末は単独でデモ可能な状態で終了し、未完成の大きな機能を持ち越しません。

サービスは独立したディレクトリ、依存関係、テスト、コンテナを持ちます。ただし、初期から細かく分散すると完成が遅れるため、最初はCore APIとFake Plannerで縦切りを完成させます。Goal Plannerは契約を保ったまま後から独立サービスへ差し替えます。

## サービス境界

```text
Android App
    ↓ 公開HTTP API
Core API ──→ Goal Planner Service ──→ OpenAI API
    ↓
PostgreSQL
```

- AndroidはCore APIだけを呼び出す。
- Core APIだけがアプリケーションDBを所有する。
- Goal Plannerは状態を持たず、Goalを保存しない。
- サービス間連携は`contracts/`のSchemaに従う。
- Progress Parser、Advisor、Re-plannerは必要になるまで分離しない。負荷、変更頻度、障害分離の必要性が明確になった時点で抽出する。

## 各作業単位の共通手順

1. `docs/backlog/`にユーザーストーリー、受け入れ条件、対象外を書く。
2. APIまたはSchemaの契約を先に決める。
3. 最小の失敗テストを追加する。
4. 一つの利用経路だけを実装する。
5. lint、型検査、テストを実行する。
6. AndroidまたはAPIからデモし、READMEを更新する。
7. 次回作業がなくてもリポジトリが起動可能な状態で終了する。

## 開発ロードマップ

Weekend 4.Aまで実装・検証済みです。次の作業はWeekend 5のGCP開発環境です。

### Weekend 0: 開発基盤

**状態:** 完了

**完成状態:** AndroidとCore APIがそれぞれ起動し、Backendのhealth checkを確認できる。

- AndroidプロジェクトとGradle Wrapperを作成する。
- Core APIをuvプロジェクトとして作成する。
- `GET /health`とテストを追加する。
- ローカル起動手順と共通品質コマンドを記載する。

この段階ではDB、Firebase、OpenAIを接続しません。

### Weekend 1: 最小Goal管理

**状態:** 完了

**完成状態:** AndroidでGoalを入力し、保存されたGoal詳細を表示できる。

- Goalの最小SchemaとAPI契約を定義する。
- ローカルPostgreSQLとAlembicを導入する。
- Goal作成・取得APIを実装する。
- AndroidにGoal入力・詳細画面を作る。

Metric、Milestone、AI、ログインは対象外です。

### Weekend 2: Fake計画生成による最小製品

**状態:** 完了

**完成状態:** Goal入力からMetric・Milestone生成、確認、保存、表示まで一周できる。

- Goal Planner契約をJSON Schemaで定義する。
- Core APIに決定論的なFake Plannerを実装する。
- 計画プレビューと確定保存を分離する。
- Androidに計画確認画面を追加する。
- 最初のE2Eシナリオを追加する。

ここが最初のMVP完成点です。以降はこの動作を壊さず拡張します。

### Weekend 3: 独立Goal Planner

**状態:** 完了

**完成状態:** Fakeと同じ契約で、独立したGoal Planner Serviceから計画を取得できる。

- Goal Plannerを個別のuvプロジェクトとして初期化する。
- OpenAI Structured Outputsを実装する。
- 読書、アプリ開発、筋トレの回帰評価を追加する。
- Core APIからの内部HTTP呼び出しを実装する。
- 障害時は保存せず、再試行可能なエラーを返す。

通常テストは引き続きFakeを使い、実AI評価は明示的に実行します。

### Weekend 3.1: Goal Planner開発基盤の仕上げ

**状態:** 完了

**完成状態:** Tokenを消費せずにCore API、独立Goal Planner、PostgreSQLを通すE2Eを一つのコマンドで再現でき、Goal Plannerコンテナが依存関係を再同期せず起動する。

- 独立Goal PlannerへE2E専用の決定論的Fake Providerを追加する。
- 通常環境とポート・DBを分離したサービス間E2Eを追加する。
- Goal PlannerのDocker build contextと起動処理を最小化する。

実AI品質評価は追加せず、明示的な既存評価以外ではOpenAI APIを呼びません。

### Weekend 4: 認証

**状態:** 完了

**完成状態:** ログインしたユーザーだけが、自分のGoalを操作できる。

- Firebase AuthenticationをAndroidへ追加する。
- Core APIでFirebase ID Tokenを検証する。
- 全データへ所有者IDを追加する。
- 他ユーザーのGoalを取得できないテストを追加する。

最初はメールアドレスとパスワードで開始し、電話番号認証とGoogleログインは後続にします。

### Weekend 4.A: Android Goal体験の拡充

**状態:** 完了

**完成状態:** 認証後に自分のGoal一覧へ入り、作成、計画確認、保存、詳細表示、一覧への再訪を一周できる。

- 所有者で絞ったGoal一覧APIを追加する。
- Androidの開始画面をGoalホームにし、空・読込・失敗状態を整える。
- 作成、計画確認、詳細を画面単位に分け、Material 3の表現を統一する。
- 保存後と詳細閲覧後に一覧へ戻れる導線を追加する。

進捗率は正本データがないため表示せず、進捗記録はWeekend 6で追加します。

### Weekend 5: GCP開発環境

**完成状態:** AndroidからCloud Run上のCore APIへ接続し、クラウドDBへGoalを保存できる。

- Artifact RegistryとCloud Runを作成する。
- Secret Managerへ必要な秘密情報を登録する。
- 最小構成のCloud SQLを必要な期間だけ作成する。
- GitHub Actionsからテスト・デプロイする。
- 予算アラート、Cloud Run最大インスタンス数、削除手順を設定する。

Goal Plannerは最初はローカルまたはFakeでもよく、Core APIのクラウド経路を先に完成させます。

### Weekend 6: 進捗記録

**完成状態:** テキストで進捗を登録し、Goal詳細で履歴とMetric更新を確認できる。

- ProgressLog SchemaとAPIを追加する。
- 最初は構造化フォームまたはFake Parserで保存する。
- Androidに進捗入力と履歴表示を追加する。
- Goal所有者とMetric整合性を検証する。

### Weekend 7: AI進捗解析と助言

**完成状態:** 自然言語の進捗をMetricへ対応付け、次の行動を提案できる。

- Progress ParserとAdvisorのPortをCore APIに追加する。
- FakeでE2Eを完成させてから実AI Adapterを追加する。
- AI出力不正時に元データを壊さないテストを追加する。
- 助言を表示するAndroid UIを追加する。

独立サービス化は、Goal Plannerと異なるデプロイ頻度または負荷が確認できた場合に行います。

### Weekend 8以降: 段階的な強化

- 計画変更の比較・確認・Re-planner
- 可視化タイプの追加
- 通知と定期振り返り
- 音声入力
- Cloud Tasks等による非同期処理
- Terraformによる環境再現

毎回、一つのユーザー経路をFakeで完成させてから外部サービスへ接続します。

## サービスを分離する判断基準

以下のいずれかが具体的に発生した場合だけ、新しいデプロイ単位へ分離します。

- 他機能と異なるスケーリングが必要
- 障害やタイムアウトを隔離する必要がある
- 独立したリリース頻度が必要
- 個別の秘密情報や権限境界が必要
- 独立した性能・品質評価が必要

コード量だけを理由にサービスを増やしません。まずCore API内のPortとして分離し、契約テストを整えてからプロセスを分けます。

## 各週末の完了条件

- READMEのコマンドだけで起動できる。
- 関連するlint、型検査、テストが成功する。
- API契約と実装が一致する。
- 秘密情報やローカル固有値をコミットしていない。
- 作業途中でも既存のデモ経路が壊れていない。
- 次回の最初の作業がバックログに一つだけ明記されている。
