# Android App

Kotlin + Jetpack ComposeによるLifeAgentクライアントを配置します。

## Local run

Backendをポート8000で起動してから、Android StudioでdebugアプリをEmulatorへ実行します。Emulatorは`config/api-debug.properties`に定義したURLでMac上のCore APIへ接続します。

Debug buildは`10.0.2.2:9099`のFirebase Authentication Emulatorにも接続します。
リポジトリのルートで`./scripts/start-auth-emulator.sh`を実行し、ログイン画面から
メールアドレスと6文字以上のパスワードでアカウントを作成してください。Release buildを
Firebaseへ接続する前に`config/auth-release.properties`をFirebase Consoleの公開アプリ設定へ
置き換えます。サービスアカウント鍵はAndroidへ置きません。

ログイン後は自分のGoal一覧がホームに表示されます。一覧から詳細へ再訪でき、新規作成ではタイトル、説明、期限から計画を生成し、MetricとMilestoneを編集・確定できます。プレビューは保存されず、「この計画で保存する」を選んだ時だけBackendへ保存されます。

```bash
cd android
./gradlew testDebugUnitTest assembleDebug
```

接続先はビルド種別ごとに分離しています。

| ビルド種別 | 設定ファイル | 用途 |
| --- | --- | --- |
| debug | `config/api-debug.properties` | EmulatorからMac上のFastAPIへ接続 |
| release | `config/api-release.properties` | Cloud Run上のFastAPIへ接続 |

Cloud Runのデプロイ後に、`api-release.properties`の予約URLを発行されたHTTPS URLへ置き換えてください。Retrofitの要件により、URL末尾の`/`は必須です。APIのURLは公開情報であり、APIキーなどの秘密情報はこのファイルへ記載しません。

debugビルドだけローカルHTTPを許可します。Android 17以降では初回起動時にローカルネットワーク権限を許可してください。releaseビルドではこの権限を宣言しません。認証後も、アプリはCore API以外のBackendサービスへ直接接続しません。
