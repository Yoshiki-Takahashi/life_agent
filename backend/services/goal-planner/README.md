# Goal Planner Service

自然言語のGoalからMetricと3〜5個のMilestoneを生成する、状態を持たない内部サービスです。

責務:

- OpenAI APIの呼び出し
- Structured Outputの検証
- プロンプトとSchemaのバージョン管理
- 読書、アプリ開発、筋トレの回帰評価

Goalの保存やユーザー認可はCore APIが担当します。Weekend 2ではCore API内のFake実装を使用します。Weekend 3で同じ契約を実装する独立サービスへ差し替えます。
