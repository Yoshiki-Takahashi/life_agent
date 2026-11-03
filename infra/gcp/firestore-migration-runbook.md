# Firestore移管ランブック（Issue #23）

方針・対象外・復旧条件は[移管計画](../../docs/plans/firestore-standard-migration.md)に従う。
既存SQLのテストデータを移さず、同じFirebaseアカウントで空のFirestoreから開始する。
切替枠はユーザーの回答後に確定する。ここにある切替・削除コマンドは、その条件を満たしてから実行する。

## 準備の実績（2026-11-03）

- Firestore: `lifeagent-505614 / (default)`、`asia-northeast1`、STANDARD / FIRESTORE_NATIVE。
- 無料枠対象`freeTier=true`、PITR無効、削除保護有効。定期バックアップなし。
- Core IAMは対象DB限定のdatastore.user、Rules全拒否、複合索引3件READY。
- 元のSQL版: `lifeagent-core-api-00008-7d6`。利用中トラフィック100%を保持して準備する。
- 候補は`--no-traffic`で配置し、`firestore-check`タグで認証付きHTTPを確認する。
- Core実行アカウントの検証Job `lifeagent-firestore-check-89pxg`で、保存・小数・並行再送・二重適用・所有者分離に合格。
- 最終候補: `lifeagent-core-api-firestore23b`。maintenance: `lifeagent-core-api-firestore23-maint`。
  image: `asia-northeast1-docker.pkg.dev/lifeagent-505614/lifeagent-dev/lifeagent-core-api@sha256:f6d043a2e3d1bdeec91aef740723c16e956fd869c04c1f6307dd9ce511627a2c`。
  両revisionはtraffic 0%。候補タグで実Firebase・全AI backend=http / provider=openai・FirestoreのHTTP検証にも合格。
- APIテスト33件と読取り競合の追加回帰1件、内部AIサービス10件、HTTP結合1件・同時保存2件、
  Android unit/assemble・AppFlowTest 1件に合格。実AIの三領域テストは維持し、今回は全領域の再評価は実行していない。
- ローカルdumpの復元リハーサル: 約3秒、約20KB。現在は9 Goal / 6実績 / 2候補。
  これは切替前のリハーサルであり、書込み停止後に最終dumpを必ず取り直す。

元のCloud Run設定とdumpはリポジトリ外の非公開ディレクトリ
`~/.local/share/lifeagent/migrations/20261103-rehearsal/`に保管する。
dumpは`legacy-verified.dump`。保存期限はユーザー管理。中身・接続値・TokenをIssueやログへ転載しない。

## 事前確認

1. PRのBackend検査、サービス境界・HTTP同時保存、Android unit/assemble/UI経路が合格していること。
2. `scripts/gcp/check-firestore.sh`が合格すること。Rulesのリリース、バックアップ設定なしも確認する。
3. 最終image digest、候補revisionとmaintenance revision、元のrevisionを実施記録に固定する。
   CoreのFirebase・内部AI URL/audience/backendを維持し、SQL attachment・DB_PASSWORD・DB設定を候補から外す。
   `AUTH_BACKEND=firebase`であり、Emulator環境変数・OpenAI Secretがないことを確認する。
4. `firestore-check`だけで候補へ到達できることを確認する。元のrevisionへのタグがないこと、通常URLがSQL版100%であることを確認する。
5. 実Firestore検証用Goal・その実績/候補と一時Firebase利用者を整理する。一般利用者や既存アカウントは削除しない。
6. 実施枠の目安は15分。元のrequest timeout分の排出待ち、最終dump/復元確認、ルーティング、Android受け入れの余裕を含む。
   退避失敗または15分以内の再開が難しい場合は、新規業務データを受ける前に元のSQL版へ戻す。

## メンテナンスと最終退避

同じ検証済みimageで`MAINTENANCE_MODE=true`だけを変え、トラフィックなしのmaintenance revisionを準備する。
以降の`MAINTENANCE_REVISION`と`CANDIDATE_REVISION`は実在とReadyを確認した名前を使う。
実行前に元の設定・trafficをローカルへ記録する。gcloudの最新版LATEST指定を使わない。

