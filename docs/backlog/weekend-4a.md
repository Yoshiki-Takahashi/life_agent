# Weekend 4.A: Android Goal体験の拡充

## Status

Implemented

## User Story

ログインしたユーザーとして、自分のGoalを一覧から選び、計画の作成・確認・保存・詳細表示を迷わず一周したい。作成後も過去のGoalへ再訪し、複数のGoalを継続して管理するため。

## Acceptance Criteria

- 認証後の開始画面に、ログインユーザーが所有するGoalだけを表示する。
- Goalがない場合は空状態と新規作成導線を表示する。
- 一覧の読込中、通信失敗、再読み込みを画面上で扱う。
- Goalカードにタイトル、期限、状態、Metric数、Milestone数を表示する。
- 一覧からGoal詳細を開き、詳細から一覧へ戻れる。
- 新規作成から計画確認・保存・詳細表示まで既存フローを維持する。
- 保存後に一覧へ戻ると新しいGoalが反映される。
- BackendとAndroidのテスト・ビルド、および既存サービス間E2Eが成功する。

## API Contract

`GET /api/v1/goals`はFirebase ID Tokenを要求し、Tokenの`uid`が所有するGoalだけを返す。レスポンスは`updated_at`降順、同値の場合は`id`降順とし、各要素は以下を含む。

- `id`、`title`、`target_date`、`status`
- `metric_count`、`milestone_count`
- `created_at`、`updated_at`

MVP規模ではページネーションを設けない。

## Out of Scope

- Goalの編集、削除、検索、フィルター
- 進捗率、進捗入力、履歴、助言、再計画
- プロフィール、設定、将来機能のモック画面
- Firebase設定、Token検証、所有者Schemaの変更

## Done

- 認証済みGoal一覧APIと所有者分離テストを追加した。
- Androidをホーム、作成、計画確認、詳細の画面構成へ整理した。
- 次の開発対象はWeekend 5のGCP開発環境。
