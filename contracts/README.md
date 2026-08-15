# Service Contracts

サービス間・Android間で共有する契約の正本を配置します。

予定:

- `openapi/`: AndroidとCore APIの公開API
- `schemas/`: Core APIと内部AIサービス間のJSON Schema
- `examples/`: 代表的なrequest/response

生成コードは契約から作成し、手編集するモデルとの二重管理を避けます。
