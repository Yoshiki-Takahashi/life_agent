# API契約

公開APIの正本はCore APIのPydanticモデルと`/openapi.json`です。
内部AIの入出力は両サービスのPydanticモデルが定義します。
人向けの規則は[APIの約束](../docs/api.md)、責務境界は[データとシステム](../docs/system-architecture.md)を参照してください。

JSON Schemaの保存済みコピーは廃止しました。必要な形式はPydanticの`model_json_schema()`またはOpenAPIから生成できます。
型のコピーをテストで固定する代わりに、各APIと内部HTTP境界を通る入力・出力の互換性を検証します。
