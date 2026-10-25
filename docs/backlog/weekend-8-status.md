# Weekend 8 検証状況

2026-10-25（日本時間）。対象PRは[#16](https://github.com/Yoshiki-Takahashi/life_agent/pull/16)。
開発環境への反映はPRブランチから実施し、mainへのマージとは分けて管理する。

## 実AI回帰

Secret Managerの`lifeagent-openai-api-key`をローカルのGoal Planner評価プロセスだけへ渡し、
既定モデル`gpt-5.6-luna`で評価した。キーの表示・ファイル保存は行わない。

| 検証 | 結果 |
| --- | --- |
| Core API Ruff / pytest | 成功、75件 |
| Goal Planner Ruff / 通常pytest | 成功、26件 |
| 実AI pytest | 最終版で9件成功。再計画3件、進捗解析・助言3件、初期計画3件 |
| Cloud Run上のRe-planner | 読書・アプリ開発・筋トレの3件すべてHTTP 200 |

再計画の合成ケースは既存Metric・Milestoneを含め、以下の内容をレビューした。

- 読書: 保存済み2冊を維持し、目標12冊を6冊へ減らす候補と確認地点を提示した。
- アプリ開発: 認証の課題を先行して整理し、期限を延長して段階的に進める候補を提示した。
- 筋トレ: 最初の評価では、痛みがあるのに運動回数と日程を提案したため不合格とした。
  プロンプトとlive回帰条件を修正した後、目標を現在値3回に留め、痛む運動の休止、休養、
  状態確認、本人による再計画を優先する返答となった。再開日や追加の運動回数は指定しない。

合成ケースの全出力は`/tmp/lifeagent-weekend8-replan-evaluation/`と
`/tmp/weekend8-cloud-planner.json`へ保存した。これらは生成物のためコミットしない。
今回の評価は限定した回帰確認であり、すべての入力に対する出力品質を保証するものではない。

## クラウド反映

- Goal Planner: revision `lifeagent-goal-planner-00007-5dm`へ反映済み。
  [deploy run 40182139982](https://github.com/Yoshiki-Takahashi/life_agent/actions/runs/40182139982)成功。
  Planner / Parser / Advisor / Re-plannerのproviderをすべて`openai`に設定。
- Core API: revision `lifeagent-core-api-00007-bzt`へ反映済み。
  [deploy run 40182546449](https://github.com/Yoshiki-Takahashi/life_agent/actions/runs/40182546449)成功。
  認証は`firebase`、Planner / Parser / Advisor / Re-plannerのbackendは`http`。
- Cloud SQL: `RUNNABLE / ALWAYS`で起動済み。ユーザーの依頼に従い起動を維持。
- Cloud Run: 両サービスはmin 0 / revision max 1を維持。Goal Plannerは匿名アクセスを403で拒否。

DB変更は`20261004_05`まで適用済み。ローカルAuth ProxyからのTCP 3307接続がtimeoutしたため、
Cloud Run Jobによるmigrationへ切り替えた。`lifeagent-db-migrate-ggl96`は成功し、
Cloud Loggingの`Running upgrade 20261002_04 -> 20261004_05`でも適用を確認した。
workflowは今後もCore APIのdeploy前に同じimageのmigration Jobを実行し、失敗時はdeployを停止する。
Jobは実行時だけ稼働する。ローカル用の`migrate-dev.sh`も残す。

クラウド受け入れは`tests/e2e/weekend8_cloud.py`を明示実行する。
`CORE_API_URL`と`FIREBASE_API_KEY`を環境変数で渡し、実Firebase認証、実AI進捗解析、
再計画プレビューの非更新、承認保存、履歴保持、古い候補の409拒否、助言を確認する。
検証用FirebaseユーザーとGoalは合成データとして作成する。

今回の実行では上記8項目すべて成功。全応答は`/tmp/weekend8-cloud.json`に保存した。
承認前のGoalと保存済みGoalの一致、適用後の版の増加・現在値2冊と履歴の維持、古い候補の
409拒否後の非更新を機械検証した。OpenAI SecretはGoal Plannerにだけ設定する境界を維持した。

## 残件

今回依頼された実AI回帰とクラウド反映は完了。PR #16は未マージ。
Androidの再計画操作を含む端末E2Eは今回実施していないため、Weekend 8全体はIn Progressとする。
次の最初の作業は、クラウドへ接続したAndroidで再計画候補の表示・取消・承認を一周確認すること。
