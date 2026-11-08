# GCP Development Environment

現在の実装はCore API・内部AIサービスのCloud Run、Firestore Standard、Firebase Authentication、
Artifact Registry、Secret Manager、Cloud Loggingを使用します。
2026-11-03 23:39（日本時間）にFirestore版へ切替済みです。ユーザーの即時削除指示に基づき、旧SQLも23:48に削除しました。
切替・旧SQLの即時撤去の実績は[移管ランブック](firestore-migration-runbook.md)で管理します。
[Weekend 5実習](weekend-5-runbook.md)とarchiveは過去のSQL構成の記録です。

## 状態・開始・停止

```bash
./scripts/gcp/status-dev.sh
./scripts/gcp/start-dev.sh
./scripts/gcp/stop-dev.sh
```

startはCloud Runをauto/min 0/max 1へ戻し、stopはmanual 0へ変更します。
Firestoreは停止せずデータを保持します。無料枠超過の保存・操作は課金対象です。
旧Cloud SQLは撤去済みで、これらのコマンドでは再作成・再起動しません。
`manage-dev.yml`も同じスクリプトを使います。
`deploy-dev.yml`はmain更新後に自動実行します。手動再実行もmainを選びます。
通常のBackend QualityはPRとpushで動き、デプロイ処理とは別に継続します。

`destroy-dev.sh`は既定で候補表示だけを行います。実削除には`--execute`と端末での二重確認が必要です。
対象にFirestoreの全データを含みます。project、Firebase利用者、予算は削除しません。
旧SQLの削除証跡は移管ランブックに記録しています。

## Firestore

DBは`lifeagent-505614 / (default)`、東京`asia-northeast1`、Standard / Native modeです。
DB作成は既存一覧・無料枠対象を調べてから明示実行し、setupでは作り直しません。

```bash
./scripts/gcp/setup-firestore.sh
./scripts/gcp/check-firestore.sh
```

Coreの実行アカウントに対象DB限定の`roles/datastore.user`を与えます。
Android向けRulesは全拒否で、サーバーSDKの利用者認可はCore内で行います。
索引と検索しないフィールドの除外は`firestore.indexes.json`、Rulesは`firestore.rules`を配置します。
索引がREADYになるまで切り替えません。定期バックアップ・PITRは設定しません。

非公開のCloud Run検証Jobで実IAM・索引・同時保存を確認できます。Job内だけでFake AIと検証UIDを使います。
公開サービスのFirebase検証を置き換える処理は含みません。
`check-android-cloud.py`は使い捨てアカウントで書込みを行うため、本人uidに限定した稼働サービスでは403になります。
本人の制限を解除して実行せず、検証用サービスを分けて使用してください。

```bash
CORE_API_IMAGE=<Artifact Registryの検証対象image@sha256:digest> ./scripts/gcp/check-firestore-api.sh
```

## デプロイ

`deploy-dev.yml`はmainへのpush（PRマージを含む）と手動実行を受け付け、mainだけを対象にします。
Backend lint・単体／結合・競合テストの成功後、同じコミットから両サービスのimageをbuild/pushし、digestで配置します。
手動実行でも両サービスを実OpenAI設定で更新します。従来の片方だけの配置・Fake切替入力は使用しません。
Androidアプリの配布と操作確認は[共通手順](../../docs/development-workflow.md)の別段階です。

1. `scripts/gcp/check-firestore.sh`でDB・索引を確認する。
2. `scripts/gcp/deploy-dev.py`が元の配信先を保存し、PlannerとCoreを`--no-traffic`の候補版として準備する。
3. Coreは同じ実行で作ったPlannerのタグURLへ固定する。IAM TokenのaudienceはPlannerのサービスURLを使う。
4. 候補Coreのhealth・未認証／不正Tokenの401を確認し、非公開Job `lifeagent-deploy-check`を実行する。
5. 検証成功後、Core→Plannerの順にrevision名を指定して100%へ切り替え、サービスURLでも確認する。

非公開Jobは候補Coreのimageと実行アカウントで`scripts/check-deployment.py`を実行します。
Job内だけで一時的な検証用認証に置き換え、固有の検証UIDを使います。公開CoreのFirebase認証・許可UIDは変更しません。
実HTTPで候補Plannerを呼び、実OpenAIによる質問・計画生成、実Firestoreへの保存・再送を検証します。
終了時はそのUIDの検証データだけを削除します。実利用者によるFirebaseログインの成功確認は、リリース確認で別途行います。
Jobは非公開の実行単位で、アプリの接続先ではありません。追加のOpenAI呼出しは通常1デプロイにつき2回です。

同時デプロイは直列化し、開始時と切替直前にmainの先頭コミットと一致するか確認します。
古いコミットの実行は`skipped-outdated`として切替を省略します。
通常の検証・切替失敗では保存した配信先へ復旧します。DBの内容は巻き戻しません。
復旧にも失敗した場合は`rollback-failed`として失敗終了し、元のrevisionと配信率をActionsに残します。

結果はActionsのSummaryと`deployment-result.json`のartifactへ、コミット・image digest・候補revision・元の配信先・検証Jobを記録します。
`deployed`はサーバー反映成功、`failed-before-cutover`は切替前の失敗、`rolled-back`は切替失敗からの復旧成功です。
runner喪失・強制終了時は自動復旧が完了しない場合があるため、Cloud Runの実際の配信先も確認します。

