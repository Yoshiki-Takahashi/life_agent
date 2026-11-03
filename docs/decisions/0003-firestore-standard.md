# ADR 0003: 正本DBをFirestore Standardへ変更する

- 日付: 2026-11-03
- 状態: accepted（2026-11-03切替済み、ユーザー指示で旧SQLも即時撤去済み）
- 管理: [Issue #23](https://github.com/Yoshiki-Takahashi/life_agent/issues/23)

## 背景

小規模な開発環境でCloud SQLの常時費用が全体の大部分を占める。
ユーザーはFirestore Standardへの変更、既存テストデータの破棄、短時間の停止を承認した。
既存のAndroid・認証・APIと、確認後にだけ保存する振る舞いは維持する。

## 決定と理由

既存プロジェクトの東京にStandard / Native modeの`(default)` DBを置く。
Core APIがサーバーSDKとADCでアクセスし、Androidの直接アクセスはRulesで拒否する。
Goal Plannerはステートレスのままとし、DB権限を与えない。
利用者認可はCoreが担い、DBの変更でサービスや汎用Repository層を増やさない。

Goal文書に固定上限のMetric・Milestoneを含め、増え続ける実績と再計画候補はサブコレクションに分ける。
小数2桁は100倍の整数で保存する。実績の文書IDは保存IDのSHA-256とし、公開UUIDと分ける。
実績保存はGoalと重複IDを読み、現在値更新と履歴作成を同一transactionで確定する。
再計画も同じGoalを読み書きし、基準版・最新実績・適用状態を検証する。
SDKのcommit競合再試行に加え、読取り中のAbortedはtransaction全体を再試行する。
詳細・助言はread-only transactionの同一snapshotで現在値と履歴を読む。
AI呼出しはtransaction外に置く。

一覧・コーチは所有者を含む索引クエリを使い、全実績を読み込まない。
助言は直近10件、詳細は既存契約どおり全履歴を返す。
索引・除外設定は`infra/gcp/firestore.indexes.json`、保存形式は固定Pydanticモデルで管理する。
SQLAlchemy・Alembicの恒久的な二重対応は設けず、旧コードとイメージは履歴から追跡する。

## 影響と運用

無料枠を超えれば従量課金され、詳細の全履歴取得は利用期間に比例して読取りが増える。
ページングは契約変更を伴うため別Issueで扱う。EmulatorはIAM・索引の検証を代替しない。
ローカル・CIはloopbackのEmulatorと`demo-`プロジェクトを使い、未起動時にクラウドへ接続しない。

定期バックアップ・PITRは設けない。切替時のSQL dumpをローカルPCだけに保管し、期限はユーザーが管理する。
当初のSQLの48時間保持は、切替検証後のユーザー指示により即時削除へ変更した。SQLは2026-11-03に削除済み。Firestoreに新規保存した後のSQL復帰は逆移管の判断が必要となる。
切替・退避・復旧は[移管計画](../plans/firestore-standard-migration.md)と
[運用手順](../../infra/gcp/firestore-migration-runbook.md)に従う。
