# 週末開発手順書

## 開発方針

最初にローカルで動く最小製品を完成させ、その後に実AI、認証、GCP、進捗管理を順に追加します。各週末は単独でデモ可能な状態で終了し、未完成の大きな機能を持ち越しません。

サービスは独立したディレクトリ、依存関係、テスト、コンテナを持ちます。ただし、初期から細かく分散すると完成が遅れるため、最初はCore APIとFake Plannerで縦切りを完成させます。Goal Plannerは契約を保ったまま後から独立サービスへ差し替えます。

## サービス境界

全Sprintに適用する配置・拡張・設計変更の判断は[サービス設計・拡張指針](service-design-guidelines.md)を参照します。

```text
Android App
    ↓ 公開HTTP API
Core API ──→ Goal Planner Service ──→ OpenAI API
    ↓
PostgreSQL
```

- Androidの業務通信はCore APIへ集約する。認証はFirebase Authenticationを使う。
- Core APIだけがアプリケーションDBを所有する。
- Goal Planner / AI接続サービスは状態を持たず、初期計画・進捗解析・助言の生成を担う。Goalを保存しない。
- サービス間連携は`contracts/`のSchemaに従う。
- Progress Parser、Advisor、Re-plannerは機能別の責務を分け、実AI生成は既存のAI接続サービスへ寄せる。新しいデプロイ単位への分離は共通指針の条件を満たす場合に検討する。

## 各作業単位の共通手順

1. 共通のサービス設計指針を確認し、`docs/backlog/`にユーザーストーリー、受け入れ条件、対象外、責務の配置先を書く。
2. APIまたはSchemaの契約を先に決める。
3. 最小の失敗テストを追加する。
4. 一つの利用経路だけを実装する。
5. lint、型検査、テストを実行する。
6. AndroidまたはAPIからデモし、READMEを更新する。
7. 次回作業がなくてもリポジトリが起動可能な状態で終了する。

## 開発ロードマップ

Weekend 6の自動デプロイ、Goal Plannerクラウド結合、手入力進捗記録まで実装済みです。旧キー失効確認等の運用残件は[`Weekend 6ステータス`](backlog/weekend-6-status.md)で追跡します。Weekend 7はクラウド受け入れとADR 0002に基づくAI接続責務の移行まで完了しました。[検証結果](backlog/weekend-7-status.md)を参照してください。次はWeekend 8の計画変更差分Schemaを定義します。現在の計画は[`backlog/README.md`](backlog/README.md)を参照してください。Weekend番号は実施順を示し、日程の確約ではありません。

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

最初はメールアドレスとパスワードで開始します。GoogleログインはWeekend 13、電話番号認証は同枠で必要性と費用を評価して採否を決めます。

### Weekend 4.A: Android Goal体験の拡充

**状態:** 完了

**完成状態:** 認証後に自分のGoal一覧へ入り、作成、計画確認、保存、詳細表示、一覧への再訪を一周できる。

- 所有者で絞ったGoal一覧APIを追加する。
- Androidの開始画面をGoalホームにし、空・読込・失敗状態を整える。
- 作成、計画確認、詳細を画面単位に分け、Material 3の表現を統一する。
- 保存後と詳細閲覧後に一覧へ戻れる導線を追加する。

進捗率は正本データがないため表示せず、進捗記録はWeekend 6で追加します。

### Weekend 5: GCP開発環境

**状態:** 完了

**完成状態:** AndroidからCloud Run上のCore APIへ接続し、クラウドDBへGoalを保存できる。

詳細な受け入れ条件は[`backlog/weekend-5.md`](backlog/weekend-5.md)、UIとCLIの実習順序は[`infra/gcp/weekend-5-runbook.md`](../infra/gcp/weekend-5-runbook.md)、進捗は[`backlog/weekend-5-status.md`](backlog/weekend-5-status.md)で管理する。

