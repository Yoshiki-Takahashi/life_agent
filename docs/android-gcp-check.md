# マージ・配備後のAndroid GCP手動確認

PRの自動検査は同じコミットの開発版Backendで行います。GCPへの接続確認は、
mainへのマージとBackend配備が完了した後、Macのエミュレーターで手動実施します。
通常のFirebaseログインと既存の本人アカウントを使います。検証専用アカウント、
パスワード用Secret、追加UID許可、検証用WIF、GCP用のAndroid自動テストは不要です。

## 準備と起動

1. 対象コミットの`deploy-dev.yml`が成功したことをActionsで確認し、Macのコードをmainへ更新します。
2. Android SDK・Java・起動済みエミュレーターを用意します。BackendをMacで起動する必要はありません。
3. Git管理外の`android/config/auth-cloud-debug.properties`へ、実Firebaseの公開SDK設定を記載します。
   設定するキーは`FIREBASE_PROJECT_ID`、`FIREBASE_API_KEY`、`FIREBASE_APPLICATION_ID`です。
   Googleログインを使う場合は`GOOGLE_WEB_CLIENT_ID`も設定します。
   通常debug用のローカルAuth設定を上書きせず、パスワード・サービスアカウント鍵は入れません。
4. `android/config/api-release.properties`のURLが、配備済みCore APIのURLであることを確認します。
5. 次を実行し、エミュレーターからアプリを開きます。

```sh
cd android
./gradlew installDebug -Plifeagent.useCloudFirebase=true
```

このオプションでAPIとログイン先をGCP／実Firebaseへ切り替えます。
ローカル用のログイン状態が残る場合はログアウトし、通常の本人アカウントでログインし直します。
通常のローカル開発へ戻すときは、`./gradlew installDebug -Plifeagent.useCloudFirebase=false`で入れ直します。
GCP接続の手動確認のために、gcloudへのログインやGCP管理権限を追加する必要はありません。

## 確認すること

- 既存の許可された本人アカウントでログインでき、自分の目標が表示される。
- 実験用と分かるタイトルの目標を作り、AIの提案を編集して保存・再表示できる。
- 今回変更した画面・操作が使える。進捗の変更なら、0や目標超過の保存と再表示も確認する。
- 必要に応じてログアウト・再ログイン後も保存した結果を確認する。

既存の目標・実績を実験用に書き換えません。個人データ・実AIの利用と通常の利用料が発生するため、
操作は必要な範囲に絞ります。確認したアプリのコミット、Actionsの配備結果、操作と結果をPRへ記録します。
手動確認を実施するまでは「Backend反映完了」と「リリース確認完了」を区別します。

## 旧PR検証の整理

PR #53の専用アカウント・Secret・WIFを使うAndroid GCP検証は撤去しました。
GitHubで旧Android GCP Checkを必須にしていた場合は、Android Qualityのチェックへ更新します。
既に作成済みのFirebaseアカウント・Secret・IAM・GitHub設定の有無は別途確認し、
不要なものの停止・削除は管理作業として行います。このコード変更では外部リソースを操作しません。
