# LifeAgent 開発ガイド

## プロダクト

LifeAgentは、自然言語の目標をAIがMetric、Milestone、スケジュールへ分解し、進捗記録・振り返り・助言・再計画を支援する目標管理アプリです。

作業前に以下を確認してください。

- `docs/development-workflow.md`: 開発からリリースまでの共通手順、検証環境、完了の判断
- `docs/validation.md`: 変更内容別の必須検証、Mac・Codex Cloud・Actionsでの実行方法、結果の記録
- `docs/product-overview.md`: 現在の機能と対象外
- `docs/ui.md`: 現在の画面と操作
- `docs/api.md`: APIと保存の規則
- `docs/system-architecture.md`: サービス責務、連携、セキュリティ境界
- `docs/service-design-guidelines.md`: 全Sprint共通の責務配置、既存サービスの拡張先、設計変更・サービス分離の判断基準
- `docs/development-infrastructure.md`: 開発環境、クラウドリソース、コスト管理
- `docs/development-guide.md`: 開発順序、各段階の完成状態、サービス分離基準
- `docs/coding-standards.md`: 実装時の簡潔さ、構成、品質ルール
- `docs/decisions/`: 採用済みの技術判断
- GitHub Issues: 今後の開発内容、受け入れ条件、進捗の正本
- `docs/archive/backlog/`: 過去のSprint記録（今後のチケットは追加しない）

## 開発原則

- ユーザー価値のある小さな縦切りをSprint単位で完成させる。
- AIに任意のDB Schemaを生成させず、共通Schemaを固定する。
- AI出力はStructured Outputとして検証し、不正な出力は保存しない。
- 重要な計画変更はユーザー確認後に確定する。
- Goal Planner、Progress Parser、Advisor、Re-plannerの責務を分離する。
- 機能の追加・計画時は`docs/service-design-guidelines.md`に沿って配置を決める。AI接続は既存のGoal Planner / AI接続サービスへ寄せる。
- 共通設計指針は開発中に修正可能とし、配置方針を変えた場合は同じ変更で更新する。重要な境界変更はADRにも記録する。
- 既存の変更を尊重し、依頼範囲外の変更を混ぜない。

## ツールとコマンド

- BackendはPython + FastAPIとし、Python本体・仮想環境・依存関係を`uv`で管理する。
- 依存追加は`uv add`または`uv add --dev`を使う。
- Pythonコマンドは`uv run`で実行し、グローバル`pip`を使わない。
- AndroidはKotlin + Jetpack Composeとし、ビルドにはGradle Wrapperを使う。
- `.venv`、`.env`、秘密情報、生成物をコミットしない。

## 品質基準

- 開発はGitHub Issueで管理し、ユーザーストーリー、受け入れ条件、完了条件を記載する。PR本文に`Refs #番号`で対応Issueを参照し、必要なチェック・デプロイの完了後にIssueを閉じる。詳細は利用するスキルと`docs/development-workflow.md`に従う。
- テストは利用者の振る舞い、認証、保存・競合・再送、外部サービス境界に絞る。内部状態の写し、表示文言ごとのテスト、生成Schemaのスナップショットを増やさない。
- 公開動作を変更した場合は必要な回帰テストを更新し、関連するlint・型検査・テストを実行する。
- PRのAndroid検証は、同じコミットの開発版Backend・Firestore Emulator・Fake AIを使う。現行GCPへ接続しない。Actionsの結果を検証実績に含め、未実施・失敗・skipは成功と区別する。
- AI機能には「読書」「アプリ開発」「筋トレ」の回帰評価を維持する。
- APIや設計判断を変更した場合は、同じ変更で`docs/`も更新する。
- 実装前に`docs/coding-standards.md`を読み、不要な抽象化を追加しない。

## 現在の標準コマンド

- Backend: `cd backend/services/core-api && uv run ruff check . && uv run pytest`
- Android単体・lint・ビルド: `cd android && ./gradlew -Plifeagent.useCloudFirebase=false testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`
- Androidと開発版Backendの結合: 起動済みAndroid Emulatorで`./scripts/test-weekend-7.sh --android`（GCP認証・実AIキー不要）
- Firestore Emulator: `gcloud emulators firestore start --host-port=127.0.0.1:8080 --project=demo-lifeagent`（Core APIテストは一時Emulatorを自動起動）

## コード配置

- `android/`: Androidアプリ
- `backend/services/core-api/`: Android向けAPIと正本データ
- `backend/services/goal-planner/`: 初期計画・進捗解析・助言の生成を担い、再計画へ拡張する内部AI接続サービス
- `contracts/`: 公開APIとサービス間Schema
- `infra/`: GCP構成とデプロイ手順
- `tests/e2e/`: 複数コンポーネントを通すテスト
- `scripts/`: 開発・検証・デプロイ補助
- `docs/`: プロダクト・設計・意思決定・バックログ
