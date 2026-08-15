# システム設計概要

## 構成

- Android: Kotlin + Jetpack Compose
- Backend: Python + FastAPI
- Database: Backendのみが読み書きする永続ストア
- AI Provider: Backendから呼び出し、検証済みの構造化結果だけを採用する

AndroidはHTTP APIを通じてBackendと通信し、AIやDBへ直接接続しません。

## 固定ドメインSchema

- `Goal`: 目標、状態、期間
- `Metric`: 測定項目、型、単位、目標値
- `Milestone`: Goal達成までの中間地点と期限
- `ProgressLog`: 進捗報告とMetricの更新

AIは任意のテーブルやフィールドを生成しません。AIが提案できるのは、固定Schema内のMetric名・型・単位・目標値、Milestone、許可済みの可視化タイプ、進捗との対応、助言・再計画案です。

## AI責務

- Goal Planner: Goalから初期計画を作る
- Progress Parser: 自然言語の進捗を構造化する
- Advisor: 状況を評価し、次の行動を提案する
- Re-planner: 計画変更案を作る

各責務は独立して検証・評価できる境界を持ちます。AI出力はStructured Outputで検証し、重要な変更はユーザーの確認前に確定しません。

## 想定ディレクトリ

```text
android/       Androidアプリ
backend/
  services/
    core-api/       Android向けAPIと正本データ
    goal-planner/   独立した初期計画生成サービス
contracts/     公開APIとサービス間Schema
infra/         GCP構成とデプロイ手順
tests/e2e/     複数コンポーネントのE2Eテスト
docs/
  decisions/   ADR
  backlog/     Sprintとチケット
```

具体的なライブラリ、DB、API契約、バージョンはADRで決定します。

サービスごとの責務と連携フローは`system-architecture.md`を参照してください。
