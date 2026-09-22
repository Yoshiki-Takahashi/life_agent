# Development Scripts

複数コンポーネントの起動、検証、デプロイを補助する小さなスクリプトを配置します。スクリプトは対話操作に依存せず、READMEにある標準コマンドを呼び出す形にします。

- `test-weekend-3.sh`: 隔離されたPostgreSQL、独立Fake Goal Planner、Core APIを通すキー不要E2E
- `start-auth-emulator.sh`: Firebase Authentication Emulatorをローカルで起動
- `gcp/status-dev.sh`: Weekend 5 GCP開発環境の状態を変更せず表示
- `gcp/start-dev.sh`: Cloud SQLを開始し、Cloud Runのhealthを確認
- `gcp/stop-dev.sh`: Cloud SQLを停止し、Cloud Runがmin 0 / max 1であることを確認
- `gcp/destroy-dev.sh`: 削除対象を表示。`--execute`と二重の対話確認がある場合だけ完全削除

日常の開始・終了では次を使用します。

```bash
./scripts/gcp/start-dev.sh
./scripts/gcp/stop-dev.sh
```

削除候補の確認は引数なしで実行します。このコマンドだけでは削除されません。

```bash
./scripts/gcp/destroy-dev.sh
```
