# Android App

Kotlin + Jetpack ComposeによるLifeAgentクライアントを配置します。

## Local run

Backendをポート8000で起動してから、Android StudioでdebugアプリをEmulatorへ実行します。Emulatorは`config/api-debug.properties`に定義したURLでMac上のCore APIへ接続します。

Debug buildは`10.0.2.2:9099`のFirebase Authentication Emulatorにも接続します。

実Firebase AuthenticationとCloud Run上のCore APIをAndroid Emulatorから確認する場合は、
Git管理外の`config/auth-cloud-debug.properties`を用意し、次の明示オプションでDebug buildを
インストールします。この場合だけAPI接続先も`config/api-release.properties`へ切り替わります。

```bash
./gradlew installDebug -Plifeagent.useCloudFirebase=true
```

このオプションを省略した通常のDebug buildは、引き続きAuthentication EmulatorとMac上の
Core APIへ接続します。
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

`api-release.properties`には発行済みCloud Run HTTPS URLを設定します。Retrofitの要件により、URL末尾の`/`は必須です。APIのURLは公開情報であり、APIキーなどの秘密情報はこのファイルへ記載しません。

debugビルドだけローカルHTTPを許可します。Android 17以降では初回起動時にローカルネットワーク権限を許可してください。releaseビルドではこの権限を宣言しません。認証後も、アプリはCore API以外のBackendサービスへ直接接続しません。

現在の動作は[画面と操作](../docs/ui.md)を参照してください。

専用PostgreSQLとFake APIを通すEmulator検証:
~~~bash
ANDROID_HOME="$HOME/Library/Android/sdk" W7_ANDROID_TEST_CLASS=com.yoshiki.lifeagent.AppFlowTest ./scripts/test-weekend-7.sh --android
~~~
上記はリポジトリルートで実行します。Dockerと起動済みEmulatorが必要です。
終了時に専用DBとAPIを停止し、テストデータを削除します。

### ログアウト後に認証できない場合（通常のDebug版）
通常のDebug版はクラウド認証ではなく、Mac上のAuthentication Emulator（9099）に接続します。
ローカルサービスが停止している場合、正しい入力でもログインできません。
リポジトリルートでscripts/start-auth-emulator.shを起動してください。
アカウントは.secrets/firebase-auth-emulator/へ保存・復元します（Git管理外）。
初回導入前の未保存アカウントや、クラウド上のアカウントが自動的に移るわけではありません。

2026-10-31のUI確認では、既存の古いローカルDBを保持するため、別DB lifeagent_ui を作成しました。
API再起動時はDATABASE_URLのDB名にlifeagent_uiを指定し、
AUTH_BACKEND=firebase、FIREBASE_PROJECT_ID=life-agent-local、
FIREBASE_AUTH_EMULATOR_HOST=127.0.0.1:9099、
各AI backend=fakeで起動します。クラウドの起動は不要です。

## 音声入力

コーチまたは目標詳細の「記録」から音声入力を開きます。説明の確認・マイク権限の許可後に録音し、
文字起こしを編集して既存本文へ追加します。送信・解析・保存は既存の操作で行います。
Android標準SpeechRecognizerを使い、API 31以降で利用可能なら端末内認識を選びます。
日本語モデルが利用できない場合はエラーを表示します。「既定の音声認識に切り替える」で送信先の説明を確認し、改めて録音を開始できます。外部認識へ自動切替はしません。
その他の端末では既定の認識サービスを使い、提供元への音声送信が発生する場合があります。
LifeAgentは音声を保存せず、Core APIやOpenAIへ音声を送りません。サービス提供元の保持・削除はその設定・方針に従います。
認識サービスがないEmulatorでも手入力は利用できます。

