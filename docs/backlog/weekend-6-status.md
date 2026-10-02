# Weekend 6 Status

## Summary

| Item | Status | Notes |
| --- | --- | --- |
| Weekend 6 plan integration | Complete | 6.A相当の自動デプロイと6.B相当のGoal Plannerクラウド結合をWeekend 6前半へ統合した |
| Development deploy workflow | Complete | GitHub Actions run `36949309163`でCore APIとGoal Plannerのbuild、push、deploy、health確認が成功 |
| Development close workflow | Complete | GitHub Actions run `36954436853`でCloud SQL停止とCloud Run scaling確認が成功 |
| Workload Identity Federation | Complete | GitHub Actions用pool/provider、deployer service account、GitHub environment Variablesを作成・更新した |
| Goal Planner runtime identity | Complete | `lifeagent-goal-planner@lifeagent-505614.iam.gserviceaccount.com`を作成した |
| OpenAI Secret | Complete | version 1は無効化済み。改行除去済みのversion 2を登録し、Goal Planner runtime service accountへSecret Accessorを付与した |
| Goal Planner Cloud Run | Complete | private Cloud Runとして作成し、Core APIから認証付きでOpenAI計画生成まで通した |
| ProgressLog implementation | Complete | Backend API、migration、Android詳細画面、unit test、エミュレータCompose testを追加 |

## Resource State

| Resource | Name | State |
| --- | --- | --- |
| GCP project | `lifeagent-505614` | Active |
| Region | `asia-northeast1` | Active |
| GitHub repository | `Yoshiki-Takahashi/life_agent` | Connected |
| GitHub environment | `development` | Variables configured |
| WIF pool | `github-actions` | Created |
| WIF provider | `lifeagent` | Created, repository restricted to `Yoshiki-Takahashi/life_agent` |
| Deployer service account | `lifeagent-github-deployer@lifeagent-505614.iam.gserviceaccount.com` | Created |
| Core API runtime service account | `lifeagent-core-api@lifeagent-505614.iam.gserviceaccount.com` | Existing |
| Goal Planner runtime service account | `lifeagent-goal-planner@lifeagent-505614.iam.gserviceaccount.com` | Created |
| Core API Cloud Run | `lifeagent-core-api` | Existing |
| Goal Planner Cloud Run | `lifeagent-goal-planner` | Ready / private / revision `lifeagent-goal-planner-00005-c9z` |
| OpenAI Secret Manager secret | `lifeagent-openai-api-key` | Version 2 enabled, version 1 disabled |

## GitHub Environment Variables

`development` environmentに次のVariablesを設定済み。

- `GCP_PROJECT_ID`
- `GCP_REGION`
- `ARTIFACT_REGISTRY_REPOSITORY`
- `GCP_WORKLOAD_IDENTITY_PROVIDER`
- `GCP_DEPLOYER_SERVICE_ACCOUNT`
- `CORE_API_SERVICE`
- `CORE_API_RUNTIME_SERVICE_ACCOUNT`
- `GOAL_PLANNER_SERVICE`
- `GOAL_PLANNER_RUNTIME_SERVICE_ACCOUNT`
- `CLOUD_SQL_CONNECTION_NAME`
- `OPENAI_SECRET_NAME`

## Log

