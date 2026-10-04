# Weekend 7 Status

2026-10-02。機能実装、ローカルPostgreSQL、エミュレータ、実OpenAIの確認を完了。2026-10-02 UTCに進捗解析・助言のユーザー価値評価も追加で完了。2026-10-03 UTCにCloud Runと実Firebaseを通す受け入れ確認を完了したため、Weekend 7はCompleteとする。

## 実装結果

| 項目 | 状態 | 内容 |
| --- | --- | --- |
| W7-01 解析候補 | Complete | 固定Schema、独立Parser Port、内部AI接続サービスのFake・OpenAI Provider、未保存preview、所有者・Metric・値検証 |
| W7-02 候補確認と保存 | Complete | Androidで修正・取消・手入力、本文変更で候補無効化、再試行ID保持、保存中の編集・連打防止 |
| W7-03 助言 | Complete | 保存済みデータから生成、保存とは独立した取得・失敗・再試行、計画変更なし |
| W7-04 実AI・運用 | Complete | 実AI Adapter・3分野評価・ローカルAPI経由のエミュレータ確認、品質評価、Cloud Run＋実Firebase受け入れを完了 |

進捗履歴用migration `20261002_04_add_progress_logs`を追加した。保存処理はGoal行をロックし、同時送信でも二重加算や更新消失を防ぐ。AI生成中はDB transactionを保持しない。Parser・Advisorの公開Portと最終検証はCore APIに残し、OpenAI SDK・プロンプト・Structured Output生成はGoal Planner Service配下の内部AI接続APIへ移した。Core APIはOpenAI Secretを持たない。

## 検証結果

| 検証 | 結果 |
| --- | --- |
| Core API Ruff / pytest | 成功（通常64件） |
| Goal Planner Ruff / pytest | 成功（通常23件、live_ai 6件は通常実行でskip） |
| 実AI回帰評価 | 読書・アプリ開発・筋トレの3件成功。解析・保存・助言を各分野で確認（計6回のOpenAI呼び出し） |
| Android testDebugUnitTest / assembleDebug | 成功（単体20件） |
| Pixel_8 / Android 17 instrumented test | 既存5件＋新しいHTTP E2E 1件、計6件成功 |
| PostgreSQL同時保存 | 同じIDの6並行送信で履歴1件・加算1回、異なるIDの6並行送信で履歴6件・加算6回、2件成功 |
| 実AI接続エミュレータE2E | 追加1件成功。Parser・Advisorをopenaiへ切替、解析2回・助言1回の実呼び出し |
| 実AI品質評価 | 主19ケースx2回、追加6ケースx2回の計50応答を実OpenAIで確認。Parser候補26/26一致、Advisor 24/24が内容レビュー合格 |
| Cloud Run / 実Firebase受け入れ | 成功。2026-10-03にCore API revision `lifeagent-core-api-00005-q4v`で確認。2026-10-04にADR 0002反映後のCore API revision `lifeagent-core-api-00006-mrp`、Goal Planner revision `lifeagent-goal-planner-00006-665`でも、実Firebase ID token、Cloud SQL、Goal Planner / AI接続サービス経由のOpenAI Goal Planner / Parser / AdvisorでGoal作成、進捗解析、保存、助言を確認 |
| Android Cloud build / Emulator | 成功。`-Plifeagent.useCloudFirebase=true`のdebug APKをbuild/installし、実Firebaseログイン後にCloud RunからGoal一覧を取得 |
| Workflow / scripts | YAML読込、各runのbash構文、script構文、git diff --check成功。ADR 0002反映後の`deploy-dev` run `37179466671`が成功し、CoreのOpenAI Secretを外し、Goal PlannerへProgress/Advisor providerを設定した状態でCloud Runへdeploy済み |

Cloud受け入れ時に、Cloud SQLのmigrationが`20260912_03`で止まっていたため、Auth Proxy経由で`20261002_04_add_progress_logs`を適用した。適用後、`progress_logs`と`progress_metric_updates`の存在を確認した。最初のGoal保存500は、この未適用schemaが原因だった。

エミュレータでは「今日は2冊読み終えた」→候補2冊（この時点で履歴0件）→取消→再解析→今回値1冊へ修正→保存→助言障害を注入→進捗維持→助言だけ再試行→履歴と現在値を再取得、を確認した。助言障害はAndroid Repositoryで一度だけ注入し、API側の503と非更新はBackendテストでも別途検証している。

最初のエミュレータ実行はロック状態でComposeを取得できず失敗。解除後、既存画面は成功。ホストへの`10.0.2.2`接続はtimeoutしたため、E2Eは`adb reverse`で接続する方式に変更し、再実行を成功させた。

## 再現とスクリーンショット

起動済みエミュレータとDocker、uvを用意し、リポジトリルートから実行する。

```bash
ANDROID_HOME="$HOME/Library/Android/sdk" ./scripts/test-weekend-7.sh --android
```

通常はFake認証・Fake AI。専用DBとAPIは終了時に停止・削除する。画面画像と実行記録は`android/app/build/weekend7/`（`W7_OUTPUT_DIR`で変更可能）へ保存する。生成物はコミットしない。

- `files/weekend7-candidates.png`: 解析候補の確認と編集
- `files/weekend7-advice.png`: 保存後の助言
- `files/weekend7-advice-retry.png`: 進捗を維持した助言再試行
- `files/weekend7-history.png`: 保存後の再訪・履歴

今回の実AI接続版画像は作業結果として別途添付する。画像は実際のエミュレータ画面を取得したもので、テスト用Activityに製品のGoal詳細画面・ViewModelを表示し、本物のHTTP RepositoryとCore API・PostgreSQLを接続した。FirebaseログインとCloud RunはこのE2Eに含まれない。

進捗解析・助言のユーザー価値評価は[進捗解析・助言 実AI品質評価結果](../progress-ai-quality-results.md)を参照する。生成された全JSONは証跡として作業成果ディレクトリへ保存し、リポジトリにはコミットしない。

Cloud受け入れのAndroidスクリーンショットは作業成果ディレクトリ`weekend7-quality/`へ保存した。

- `android-cloud-login.png`: Cloud Firebase設定のdebug APK起動直後
- `android-cloud-auth.png`: 実Firebaseログイン画面
- `android-cloud-goal-list.png`: 実Firebaseログイン後、Cloud Runから取得したGoal一覧

## 残件と次の作業

Weekend 7としての実装・ローカル検証・実AI品質評価・Cloud受け入れは完了。2026-10-04にADR 0002へ合わせ、Core APIからOpenAI直接接続を除去し、Progress Parser / Advisorの実AI生成をGoal Planner Service配下の内部AI接続APIへ移した。同日、`deploy-dev` run `37179466671`と実Firebase tokenによるCloud API確認まで完了した。確認後、Cloud SQLは停止し、Cloud Runはmin 0 / max 1のrequest-driven設定を維持する。Weekend 6の旧キー失効確認等の残件は同ステータスで引き続き追跡する。

クラウド受け入れ完了後、Weekend 8の計画変更差分Schemaへ進む。
