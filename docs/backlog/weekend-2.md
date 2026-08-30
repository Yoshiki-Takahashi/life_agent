# Weekend 2: Fake計画生成による最小製品

## Status

Implemented

## User Story

ユーザーとして、自然言語で入力したGoalからMetricとMilestoneの計画案を作り、内容を確認・編集してから保存したい。曖昧な目標を実行可能な計画へ変えるため。

## Acceptance Criteria

- AndroidでGoalのタイトル、任意の説明、期限を入力できる。
- Fake Plannerが入力に応じて1個のMetricと3個のMilestoneを決定論的に返す。
- プレビュー時にはGoalや計画をDBへ保存しない。
- 確認画面でMetricの名前・目標値・単位と、Milestoneの名前・期限を編集できる。
- 確定時にGoal、Metric、Milestoneを一つのTransactionで保存する。
- Goal詳細で期限、Metric、Milestoneを表示順に確認できる。
- 通信失敗時に入力または編集内容を維持して再試行できる。
- 読書、アプリ開発、筋トレ、汎用GoalのFake出力を回帰テストする。
- Backendのlint・test、Androidのtest・build、PostgreSQL Migrationが成功する。

## Validation

- Metricは1〜3個、Milestoneは3〜5個とする。
- Metricの目標値は0より大きい数値とする。
- Goal期限は今日以降、Milestone期限は今日からGoal期限までの非減少順とする。
- Androidの入力にかかわらず、確定APIで全項目を再検証する。

## Out of Scope

- 計画項目の追加・削除
- OpenAI APIと独立Goal Planner Service
- Firebase Authentication、GCPへのデプロイ
- 進捗記録、助言、再計画

## Done

- `POST /api/v1/goals/preview`、`POST /api/v1/goals/confirm`、計画を含む`GET /api/v1/goals/{id}`を実装。
- MetricとMilestoneのMigration、Fake Planner、Androidの計画確認・詳細表示を追加。
- 次の開発対象はWeekend 3の独立Goal Planner。
