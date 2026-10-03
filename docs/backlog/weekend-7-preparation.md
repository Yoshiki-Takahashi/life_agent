# Weekend 7 実装準備

2026-10-02、ローカルHEAD `180e504`（Weekend 6進捗記録のPR #12マージ）を確認。開始時の作業ツリーに変更なし。以下のAPI・Schemaは実装前の案であり、公開済み契約ではない。

この文書は着手時の記録です。現在の実装状態は[Weekend 7ステータス](weekend-7-status.md)を参照してください。

## 着手時の状態

- Goal作成・計画確認・保存・一覧・詳細、Firebase認証、手入力による進捗登録まで実装済み。
- Core APIは進捗とMetric加算を同一transactionで保存し、Goal内の`client_request_id`で順次再送の二重反映を防ぐ。履歴はGoal詳細に含まれる。
- Metricは現在、数値の加算のみ。DBは小数2桁。Parserもこの制約に合わせ、現在値への置換や新しいMetric型は追加しない。
- Parser・Advisor、対応するAPI・Android画面状態・回帰評価は未実装。Core APIにOpenAI SDK依存はまだない。
- `docs/decisions/`にはREADMEのみで、個別の採用済みADRはない。
- Weekend 6のクラウド構築とworkflow成功は記録済み。ただし旧キー失効確認、3分野の実AI回帰評価、Androidのクラウド通し確認は未完了として残る。クラウドの現時点の稼働状態は今回再確認していない。[証跡と残件](weekend-6-status.md)を参照。

## 実装方針と契約案

1. `POST /api/v1/goals/{goal_id}/progress/preview`を追加。本文（1〜2000文字）を受け取り、所有者確認後に保存済みGoal・Metricと本文をParserへ渡す。DBを変更せず、`metric_updates`（0〜3件の`metric_id`・正の加算値）と、曖昧さを説明する`warnings`を返す。候補0件は正常な結果として手入力へ戻せる。
2. Parserは既存Metricだけを参照し、数値・単位・加算か累計かが曖昧なら推測で確定しない。未知ID、重複ID、非有限値、範囲外、目標超過、不正Schemaを検証する。小数精度の扱いと文字列上限もW7-01で固定する。
3. Androidで候補の修正・取消・明示保存を可能にし、保存は既存`POST /api/v1/goals/{goal_id}/progress`を使う。本文を変更した場合は古い候補を無効化する。解析後に現在値が変わる場合も、保存時の検証を正とする。
4. `POST /api/v1/goals/{goal_id}/advice`を追加。リクエストに未保存候補を含めず、所有者確認後に保存済みGoal・現在値・履歴から生成する。応答案は`summary`と`next_actions`（1〜3件の短い文字列）。履歴の送信上限と文字列上限はW7-01で固定する。助言で計画・Metric・履歴を更新しない。
5. 保存成功後にAndroidが助言を別リクエストで取得する。助言の失敗状態と再試行を保存状態から分離する。再試行で進捗POSTを再送しない。
6. ParserとAdvisorはCore API内の別Port・Fake・実AI Adapterとする。候補と助言の永続化は初期スコープに含めず、現時点では追加migration不要の見込み。実AIはFakeの利用経路完成後に追加する。

## 実装前に押さえる既存の不足

- Androidの`ProgressLogCreateRequest`は生成時にUUIDを採番し、Repositoryの保存呼び出しごとに新しいIDになる。応答喪失後の再試行でも同じIDを保持するよう、W7-02でViewModelから渡す。編集後の別内容は別IDとし、保存中の連打も防ぐ。
- Backendの既存再送テストは順次送信。並行送信時は事前の存在確認だけでは競合し得るため、PostgreSQLで同一IDの競合と別IDのMetric更新を検証し、必要なロック・競合処理を保存経路に追加する。
- Core API内で実AIを呼ぶため、現在Goal Plannerだけに設定されているOpenAI Secret参照・SDK・設定をCore APIにも追加する必要がある。W7-04で権限と運用文書を更新する。

## 作業チケット

| ID | ユーザーストーリー / 作業 | 受け入れ条件 | 完了条件 |
| --- | --- | --- | --- |
| W7-01 | 利用者として本文から未保存の候補を確認したい。契約とParser Fakeを追加 | 候補0件、明確な加算、不明確な累計、未知・重複Metric、所有者分離を扱い、解析だけではDB不変 | Schema・契約例・BackendテストとAPI文書が一致し、Ruff・pytest成功 |
| W7-02 | 利用者として候補を修正・取消・保存し、手入力も使いたい | 本文変更で候補無効化、保存時再検証、応答喪失時も同一ID再送、連打防止、競合時も二重加算・更新消失なし | AndroidのRepository・ViewModel・Composeテスト、PostgreSQL競合検証、既存手入力経路の確認成功 |
| W7-03 | 利用者として保存後に助言を受け、失敗時に助言だけ再試行したい | Advisor Fakeが保存済みデータだけを使用し、助言失敗で進捗を失わず計画も不変 | Fakeで解析→確認→保存→助言のE2E、所有者分離・障害テスト成功 |
| W7-04 | 利用者としてクラウドで実AI解析・助言を使いたい | 固定Structured Output検証、タイムアウト・不正出力・拒否への対応、個別Fake切替、秘密情報をログへ出さない | 3分野の明示実AI評価、Androidクラウドデモ、Secret権限・deploy・停止手順と証跡を更新 |

順序はW7-01 → W7-02 → W7-03 → W7-04。実AI評価は通常CIから分離する。独立サービス化、自動再計画、通知、音声は対象外。

## 検証計画

- 読書「2冊読み終えた」、アプリ開発「画面を1つ完成させた」、筋トレ「2回トレーニングした」を、それぞれ対応するMetricと組み合わせて解析・助言評価に使う。
- 「少し進んだ」「合計5冊になった」、無関係な数値、異なる単位、他GoalのMetric、不正AI出力を追加し、推測加算や正本変更がないことを確認する。
- 取消、手入力への復帰、解析中の本文変更、保存失敗・再送、助言失敗・再試行をAndroidで確認する。
- Backendは両サービスのRuff・pytest、Androidは`testDebugUnitTest assembleDebug`と追加Composeテスト、統合はPostgreSQLを通すFake E2Eを実行する。現在のBackendには専用の型検査コマンドはない。
- クラウド確認時はWeekend 6残件も追跡し、完了証跡を残して環境を停止する。

## 準備時の検証結果

2026-10-02に実行。

- Core API: `uv run --frozen ruff check .`成功、`uv run --frozen pytest`は40件成功。
- Goal Planner: Ruff成功、`uv run --frozen pytest -m 'not live_ai'`は13件成功、実AI3件は除外。
- Android: 初回はSDK設定不足で失敗。既存のSDKを`ANDROID_HOME`で指定し、`./gradlew testDebugUnitTest assembleDebug`成功。マシン固有の設定ファイルは追加していない。
- 今回はエミュレータテスト、PostgreSQL E2E、クラウド検証、実AI評価を未実行。

## 次の最初の作業

W7-01として、進捗解析候補と助言のPydantic Schema・入出力例・検証条件を定義し、未確認候補ではDBが変わらない失敗テストから着手する。