```bash
gcloud run services update-traffic lifeagent-core-api \
  --project=lifeagent-505614 --region=asia-northeast1 \
  --to-revisions="${MAINTENANCE_REVISION}=100" --clear-tags
```

- 通常URLの`/api/v1/goals`が503、`Retry-After: 60`、`/health`が200であることを確認する。
- trafficとtagを再取得して旧revision・検証タグの入口がないことを確認する。
- 旧revisionのrequest timeout（準備時は120秒。実施時にもdescribeで再確認）以上待ち、最後の処理中要求が終わったことをrequestログとSQL接続で確認する。
  ルーティング変更だけで実行中の要求が消えたとは扱わない。稼働中の旧Jobがないことも確認する。
- SQL Auth Proxyをloopbackだけで起動する。停止後はProxyも終了する。

```bash
cloud-sql-proxy lifeagent-505614:asia-northeast1:lifeagent-dev-db \
  --address=127.0.0.1 --port=15438
uv run --no-project python scripts/gcp/backup-legacy-sql.py \
  --output /Users/yoshiki/.local/share/lifeagent/migrations/<切替日時>/legacy-final.dump
```

dump一覧・SHA256・隔離ローカルDBへの復元がすべて合格した場合だけ続行する。
このスクリプトはCloud Storageへ送らず、パスワードをコマンド引数・ファイルへ書かない。
バックアップは一貫snapshotだが、排出確認を省略する理由にはならない。

## 利用再開と受け入れ

業務コレクションが空であることを確認し、候補を100%へ寄せる。

```bash
gcloud run services update-traffic lifeagent-core-api \
  --project=lifeagent-505614 --region=asia-northeast1 \
  --to-revisions="${CANDIDATE_REVISION}=100" --clear-tags
```

通常URLのhealth200、未認証401、既存Firebaseログイン、空一覧→新規目標→実績→助言→再計画→再訪を確認する。
Androidの旧一覧・候補は再読込みする。確認済み実績の再送が二重加算しないことも確認する。
新規業務データを受け始めた後は、SQL版に戻すだけのrollbackを行わない。
Firestore側で修正し、SQL復帰が必要なら新データを退避し逆移管をユーザーへ相談する。
成功した切替時刻と48時間後の最短撤去時刻をJSTでIssueに記録する。

## 48時間の観察・旧SQLの撤去

時間経過だけで削除しない。5xx/競合失敗、保存の整合性、Firestore read/write/storage、旧SQLへの接続・書込みがないことを確認する。
旧SQLを保持している間は料金が継続する。受け入れ不合格なら削除せず調べる。
合格後に次を参照照合し、対象を明記して撤去する。

1. 全Cloud Run service/revisionのtraffic/tag、稼働中Job、workflowにSQLへの到達経路がないことを確認する。
   旧SQL revisionを削除し、旧migration Job `lifeagent-db-migrate`を削除する。
2. `lifeagent-dev-db`の対象、48時間経過、最終dumpとチェックサムを再確認する。
   SQLの削除保護を解除し、同インスタンスを削除する。保持されるバックアップ・IPの有無を確認する。
3. `lifeagent-db-password`、Coreのcloudsql.client、GitHub deployerのcloudsql.admin、SQL専用GitHub Variable `CLOUD_SQL_CONNECTION_NAME`を
   他用途で使わないことを確認して整理する。Firestore・Firebase・OpenAI Secret・内部AI IAMを保持する。
4. `budget-alerts.json`からSQL専用予算を外し、同じ予算だけを削除する。全体予算と通知を残す。
5. SQLをlistで再取得し、消えていることを確認する。削除日時、残存依存の確認結果をIssueへ記録する。
6. 課金反映後、削除後利用日のSQL compute/storage/IPが止まり、Firestore使用量が想定内であることを確認する。
   SQL管理権限を外した後はstatusスクリプトがSQL一覧を取得できない場合があるため、削除証跡をIssueに残す。

## 最終記録

Issue #23へimage digest、revision、検証結果、最終dumpの保存先・復元結果、JST切替時刻、
48時間観察、削除結果、課金確認を記録する。利用者データと秘密値は載せない。
実装PRのmergeだけで移管全体を完了扱いにしない。
