# Service Contracts

サービス間・Android間で共有する契約の正本を配置します。

予定:

- `openapi/`: AndroidとCore APIの公開API
- `schemas/`: Core APIと内部AIサービス間のJSON Schema
- `examples/`: 代表的なrequest/response

生成コードは契約から作成し、手編集するモデルとの二重管理を避けます。

現在の公開契約はCore APIが生成するOpenAPIを正本とし、ローカル起動中の`http://127.0.0.1:8000/openapi.json`で確認します。

Weekend 1のGoal:

- 作成入力: `title`、任意の`description`
- 応答: `id`、`title`、`description`、`status`、`created_at`、`updated_at`
- `POST /api/v1/goals`
- `GET /api/v1/goals/{goal_id}`
