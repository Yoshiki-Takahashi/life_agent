# LifeAgent 開発ガイド

## プロダクト

LifeAgentは、自然言語の目標をAIがMetric、Milestone、スケジュールへ分解し、進捗記録・振り返り・助言・再計画を支援する目標管理アプリです。

作業前に以下を確認してください。

- `docs/product-overview.md`: 機能、MVP、対象外
- `docs/architecture-overview.md`: システム境界とデータ設計
- `docs/system-architecture.md`: サービス責務、連携、セキュリティ境界
- `docs/development-infrastructure.md`: 開発環境、クラウドリソース、コスト管理
- `docs/development-guide.md`: 開発順序、各段階の完成状態、サービス分離基準
- `docs/decisions/`: 採用済みの技術判断
- `docs/backlog/`: Sprintごとの作業と受け入れ条件

## 開発原則

- ユーザー価値のある小さな縦切りをSprint単位で完成させる。
- AIに任意のDB Schemaを生成させず、共通Schemaを固定する。
- AI出力はStructured Outputとして検証し、不正な出力は保存しない。
- 重要な計画変更はユーザー確認後に確定する。
- Goal Planner、Progress Parser、Advisor、Re-plannerの責務を分離する。
- 既存の変更を尊重し、依頼範囲外の変更を混ぜない。

## ツールとコマンド

- BackendはPython + FastAPIとし、Python本体・仮想環境・依存関係を`uv`で管理する。
- 依存追加は`uv add`または`uv add --dev`を使う。
- Pythonコマンドは`uv run`で実行し、グローバル`pip`を使わない。
- AndroidはKotlin + Jetpack Composeとし、ビルドにはGradle Wrapperを使う。
- `.venv`、`.env`、秘密情報、生成物をコミットしない。

## 品質基準

- チケットにはユーザーストーリー、受け入れ条件、完了条件を記載する。
- 変更に対応するテストを追加し、関連するlint・型検査・テストを実行する。
- AI機能には「読書」「アプリ開発」「筋トレ」の回帰評価を維持する。
- APIや設計判断を変更した場合は、同じ変更で`docs/`も更新する。

## コード配置

- `android/`: Androidアプリ
- `backend/services/core-api/`: Android向けAPIと正本データ
- `backend/services/goal-planner/`: 独立した初期計画生成サービス
- `contracts/`: 公開APIとサービス間Schema
- `infra/`: GCP構成とデプロイ手順
- `tests/e2e/`: 複数コンポーネントを通すテスト
- `scripts/`: 開発・検証・デプロイ補助
- `docs/`: プロダクト・設計・意思決定・バックログ
