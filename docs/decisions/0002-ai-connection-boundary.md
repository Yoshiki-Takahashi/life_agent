# ADR 0002: AI接続責務の境界を明確にする

- 日付: 2026-10-03
- 状態: accepted

## 背景

Weekend 7を最新の`main`へ取り込んだ結果、OpenAI SDKと`OPENAI_API_KEY`を扱う実行単位が複数になっている。

- Goal Planner Service: 初期計画生成のOpenAI接続を持つ。
- Core API: Progress ParserとAdvisorのOpenAI接続を持つ。

Core APIは公開API、認証、認可、DB transaction、正本データ更新を担当する。ここへAI Provider接続、Prompt、Structured Output、実AI評価、APIキー運用まで追加すると、Core APIの責務が広がり、AI接続管理がGoal Planner ServiceとCore APIに分散する。

再計画はADR 0001でGoal Planner Serviceの一機能として扱うことにした。この判断は、初期計画と再計画のAI接続責務を同じ境界へ寄せるためである。同じ観点で、Progress ParserとAdvisorの実AI接続も、長期的な配置を見直す必要がある。

## 設計指針

AI Providerへ直接接続する責務は、できるだけ公開API・DB更新責務から分離する。Core APIは正本データ、認可、入力検証、ユーザー確認、transaction、最終的な業務ルール検証を担当する。AI接続を持つ内部サービスは、固定Schemaに従った候補や助言を生成し、状態を持たず、アプリケーションDBへ接続しない。

原則として、新しいAI機能を追加するときは次の順で判断する。

1. 既存のAI接続サービスに機能として追加できるかを検討する。
2. 追加できない場合は、Core API内にOpenAI SDKやAPIキーを増やす理由を明記する。
3. 新しい実行単位を作る場合は、スケーリング、障害隔離、権限、評価、運用負荷の理由をADRへ記録する。

## 推奨する責務分担

Core API:

- Android向け公開API
- Firebase ID Token検証と所有者認可
- Goal、Metric、Milestone、ProgressLogの正本管理
- AI出力の業務ルール検証
- ユーザー確認、更新競合検証、DB保存
- AI内部サービスへの認証付き呼び出し

AI接続サービス:

- OpenAI SDKとAPIキーの保持
- PromptとStructured Output Schemaの管理
- AI Providerのtimeout、retry、refusal、不正出力の扱い
- Fake Providerと実AI回帰評価
- 初期計画、再計画、進捗解析、助言などの候補生成

AI接続サービスは正本DBを持たない。AI出力は候補であり、Core APIの検証とユーザー確認を通るまで正本へ反映しない。

## 現状への適用

Weekend 7のProgress Parser / Advisorは、既存の公開APIとAndroid経路を保ったまま、OpenAI SDK・Prompt・Structured Output生成をGoal Planner Service配下の内部AI接続APIへ移す。Goal Planner Serviceは当面、初期計画だけでなく進捗解析候補と助言の生成も担うステートレスAI接続サービスとして扱う。

Core APIに残すもの:

- Android向け`/progress/preview`、`/progress`、`/advice`公開API
- Firebase認証、所有者確認、正本Goalの読み出し
- AI出力候補のPydantic Schema検証とドメイン検証
- ユーザー確認後のProgressLog保存とMetric加算
- Goal Planner / AI接続サービスへの認証付きHTTP呼び出し

Goal Planner Serviceへ移すもの:

- Progress Parser / AdvisorのOpenAI Adapter
- Parser / Advisor promptとStructured Output schema
- Provider timeout、SDK自動再試行0回、`store=False`、不正出力の再試行可能エラー変換
- Parser / AdvisorのFake Provider、通常テスト、実AI回帰評価、ユーザー価値評価ハーネス

これにより、Core APIはOpenAI Secretを参照しない。Cloud Run deployでは、`progress_parser_backend=openai`または`advisor_backend=openai`を選んだ場合も、Core APIには`http` backendを設定し、OpenAI SecretはGoal Planner Service側にだけ付与する。

## 移行時の注意

- 既存のProgress Parser / Advisor API契約とAndroidの利用経路を壊さない。
- まずFake Providerを通したサービス間契約テストを追加する。
- 実AI評価は読書、アプリ開発、筋トレの既存回帰を維持する。
- Core APIからOpenAI Secret参照を外すのは、移行先サービスでクラウドE2Eと回帰評価が通ってからにする。
- 移行中もAI出力は候補として扱い、ProgressLog保存や計画変更はCore APIのtransaction内でのみ確定する。

## 採用しない方針

- AI機能ごとに無条件で独立サービスを増やす。
- Core APIへ新しいOpenAI接続を都度追加する。
- AIサービスからアプリケーションDBを直接更新する。
- AI出力をユーザー確認や業務ルール検証なしで正本へ保存する。

## 次の作業

Weekend 8では、この方針を前提として再計画APIをGoal Planner Serviceへ追加する。Goal Planner Serviceをより広いAI接続サービスとして正式に名称変更するかは、API互換性、運用負荷、ログ・評価の見通しを踏まえて別途判断する。

