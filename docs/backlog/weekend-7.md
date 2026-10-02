# Weekend 7: AI進捗解析と助言

## Status

Planned

## Sprint Goal / User Story

ユーザーとして、自然言語で進捗を報告し、Metric更新候補を確認して保存した後、次の行動の助言を受けたい。

## Acceptance Criteria

- Progress ParserとAdvisorをCore API内の別責務として実装し、それぞれ固定Schemaと評価を持つ。
- Fakeで解析→候補確認→保存→助言表示のE2Eを完成させてから、実AI Adapterを追加する。
- 解析は既存GoalのMetricだけを対象とし、不明確な値を確定しない。候補の修正・取消とWeekend 6の手入力を利用できる。
- 未確認候補、不正出力、他GoalのMetric参照では正本を更新しない。確定時はWeekend 6の検証と重複防止を利用する。
- Advisorは保存済みデータから助言する。助言生成に失敗しても保存した進捗は失われず、助言だけ再試行できる。
- 助言は計画を変更しない。読書・アプリ開発・筋トレで解析と助言の回帰評価を用意する。
- 通常CIはFake、実AI評価は明示実行とし、Androidからクラウド上の実AI経路も検証する。

## Done

- 関連するBackend・Android品質検査、契約テスト、不正出力・所有者分離テストが成功する。
- 目標設定→計画確認・保存→進捗記録→助言までデモできる。
- API・AI境界・運用文書を更新する。次はWeekend 8の計画変更差分Schemaを定義する。

## Out of Scope

- Parser・Advisorの独立サービス化、自動計画変更、通知、音声入力
