# GCP Development Environment

対象リソース:

- Cloud Run
- Artifact Registry
- Firebase Authentication
- Secret Manager
- Cloud Logging
- Cloud SQL（クラウドDB結合が必要になるまで作成しない）

リソース作成時は、設定値、課金抑制策、削除手順を同じ変更で記録します。

Weekend 5では、Google Cloud Consoleによる初回作成とユーザー端末のCLIによる確認・開始・停止・削除訓練を[Weekend 5 GCP実習ランブック](weekend-5-runbook.md)の順序で行います。進行状況と作成済みリソースは[Weekend 5 Status](../../docs/backlog/weekend-5-status.md)へ記録します。

日常の状態確認、開始、課金抑制のための停止には、リポジトリルートから次を実行します。

```bash
./scripts/gcp/status-dev.sh
./scripts/gcp/start-dev.sh
./scripts/gcp/stop-dev.sh
```

`destroy-dev.sh`は既定では削除候補の表示だけを行います。実削除は`--execute`、対話可能な端末、project IDと確認語の一致がすべて必要です。GCP project、Firebase project、Firebase user、Billing Budgetは削除対象に含みません。

## Billing alerts

CLIで管理するalerts-only予算は[`budget-alerts.json`](budget-alerts.json)に定義し、次のコマンドで未作成分を適用します。

```bash
./scripts/gcp/apply-budget-alerts.sh
```

Cloud Run Spend Cap `max2000`はPreview機能であり、`gcloud billing budgets`の管理対象外です。設定ファイルには期待状態を記録しますが、変更はGoogle Cloud Consoleで行います。通常のalerts-only予算は通知を行うだけで、利用を自動停止しません。
