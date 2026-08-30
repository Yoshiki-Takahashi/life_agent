# Backend

Python + FastAPIの独立サービスを配置します。各サービスのPython環境と依存関係はuvで管理します。

- `services/core-api/`: Android向けAPIと正本データの管理
- `services/goal-planner/`: Goalから初期計画を生成するAIサービス

## ローカルPostgreSQL

```bash
cd backend
docker compose up -d
docker compose ps
```

停止時はデータを残してコンテナだけ停止します。

```bash
docker compose down
```

接続値を変更する場合は`.env.example`を`.env`へコピーして編集します。`.env`はGit管理外です。

サービス間ではDBを共有せず、明示的なAPI契約で連携します。Core APIだけがアプリケーションDBを所有します。
