# Weekend 1: 最小Goal管理

## Status

Implemented

## User Story

ユーザーとして、目標のタイトルと説明を入力して保存し、保存されたGoalの詳細を確認したい。目標管理の最小サイクルを開始するため。

## Acceptance Criteria

- Androidでタイトルと任意の説明を入力できる。
- 空のタイトル、120文字を超えるタイトル、2,000文字を超える説明を拒否する。
- GoalがFastAPI経由でPostgreSQLへ保存される。
- 保存成功後に詳細画面へ遷移し、タイトル、説明、状態、作成日時を表示する。
- 通信失敗時に入力を維持し、再試行できる。
- Goal作成・取得APIとhealth checkがテストされている。
- Alembicを空のPostgreSQLへ適用できる。
- Backendのlint・testとAndroidのtest・buildが成功する。

## Out of Scope

- Metric、Milestone、AI計画生成
- Firebase Authentication
- GCP、Cloud SQL、Cloud Run
- Goal一覧、編集、削除
- 進捗記録

## Done

- `POST /api/v1/goals`、`GET /api/v1/goals/{id}`、`GET /health`を実装。
- PostgreSQL 17のCompose設定と初期Migrationを追加。
- AndroidのGoal入力・詳細画面を実装。
- 次の開発対象はWeekend 2のFake Goal Planner。
