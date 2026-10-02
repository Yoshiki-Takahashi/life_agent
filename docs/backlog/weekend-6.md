# Weekend 6: 開発環境自動デプロイ、クラウド実AI計画生成、進捗記録

## Status

In Progress

進捗と証跡は[Weekend 6ステータス](weekend-6-status.md)に記録する。

## Sprint Goal / User Story

開発者として、品質検査済みのBackendを長期サービスアカウントキーなしで開発環境へ届け、Androidから実AIの計画生成をクラウド上で確認したい。そのうえで、Goalに取り組むユーザーとして、進捗本文とMetric値を登録し、過去の記録と現在値を確認したい。

Weekend 5から持ち越した6.A相当の自動デプロイと、6.B相当のGoal Plannerクラウド結合をWeekend 6の前半で完了してから、進捗記録へ進む。

## Acceptance Criteria

### 1. 開発環境への自動デプロイ

- GitHub ActionsとGCPをWorkload Identity Federationで接続し、長期サービスアカウントキーを保存しない。
- 対象repository、branchまたはenvironmentを制限し、build、push、deployに必要な権限だけを付与する。
- 品質検査、image build・push、Core API deploy、health確認を手動`workflow_dispatch`で実行できる。PRだけではデプロイしない。
- migrationの実行主体と順序を定め、失敗時には後続deployを止める。前revisionへの復帰とDB変更の互換性・復旧手順を記載する。
- 同時deployを防ぎ、対象project、revision、image digest、結果を秘密情報なしで追跡できる。
- 既存の最大instance制限と開始・停止手順を維持し、検証後の停止を確認する。

### 2. クラウドでの実AI計画生成

- Goal Plannerを非公開Cloud Runへ配置し、Core APIのサービスアカウントだけが認証付きで呼び出せる。
- AndroidはCore APIだけを呼び、Goal PlannerはDBとGoalの正本を持たない。
- OpenAI APIキーをSecret Managerで管理し、Plannerに必要な権限だけを付与する。
- 自動デプロイworkflowをGoal Plannerにも対応させ、両サービスの契約互換性を検証する。
- Structured Outputと業務ルールを検証し、不正出力・タイムアウトではGoalを保存せず再試行可能なエラーを返す。
- Androidから実Firebase認証、実AI計画、確認、保存、再訪を確認する。
- 通常CIはFakeを使い、読書・アプリ開発・筋トレの実AI評価は明示実行に限定する。
- instance数、タイムアウト、呼出し回数の制限を設定し、Fake切替・停止・削除手順と費用確認方法を文書化する。

### 3. 進捗記録

- ProgressLogとMetric更新の固定Schema、登録・履歴取得API、migrationを定義する。
- 実装前にMetric型ごとの更新方法（加算・現在値の置換など）、入力範囲、記録日時と並び順を契約に明記する。
- AndroidのGoal詳細から本文と構造化したMetric値を入力し、保存後に履歴と現在値を再取得できる。
- 他ユーザーのGoalや別GoalのMetricへの記録を拒否する。不正入力では履歴もMetricも変更しない。
- ProgressLog保存とMetric更新を同一transactionで行い、通信再送で二重反映しない。
- 空履歴、保存中、失敗、再試行の表示を用意する。

## Done

- 自動デプロイworkflowを実行して成功証跡を残し、失敗時の復旧手順を検証する。
- サービス間の認証成功・拒否、契約、障害時非保存を検証する。
- クラウドE2Eと3分野の回帰評価の証跡を残し、構成図・運用手順を更新する。
- 進捗記録の契約、所有者分離、整合性、再送時のテストと関連するBackend・Android品質検査が成功する。
- Androidからクラウド上の実AI計画作成と、API・DBを通した進捗登録・再訪をデモできる。
- 起動手順とAPI文書を更新する。次はWeekend 7の進捗解析候補と助言Schemaを定義する。

## Out of Scope

- 実AIによる進捗解析・助言（Weekend 7）、グラフ（Weekend 9）
- 履歴の編集・削除、自動再計画
- 本番自動公開、Terraform（Weekend 12）、非同期化（Weekend 12）、本番構成（Weekend 14）
