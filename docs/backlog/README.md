# Backlog

Sprintごとにファイルを作り、以下を記載します。

- Sprint Goal
- ユーザーストーリー
- 受け入れ条件
- 完了条件
- 対象外

チケットは、可能な限りAndroid、API、DBをつないだ小さな縦切りにします。基盤作業だけのチケットは、後続のユーザー価値と完了判定を明示します。

すべてのSprintで[サービス設計・拡張指針](../service-design-guidelines.md)を参照し、機能の配置先と契約を決めます。開発中に配置方針を見直した場合は、共通指針と関連文書も同じ変更で更新します。

## 現在の計画

Weekend 6の自動デプロイ・クラウド計画生成経路・手入力進捗記録は実装済みです。Weekend 7の進捗解析・助言はCloud受け入れまで完了し、ADR 0002に合わせてOpenAI接続責務を内部AI接続サービスへ移しました。[検証結果と残件](weekend-7-status.md)を参照してください。Weekend 8は再計画候補の生成、差分確認、承認適用を実装中です。

| 順序 | 計画 | 状態 |
| --- | --- | --- |
| 5 | [GCP開発環境](weekend-5.md)・[完了証跡](weekend-5-status.md) | Complete |
| 6 | [開発環境自動デプロイ、クラウド実AI計画生成、進捗記録](weekend-6.md)・[進捗](weekend-6-status.md) | In Progress / 旧キー失効確認など運用残件あり |
| 7 | [AI進捗解析と助言](weekend-7.md)・[検証結果](weekend-7-status.md) | Complete |
| 8 | [再計画](weekend-8-plus.md#weekend-8-計画の見直し) | In Progress |
| 9〜14 | [可視化、通知、音声、基盤、認証、本番化](weekend-8-plus.md) | Planned / 条件付き項目あり |

Weekend 6では、6.A相当の自動デプロイ、6.B相当のGoal Plannerクラウド結合、進捗記録の順に実施します。各段階をデモ可能な状態で完了させてから次へ進みます。8以降は日程確約ではなく実施枠です。未採用の機能を実装済みとは扱いません。