- Artifact RegistryとCloud Runを作成する。
- Secret Managerへ必要な秘密情報を登録する。
- 最小構成のCloud SQLを必要な期間だけ作成する。
- GitHub Actionsで品質検査する。自動デプロイとWorkload Identity FederationはWeekend 6の前半で実施する。
- 予算アラート、Cloud Run最大インスタンス数、削除手順を設定する。

Goal Plannerは最初はローカルまたはFakeでもよく、Core APIのクラウド経路を先に完成させます。

### Weekend 6: 開発環境自動デプロイ、クラウド実AI計画生成、進捗記録

**完成状態:** 長期サービスアカウントキーなしで開発環境へデプロイでき、AndroidからCloud Run上のGoal Plannerと実AIで計画を確認・保存し、その後に進捗本文と構造化したMetric値を登録して履歴と現在値を確認できる。

- Workload Identity Federationと手動dispatchの開発環境deploy workflowを追加する。
- Goal Plannerを非公開Cloud Runへ配置し、Core APIから認証付きで呼び出す。
- OpenAI APIキーをSecret Managerで管理し、Fake切替・費用抑制・停止手順を整える。
- ProgressLog Schema、API、migrationを追加する。
- Androidに本文・Metric値の入力と履歴表示を追加する。
- Metric更新の意味、再送時の重複防止、所有者検証を契約化する。
- 自然言語からの値の抽出はWeekend 7で追加する。

詳細は[`backlog/weekend-6.md`](backlog/weekend-6.md)を参照してください。

### Weekend 7: AI進捗解析と助言

**完成状態:** 自然言語の進捗をMetric更新候補へ変換し、確認・保存後に次の行動を提案できる。

Progress ParserとAdvisorの呼出し境界と業務検証をCore API内で分け、生成処理はGoal Planner / AI接続サービスに配置します。Fakeで利用経路を完成させてから実AI Adapterを追加します。新しいデプロイ単位への分離は共通指針の判断基準を満たした場合だけ行います。

詳細は[`backlog/weekend-7.md`](backlog/weekend-7.md)を参照してください。この段階で、プロダクト概要にある進捗記録と助言を含むMVPの利用経路が揃います。

### Weekend 8以降: 段階的な強化

| 実施枠 | 内容 | 完成状態 |
| --- | --- | --- |
| Weekend 8 | Re-planner、計画差分の比較・確認 | ユーザーの承認後だけ計画を更新できる |
| Weekend 9 | 進捗可視化 | 保存済みの履歴とMetricに基づく推移を確認できる |
| Weekend 10 | 定期振り返り・通知 | 利用者が設定したタイミングで振り返れる |
| Weekend 11 | 音声入力 | 音声から起こした本文を確認して進捗登録できる |
| Weekend 12 | 非同期処理・環境再現 | 必要性を評価し、必要な場合にCloud Tasks等とTerraformを導入する |
| Weekend 13 | 認証方式の拡充 | Googleログインを追加し、電話番号認証の採否を決める |
| Weekend 14 | 本番化判断・運用整備 | 公開要否を判断し、公開する場合の復旧・監視・セキュリティ条件を満たす |

詳細なストーリー、受け入れ条件、対象外は[`backlog/weekend-8-plus.md`](backlog/weekend-8-plus.md)にまとめます。MVP対象外の拡張であり、着手前に利用実績をもとに範囲を再確認します。条件付き項目は採否と理由の記録も完了条件とし、必要性のない基盤は作成しません。

毎回、一つのユーザー経路をFakeで完成させてから外部サービスへ接続します。

## サービスを分離する判断基準

[共通指針の分離条件](service-design-guidelines.md#新しいサービスへ分離する条件)を適用します。まず責務に合う既存サービス内で機能を分け、具体的な必要性と運用コストを評価し、契約テストを整えてから実行単位を分離します。

## 各週末の完了条件

- READMEのコマンドだけで起動できる。
- 関連するlint、型検査、テストが成功する。
- API契約と実装が一致する。
- 秘密情報やローカル固有値をコミットしていない。
- 作業途中でも既存のデモ経路が壊れていない。
- 次回の最初の作業がバックログに一つだけ明記されている。