Issue: [Weekend 10 / #19](https://github.com/Yoshiki-Takahashi/life_agent/issues/19)。

実装上の制約は[AndroidのSpeechRecognizer仕様](https://developer.android.com/reference/android/speech/SpeechRecognizer)に従います。
マイク音声を使わず、認識サービスの結果を差し替える操作テストは、APKを上書きインストールして実行します。
`connectedDebugAndroidTest`は終了時にアプリを削除する場合があるため、既存アプリデータを持つEmulatorでは次の手順を使います。

```bash
./gradlew assembleDebug assembleDebugAndroidTest
"$ANDROID_HOME/platform-tools/adb" install -r app/build/outputs/apk/debug/app-debug.apk
"$ANDROID_HOME/platform-tools/adb" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
"$ANDROID_HOME/platform-tools/adb" shell am instrument -w -e class com.yoshiki.lifeagent.SpeechInputTest com.yoshiki.lifeagent.test/androidx.test.runner.AndroidJUnitRunner
```

このテストとAppFlowTestはマイク権限をテスト用に付与します。
権限拒否→手入力→再試行で許可するOS境界は、マイク権限が未付与のEmulatorで次を実行します。
すでに付与済みの場合はskipし、テスト自身では権限を取り消しません。

```bash
"$ANDROID_HOME/platform-tools/adb" shell am instrument -w -e class com.yoshiki.lifeagent.SpeechPermissionTest com.yoshiki.lifeagent.test/androidx.test.runner.AndroidJUnitRunner
```

## Googleログイン（Issue #20）

Googleでログインする処理はCredential Manager、認証と連携はFirebase Authenticationを使います。
既存のメールユーザーは先にメールでログインし、目標一覧の「Googleを連携」から連携してください。
この経路はuidと既存パスワードを維持します。別アカウントに属するGoogle資格情報の連携は拒否し、DBの所有者を書き換えません。
Googleで直接ログインした際の同一メールの扱いはFirebaseに従います。
未確認のメール・パスワードへ信頼済みGoogleアカウントで直接ログインすると、未確認のパスワードが削除される場合があります。
メールアドレスだけでの独自統合、パスワードの保存・復元、認証方式の事前照会は行いません。
[Firebaseのアカウント連携](https://firebase.google.com/docs/auth/android/account-linking)と
[信頼済みプロバイダの規則](https://firebase.google.com/docs/auth/users/#verified_email_addresses)を参照してください。

実Firebaseに必要な設定:

1. Firebase AuthenticationでGoogleを有効にし、同一メールに複数のアカウントを作らない設定を維持する。
2. Androidアプリ`com.yoshiki.lifeagent`に実際のdebug／release署名のSHA-1・SHA-256を登録する。`./gradlew signingReport`で確認する。
3. 同じプロジェクトのWeb OAuthクライアントID（AndroidクライアントIDではない）を、利用する`config/auth-*.properties`へ追加する。
4. クラウド検証ではGit管理外の`config/auth-cloud-debug.properties`へ実Firebaseの公開設定と以下の項目を設定し、`./gradlew installDebug -Plifeagent.useCloudFirebase=true`を使う。

```properties
GOOGLE_WEB_CLIENT_ID=your-web-client-id.apps.googleusercontent.com
```

WebクライアントIDは公開設定です。OAuthクライアントSecret、サービスアカウント鍵、ID TokenをAndroid・Git・ログへ入れません。
ID未指定ではGoogleのボタンを無効化し、メール認証は利用できます。ローカル設定と実Firebase設定を混ぜません。
Google IDライブラリは既存Kotlin 2.2系に対応し、Firebaseガイドでも使用されている1.1.1を使用します。
GoogleのG画像は[公式ブランド素材](https://developers.google.com/identity/branding-guidelines)から取得しています。

実Firebaseでの受け入れは、Googleログイン、取消・失敗後の再試行、既存メールユーザーからの明示連携、
連携後のGoogle／パスワード両方での再ログインと既存Goal表示、別利用者の目標分離、ログアウトを確認します。
OAuth設定と端末アカウントが必要なため、通常テストでは個人のGoogleアカウントを使用しません。

Firebase SDKと認証エミュレーターを通す検証はリポジトリルートから実行します。

```bash
./scripts/start-auth-emulator.sh
# 別ターミナル（Dockerと起動済みAndroid Emulatorが必要）
ANDROID_HOME="$HOME/Library/Android/sdk" ./scripts/test-google-auth.sh
```

Googleのアカウント選択だけを差し替え、署名なしの合成Google Tokenを認証エミュレーターへ渡します。
専用PostgreSQL・Core APIで実際のFirebase ID Tokenを検証し、既存Goalへのアクセスと他利用者の404を確認します。
既存の開発DB・デフォルトFirebaseセッションは使いません。テスト用の名前付きFirebase Appとアカウントだけを使用し、作成ユーザーと専用DBを終了時に削除します。
実Googleの署名検証・OAuthアカウント選択を検証した扱いにはしません。
