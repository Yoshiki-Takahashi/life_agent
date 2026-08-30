# Android App

Kotlin + Jetpack ComposeによるLifeAgentクライアントを配置します。

## Local run

Backendをポート8000で起動してから、Android StudioでdebugアプリをEmulatorへ実行します。Emulatorは`config/api-debug.properties`に定義したURLでMac上のCore APIへ接続します。

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

debugビルドだけローカルHTTPを許可します。Android 17以降では初回起動時にローカルネットワーク権限を許可してください。releaseビルドではこの権限を宣言しません。将来Firebase Authenticationを追加しても、アプリはCore API以外のBackendサービスへ直接接続しません。
