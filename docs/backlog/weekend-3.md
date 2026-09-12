# Weekend 3: 独立Goal Planner

## Status

Implemented

## User Story

ユーザーとして、既存のGoal入力・確認・保存フローを変えずに、独立したAIサービスから実行可能な計画案を取得したい。AI障害時にも入力や正本データを失わず再試行したい。

## Acceptance Criteria

- Goal Plannerが独立したuvプロジェクトとFastAPIプロセスとして起動する。
- Core APIと同じGoal Plan契約で、OpenAI Structured Outputsから計画を生成する。
- Core APIは設定によりFakeまたは内部HTTP Plannerを選択できる。
- Planner障害や不正出力時は`503`を返し、Goalを保存しない。
- 通常テストはOpenAIを呼ばず、読書、アプリ開発、筋トレの実AI評価は明示実行する。
- APIキー、Goal本文、OpenAI応答全文をログへ記録しない。

## Validation

- Metricは1〜3個、Milestoneは3〜5個とする。
- Metric目標値は0より大きく、Milestone期限は今日からGoal期限までの非減少順とする。
- Goal PlannerとCore APIの両方でサービス間レスポンスを検証する。

## Out of Scope

- Firebase AuthenticationとGCPデプロイ
- Android画面変更
- 自動リトライ、進捗記録、助言、再計画

## Done

- APIキーなしで単体、契約、サービス間E2Eを実行できる。
- `OPENAI_API_KEY`を使った読書、アプリ開発、筋トレの実AI回帰評価が成功する。
- AndroidからCore API、独立Goal Planner、OpenAIを通して計画確認画面を表示できる。
- 次の開発対象はWeekend 4の認証。
