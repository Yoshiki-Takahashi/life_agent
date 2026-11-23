# API契約

公開APIの正本はCore APIのPydanticモデルと`/openapi.json`です。
内部AIの入出力は両サービスのPydanticモデルが定義します。
人向けの規則は[APIの約束](../docs/api.md)、責務境界は[データとシステム](../docs/system-architecture.md)を参照してください。

JSON Schemaの保存済みコピーは廃止しました。必要な形式はPydanticの`model_json_schema()`またはOpenAPIから生成できます。
型のコピーをテストで固定する代わりに、各APIと内部HTTP境界を通る入力・出力の互換性を検証します。

Goal作成は`POST /api/v1/goals/questions` → `POST /api/v1/goals/preview` →
`POST /api/v1/goals/confirm`の順です。内部質問生成は`POST /internal/v1/goal-questions`。
質問・回答の型、件数制約、既存クライアントとの互換性は[APIの約束](../docs/api.md#計画)を参照します。
目標一覧の達成率と詳細のチェック項目は[マイルストーンと達成率](../docs/api.md#マイルストーンと達成率)を参照します。

再計画は、所有する目標の`/replan/questions` → `/replan/preview` → `/replan/apply`の順です。
質問生成は内部の`POST /internal/v1/replan-questions`を使います。回答を省略した従来の候補生成も受け付けます。
[再計画のAPI](../docs/api.md#再計画)を参照します。
