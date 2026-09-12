# End-to-End Tests

AndroidまたはAPIクライアントから複数サービスを通すシナリオを配置します。各サービス内の単体・結合テストは、それぞれのサービスディレクトリに置きます。

Weekend 2は「Goal入力 → Core API内Fake → 保存 → Goal詳細取得」を対象にします。

Weekend 3.1は、隔離されたPostgreSQLと独立Goal PlannerのFake Providerをコンテナで起動し、Core APIとのHTTP境界をToken消費なしで検証します。

```bash
scripts/test-weekend-3.sh
```

実OpenAIを含む評価はGoal Planner側の`live_ai` markerで分離し、このE2Eからは呼びません。