| Date | Item | Result | Evidence |
| --- | --- | --- | --- |
| 2026-10-02 | W6-00 | Pass | Weekend 6計画を統合し、6.A/6.Bを独立枠からWeekend 6前半へ移動 |
| 2026-10-02 | W6-10 | Pass | `deploy-dev.yml`を追加。Core APIとGoal Plannerのbuild、push、deploy、health確認、Goal Planner private serviceへのInvoker付与を手動dispatchに定義。Secret登録前は`fake` providerでservice境界を検証できる |
| 2026-10-02 | W6-11 | Pass | Core APIのHTTP Plannerに`GOAL_PLANNER_ID_TOKEN_AUDIENCE`を追加し、private Cloud Run呼び出し用ID tokenを付与できるようにした |
| 2026-10-02 | W6-12 | Pass | `setup-dev-deploy.sh`でWIF pool/provider、deployer service account、Goal Planner runtime service account、GitHub Variablesを作成・更新 |
| 2026-10-02 | W6-13 | Blocked | `OPENAI_API_KEY`環境変数がなく、`lifeagent-openai-api-key` Secretも未作成。実AI Goal Planner deployはSecret登録後に実施 |
| 2026-10-02 | W6-14 | Pass | deployerにArtifact Registry Writer、Cloud Run Admin、Secret Manager Viewerを付与。Core APIとGoal Plannerのruntime service accountへService Account Userを付与。Core API runtime service accountに`lifeagent-db-password` Secret Accessorがあることを確認 |
| 2026-10-02 | W6-15 | Info | ローカルDocker daemonが停止中のため、手元でのimage build/pushは未実行。`deploy-dev` workflowをGitHub Actions上で実行してbuild/push/deployを検証する |
| 2026-10-02 | W6-16 | Pass | `lifeagent-openai-api-key` Secret version 1を登録し、Goal Planner runtime service accountにSecret Accessorを付与。`.secrets/openai-api-key.txt`は`.gitignore`で無視されることを確認 |
| 2026-10-02 | W6-17 | Pass | `lifeagent-goal-planner`をprivate Cloud Runとしてdeployし、Core API runtime service accountへInvokerを付与。匿名アクセスは403で拒否されることを確認 |
| 2026-10-02 | W6-18 | Pass | Core APIを`GOAL_PLANNER_BACKEND=http`で再deployし、private Goal Planner URLとID token audienceを設定。`/health`は200を返した |
| 2026-10-02 | W6-19 | Blocked | Core API経由previewはGoal Plannerまで到達したが、Secret version 1の末尾改行によりOpenAI SDKが不正headerとして拒否。診断ログにkeyが出たためversion 1を無効化し、ローカルkeyファイルを削除 |
| 2026-10-02 | W6-20 | Pass | 改行除去済みのOpenAI keyをSecret version 2として登録し、Goal Planner revision `lifeagent-goal-planner-00004-j87`をdeploy。Core API経由previewが200を返し、Metric 3件、Milestone 5件の実AI計画案を取得 |
| 2026-10-02 | W6-21 | In Progress | deploy-dev workflow上でのdeploy成功確認は未実施。manage-dev workflowを追加し、Cloud SQL停止とCloud Run scaling確認をworkflow化した。deployerへCloud SQL Adminを付与し、workflow定義、Backendテスト、script構文検査は成功。次にGitHub Actions上で両方を実行して証跡を残す |
| 2026-10-02 | W6-22 | Fix | `deploy-dev`はGitHub Actions上で成功。`manage-dev stop`はCloud SQL停止まで成功したが、Goal Plannerのmax scale検証がservice-level annotationだけを読んだため失敗。template annotationを優先して読むよう修正する |
| 2026-10-02 | W6-23 | Pass | GitHub Actions上で`deploy-dev` run `36949309163`が成功し、Core APIとGoal Plannerのbuild、push、deploy、health確認が完了。`manage-dev stop` run `36954436853`が成功し、Cloud SQL `STOPPED / NEVER`、Core API revision `lifeagent-core-api-00004-2sw`、Goal Planner revision `lifeagent-goal-planner-00005-c9z`、Cloud Run `min=0 / max=1`を確認 |
| 2026-10-02 | W6-24 | Pass | ProgressLogとProgressMetricUpdateのmigration、`Metric.current_value`、`POST /api/v1/goals/{goal_id}/progress`を追加。所有者分離、別Goal Metric拒否、目標超過拒否、`client_request_id`再送時の二重反映防止をBackend testで確認 |
| 2026-10-02 | W6-25 | Pass | Android Goal詳細画面に進捗メモ、Metric今回値、保存中・失敗表示、現在値、履歴表示を追加。`./gradlew testDebugUnitTest assembleDebug`成功 |
| 2026-10-02 | W6-26 | Pass | `Pixel_8` AVDで`./gradlew connectedDebugAndroidTest`を実行し、エミュレータ上の5件のinstrumented/Compose testが成功 |

## Next Action

1. OpenAI Dashboardでversion 1に使った旧keyがrevoke済みであることを確認する。
2. 実AI計画生成の読書・アプリ開発・筋トレ回帰評価を明示実行し、結果を記録する。
3. Androidから実Firebase認証、実AI計画、確認、保存、再訪、進捗登録、再訪をクラウド環境で確認する。