手動で元の配信先へ戻す場合は、記録された**両サービス**のrevision名と配信率を使います。
メトリクス種別対応（#47）で版2のGoal／実績を書き込んだ後は、版2と全種別を読めるCore・対応Plannerへ戻します。
旧Coreへ戻すために種別フィールドや実績を削除しません。新版を初めて配置する回では、
候補検証の段階で新版だけを使用し、利用者の書込み後の手動復旧先には対応版を選びます。
Coreは対応Plannerのタグを参照するため、成功したPlannerの`deploy-*`タグは復旧対象Coreが不要になるまで削除しません。
Coreの確認用タグと、失敗・旧コミットによる未採用タグは自動で片付けます。
旧SQL migration Jobは実行しません。

WIF・権限・共通のGitHub Variablesは`./scripts/gcp/setup-dev-deploy.sh`で準備します。
`CORE_API_ALLOWED_USER_ID`は、本人の既存uidを確認してenvironmentへ別途登録します。
environment `development`の変数:

```text
GCP_PROJECT_ID=lifeagent-505614
GCP_REGION=asia-northeast1
ARTIFACT_REGISTRY_REPOSITORY=lifeagent-dev
GCP_WORKLOAD_IDENTITY_PROVIDER=<projects/.../providers/...>
GCP_DEPLOYER_SERVICE_ACCOUNT=<deployer email>
CORE_API_SERVICE=lifeagent-core-api
CORE_API_RUNTIME_SERVICE_ACCOUNT=lifeagent-core-api@lifeagent-505614.iam.gserviceaccount.com
CORE_API_ALLOWED_USER_ID=<Firebase管理APIで本人のメールから照合した既存uid>
GOAL_PLANNER_SERVICE=lifeagent-goal-planner
GOAL_PLANNER_RUNTIME_SERVICE_ACCOUNT=<planner runtime email>
OPENAI_SECRET_NAME=lifeagent-openai-api-key
```

CoreにFirestoreの接続先とFirebase設定を渡し、DBパスワード・Cloud SQL attachmentは外します。
検証専用アカウントを追加する場合は、任意の`CORE_API_ALLOWED_USER_IDS`へ追加UIDをカンマ区切り（空白なし）で登録します。
本人の`CORE_API_ALLOWED_USER_ID`は維持し、Coreは両設定の和集合だけを許可します。
`CORE_API_ALLOWED_USER_ID`はCoreの`ALLOWED_USER_ID`、追加設定は`ALLOWED_USER_IDS`へ渡します。未設定ならworkflowはデプロイを中止します。
workflow内ではデプロイ用の変数名で保持し、候補検証・切替ステップだけに`ALLOWED_USER_ID`を渡します。
事前のテストには利用者制限を引き継がず、複数のテスト用ユーザーによる認可・所有者分離を確認します。
Cloud Run自身も、許可uid未設定・空文字・Fake認証では起動を拒否します。
ローカルの`.env`は、複数利用者の回帰テスト時には`ALLOWED_USER_ID`を定義しません（空文字の代入も不可）。
本人のuidはToken検証後に照合し、他のアカウントによる業務APIを403とします。healthは公開のままです。
Firebase新規登録自体は変更しません。メールを変更しても同一uidの許可は継続します。
削除後に同じメールで再作成しても別uidなので拒否され、管理者による設定更新が必要です。
OpenAIキーは内部サービスだけが参照します。自動・手動デプロイとも実OpenAIを使い、Coreは内部サービスへHTTPで接続します。
通常のPR検査はFakeです。AI品質評価は明示実行し、候補版のデプロイ確認では実AIを使います。キー登録には`register-openai-secret.sh`を使います。

改善メモの送信を有効にする場合は[GitHub連携の設定](../../backend/README.md#改善メモのgithub連携)に従い、
`scripts/gcp/register-feedback-secret.sh`で対象リポジトリ限定・Issues書込み用Tokenを登録します。
`development`環境の`FEEDBACK_GITHUB_REPOSITORY`と`FEEDBACK_GITHUB_SECRET_NAME`を使い、
デプロイ時にCoreだけへSecretを割り当てます。Plannerと検証Jobには渡しません。
送信先リポジトリが未登録・空の場合は`Yoshiki-Takahashi/life_agent`を使います。workflowではデプロイ用の別名で保持し、Core APIの設定名へ渡すのは配備ステップだけに限定します。
未設定でもデプロイと既存機能は利用でき、改善メモの送信だけが設定エラーを返します。

## 課金

`budget-alerts.json`と`apply-budget-alerts.sh`はalerts-only予算を扱います。通知は自動停止ではありません。
Cloud Run Spend Cap `max2000`はPreviewであり、このスクリプトの管理外です。
旧SQL専用の予算整理は撤去時に行い、全体予算を残します。
無料枠と制限は[Firestore料金](https://firebase.google.com/docs/firestore/pricing)、
サービス停止は[Cloud Run manual scaling](https://docs.cloud.google.com/run/docs/configuring/services/manual-scaling)を確認してください。

専用アカウントによるPR版Android→現行GCPの検査は[Android GCP検証手順](../../docs/android-gcp-check.md)を参照。
未設定時のcloud jobはskipで、実GCP確認済みとは扱わない。
