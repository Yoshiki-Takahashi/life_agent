# Weekend 9: 進捗可視化の最初の縦切り

## 状態
In Progress。Weekend 8と独立した既存APIによるAndroid表示を追加する。

## User Story
ユーザーとして、保存した進捗の累積推移と現在の目標値をGoal詳細で確認したい。

## 範囲と契約
- 既存のGET Goal詳細のMetric / ProgressLogを利用し、公開API・DB・AIサービスは変更しない。
- 現在サポートされる型は正の増加量を累積する数値型のみ。固定の折れ線と点で描画する。
- Metric IDで履歴を対応付け、記録時刻をUTC instantに変換して昇順に並べ、同時刻はログID順とする。
- Decimalによる履歴合計が現在値と一致し、単位と日時が有効な場合のみ推移を表示する。不一致は推定で補完しない。
- 記録なしは「まだ記録がありません」とし、達成率を表示しない。
- 達成率は履歴整合時のみ現在値 / 現在の正の有限目標値 × 100。小数1桁で表示し、100%に丸め込まない。
- 目標値なしでは率・目標線を表示せず履歴のみ表示する。現行APIは正の目標値必須だが表示側も防御する。
- 破線は現在の目標値であり、過去の目標値の推移ではない。未記録期間の活動を推測しない。
- グラフの読み上げ説明には各記録日時・累積値・単位を含める。

## 責務配置
この縦切りでは既存の加算契約をAndroidで表示用に投影する。正本値・保存時検証は引き続きCore API。
再計画のSchema・Migration・Providerには触れない。期間集計APIや型拡張時はCore API側に計算規則を集約する。

## 受け入れ条件
- Goal詳細でMetricごとの累積推移・現在の目標値・計算可能な達成率を確認できる。
- 他Metric、未記録、小数、時差を含む記録順序、不正日時、履歴不整合を検証する。
- 進捗保存後に返却されたGoalを使い推移も更新する。

## 完了条件
Android単体テスト・ビルド・lint、画面デモ、文書更新を完了する。

## 対象外
Weekend 8との統合、計画履歴の推定、任意グラフ生成、追加Metric型、期間集計API。

## 次回の最初の作業
実機またはEmulatorで記録なし→進捗保存→グラフ更新の画面デモを確認する。

## 検証結果
- `ANDROID_HOME=/Users/yoshiki/Library/Android/sdk ./gradlew testDebugUnitTest assembleDebug lintDebug`: 成功。
- API 24/25でも日時解析を使えるようcore library desugaringを有効化。
- 接続中の実機・Emulatorがないため画面デモは未実施。SprintはIn Progressを維持する。

## クラウド接続反映（2026-10-25）
- Cloud Run Core APIの`/health`が正常応答することを確認。
- Git管理外の実Firebase設定を用い、`installDebug -Plifeagent.useCloudFirebase=true`が成功。
- Pixel_8 Emulatorで既存ログインを維持したGoal一覧とGoal詳細を表示し、クラウドGoalの「進捗の推移」「現在の目標」「まだ記録がありません」を確認。
- 記録ありのグラフ描画・進捗保存後の更新は画面デモ未確認。既存クラウドデータへのテスト記録は追加していない。

## 利用者確認
利用者がクラウド接続Emulatorでの操作確認完了を報告。Weekend 10と合わせてPR化する。
