# Android App

Kotlin + Jetpack ComposeによるLifeAgentクライアントを配置します。

## Local run

Backendをポート8000で起動してから、Android StudioでdebugアプリをEmulatorへ実行します。Emulatorは`http://10.0.2.2:8000`でMac上のCore APIへ接続します。

```bash
cd android
./gradlew testDebugUnitTest assembleDebug
```

debugビルドだけローカルHTTPを許可します。Android 17以降では初回起動時にローカルネットワーク権限を許可してください。releaseビルドではこの権限を宣言せず、HTTPS上のCloud Runへ接続します。将来Firebase Authenticationを追加しても、アプリはCore API以外のBackendサービスへ直接接続しません。
