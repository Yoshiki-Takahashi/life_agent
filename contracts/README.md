# Service Contracts

サービス間・Android間で共有する契約の正本を配置します。

予定:

- `openapi/`: AndroidとCore APIの公開API
- `schemas/`: Core APIと内部AIサービス間のJSON Schema
- `examples/`: 代表的なrequest/response

生成コードは契約から作成し、手編集するモデルとの二重管理を避けます。

現在の公開契約はCore APIが生成するOpenAPIを正本とし、ローカル起動中の`http://127.0.0.1:8000/openapi.json`で確認します。

Weekend 2・3のGoal計画:

- 内部Planner入力契約: `schemas/goal-plan-request.schema.json`
- 内部Planner出力契約: `schemas/goal-plan.schema.json`
- プレビュー入力: `title`、任意の`description`、`target_date`
- Metric案: `name`、数値の`target_value`、`unit`
- Milestone案: `title`、`target_date`
- `POST /api/v1/goals/preview`: 未保存の計画案を返す
- `POST /api/v1/goals/confirm`: 確認・編集済みのGoalと計画を一括保存する
- `GET /api/v1/goals/{goal_id}`

確定後のMetricとMilestoneには`id`、`position`、`created_at`が加わります。Metricは1〜3個、Milestoneは3〜5個です。正確なrequest/responseとValidationはCore APIが生成するOpenAPIを参照してください。

Goal Plannerの出力はPlannerとCore APIの両方で検証し、不正な計画は保存しません。

## Weekend 7: 進捗解析と助言

- `POST /api/v1/goals/{goal_id}/progress/preview`: `{"body":"今日は2冊読み終えた"}`を受け取る。応答は`metric_updates`（0〜3件の`metric_id`・`value`）と`warnings`（最大3件、各1〜300文字）。解析だけでは保存しない。
- 候補の値は**今回の加算量**。正数、小数2桁まで、最大10億、現在値との合計が目標以内。既存GoalのMetricだけを許可し、重複IDを拒否する。曖昧な報告は空候補と説明を返す。
- 候補確認・修正後の保存は既存`POST /api/v1/goals/{goal_id}/progress`。保存直前にも検証する。候補0件では保存できず、手入力へ戻れる。
- `client_request_id`は同じ保存操作の再試行中は保持する。Goal行をロックして存在確認と加算を直列化する。Metric更新・履歴保存は同一transaction。別内容への編集は新しいIDを使う。
- `POST /api/v1/goals/{goal_id}/advice`: bodyなし。保存済みGoal・Metric・Milestone・最新10件までの履歴を使用する。応答は`summary`（1〜500文字）、`next_actions`（1〜3件、各1〜300文字）。助言自体は保存せず、再取得できる。
- 両APIはFirebase認証とGoal所有者検証を要求する。他所有者・存在しないGoalは404、入力不正は422、AI障害・不正出力は503。503に入力やproviderのエラー全文を含めない。
- 進捗保存と助言は別リクエスト。助言障害で保存は取り消さず、助言の再試行で進捗を再送しない。助言は計画を変更しない。

固定Schemaは`schemas/progress-preview-request.schema.json`、`schemas/progress-preview.schema.json`、`schemas/advice.schema.json`。Core APIのPydanticモデルとの一致をテストする。
