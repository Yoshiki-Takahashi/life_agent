# Backend

Python + FastAPIの独立サービスを配置します。各サービスは個別の`pyproject.toml`、テスト、コンテナ定義を持ち、Python環境と依存関係はuvで管理します。

- `services/core-api/`: Android向けAPIと正本データの管理
- `services/goal-planner/`: Goalから初期計画を生成するAIサービス

サービス間ではDBを共有せず、明示的なAPI契約で連携します。開発初期はCore API内のFake Plannerを使い、Goal Plannerを後から接続します。
