# UI刷新2: 共通ナビゲーションと全目標対話

> 履歴資料です。現在の実装仕様は[仕様の入口](../../README.md)を参照してください。

状態: Complete。開始: 2026-10-31。完了: 2026-11-01。
仕様: [UI改訂](../ui/04-navigation-and-global-coach.md)。

User Story: 利用者として、共通メニューで画面を移動し、すべての目標について一つのコーチと話したい。
受け入れ条件: 下部メニュー常設、全目標対話、対話中心の画面、Goal詳細タブ、
未保存移動確認、他ユーザー分離、候補確認後のみ保存。
Done: Android単体・build・lint、Core/AIサービスのlint・テスト、Fakeサービス間契約、
Emulatorで共通遷移と複数目標の対話を検証。クラウドは起動せず通常テストはFake。
対象外: 会話の永続化・同期、複数Goalの無確認更新、自動計画変更。
次回の最初の作業: 複数目標コーチの実利用フィードバックを収集する。

## 検証結果
- Core API: Ruff成功、pytest 95件成功。全所有Goalの抽出、他人のGoal除外、
  AI待機前のtransaction終了、不明Goal・生成実績文の拒否、読書・開発・筋トレの回帰を確認。
- 内部AI接続サービス: Ruff成功、pytest 33件成功・実AI9件skip。
  全目標のStructured Output入力、出力検証、固定Schemaの両サービス一致を確認。
- Android: testDebugUnitTest 43件成功、assembleDebug・assembleDebugAndroidTest・lintDebug成功。
- Emulator: CoachFlowTest 5件・GoalDetailScreenTest 1件・ProgressFlowTest 1件の計7件成功。
  共通メニュー・未保存確認・詳細タブ・助言再試行を検証。専用PostgreSQL + Fake APIを通し、
  読書と筋トレを同じ会話で相談・個別保存、commit後の応答消失→同一ID再送で重複なしを確認。
- 通常Debugアプリにも反映済み。既存のローカル認証でログインし、読書と筋トレの
  両Goalが全目標コーチの応答へ含まれることを確認。テスト用の専用DB/APIは終了時に削除・停止。
- 手動deploy-devにcoach_backend（既定fake）を追加し、YAML・shell構文検証成功。
  workflowは実行していない。クラウド・実AIは起動していない。

## 現時点の制約
- ローカルの応答はFake。実AIの会話品質は未評価。接続コードは既存内部サービスに実装し、
  OpenAIの応答はモックで検証した。実AI利用時はCOACH_BACKEND=http / COACH_PROVIDER=openaiが必要。
- 会話はセッション内だけ。目標作成と再計画は既存の専用画面で確認して確定する。
- 03設計書の全機能を完了した扱いにはしない。本チケットは04の採用変更を対象とする。
