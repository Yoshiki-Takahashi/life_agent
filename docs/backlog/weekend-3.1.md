# Weekend 3.1: Goal Planner開発基盤の仕上げ

## Status

Implemented

## User Story

開発者として、OpenAI APIのTokenを消費せずにCore API、独立Goal Planner、PostgreSQLを通すE2Eを再現し、Goal Plannerコンテナを本番依存だけで安定して起動したい。

## Acceptance Criteria

- 独立Goal Plannerに明示設定時だけ利用できる決定論的Fake Providerがある。
- 一つのコマンドで隔離されたPostgreSQL、Fake Goal Planner、Core APIを起動してE2Eを実行できる。
- E2Eが独立Goal Planner固有の出力を確認し、プレビュー、確定、取得を通す。
- E2E用コンテナとDBは終了時に削除され、通常の開発コンテナへ干渉しない。
- Goal Plannerコンテナは起動時に依存関係を再同期しない。
- Docker build contextに仮想環境、キャッシュ、テスト、秘密情報を含めない。
- 通常テストとWeekend 3.1 E2EはOpenAI APIを呼ばない。

## Out of Scope

- 実AI出力の品質評価追加
- Core APIのコンテナ化
- Firebase Authentication

## Done

- `scripts/test-weekend-3.sh`でキー不要のサービス間E2Eが成功する。
- 次の開発対象はWeekend 4の認証。
