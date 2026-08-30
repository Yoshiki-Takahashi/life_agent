# Service Contracts

サービス間・Android間で共有する契約の正本を配置します。

予定:

- `openapi/`: AndroidとCore APIの公開API
- `schemas/`: Core APIと内部AIサービス間のJSON Schema
- `examples/`: 代表的なrequest/response

生成コードは契約から作成し、手編集するモデルとの二重管理を避けます。

現在の公開契約はCore APIが生成するOpenAPIを正本とし、ローカル起動中の`http://127.0.0.1:8000/openapi.json`で確認します。

Weekend 2のGoal計画:

- 内部Planner契約: `schemas/goal-plan.schema.json`
- プレビュー入力: `title`、任意の`description`、`target_date`
- Metric案: `name`、数値の`target_value`、`unit`
- Milestone案: `title`、`target_date`
- `POST /api/v1/goals/preview`: 未保存の計画案を返す
- `POST /api/v1/goals/confirm`: 確認・編集済みのGoalと計画を一括保存する
- `GET /api/v1/goals/{goal_id}`

確定後のMetricとMilestoneには`id`、`position`、`created_at`が加わります。Metricは1〜3個、Milestoneは3〜5個です。正確なrequest/responseとValidationはCore APIが生成するOpenAPIを参照してください。
