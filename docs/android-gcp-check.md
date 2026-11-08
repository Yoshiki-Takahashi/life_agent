# 専用アカウントでAndroidから現行GCPを確認する

Issue #50。PRのAndroidを現行GCPへ接続する検査を追加した。PR版Backendを検証したことにはならない。
Backend変更は従来のFake/Firestore Emulator検査と別途隔離した候補版で検証する。
共有の稼働APIを未マージコードへ切り替えない。mainへのマージ・配備は別途ユーザー判断で行う。

## 初回準備（管理者が明示的に実行）

この変更だけではアカウント・Secret・IAM・GitHub設定は作成されない。
本Cloud環境にはGCP identity、gcloud、Android SDK/adbがなく、GitHub environment variablesの操作も403。
以下は未適用の手順で、初回の実GCP検査はCoreの追加UID設定を反映した後に行う。

1. 管理用MacでGCP/Firebase管理権限を持つ開発者が `gcloud auth login` と
   `gcloud auth application-default login` を実行する。長期SA鍵は作らない。
2. リポジトリrootから次を実行する。架空メールは所有者了承済みで、メール回復は利用しない。

   ```sh
   uv run --project backend/services/core-api python scripts/gcp/register-check-account.py
   ```

   `lifeagent-gcp-check@example.invalid` を通常のFirebase利用者として作成し、生成したパスワードと
   UIDをJSON `{email,password,uid}` として `lifeagent-gcp-check-credentials` に登録する。
   パスワードは表示・引数・ファイルへ出さない。UIDだけ出力される。
   失敗時はアカウントだけ作成/更新済みの場合がある。権限修正後 `--rotate` で再設定する。
3. 既存本人の `CORE_API_ALLOWED_USER_ID` を維持し、GitHub `development` environmentの
   `CORE_API_ALLOWED_USER_IDS` に検証UIDを設定する。Coreの新設定を通常の承認・配備手順で反映する。
   検証アカウントにGCP/Firebase管理権限を付けない。
4. GitHubに `android-gcp-check` environmentを作る。**required reviewersを設定し、自己承認を禁止**する。
   Environmentへ入る前にPR差分（workflow、runner、依存関係を含む）を確認する運用とする。
   同一repoのPRだけjob候補となり、承認後に自動実行する。fork PRはcloud job対象外。
   required reviewersが使えないプランや、自己承認以外の別reviewerがいない場合はcloud jobを有効にせず、管理者がMacで実施する。CIの受け入れ条件は未完のまま残す。
5. 管理者が `scripts/gcp/setup-android-check.sh` を明示実行する。既存deploy WIF providerを
   mainのdeploy/manage workflow・development environment・push/手動イベントへ制限してから、
   検証専用providerとSAを用意する。PRから既存deployerを指定し直す経路を塞ぐため、
   **専用provider追加だけで済ませない**。既存setupを再実行した際も制約を確認する。
   mainのmanage-dev手動実行は維持する。main以外からの管理実行は認証を拒否する。
6. `android-gcp-check` environment variablesに以下を設定する。

   | Variable | 内容 |
   | --- | --- |
   | `GCP_PROJECT_ID` | `lifeagent-505614` |
   | `ANDROID_GCP_WIF_PROVIDER` | setupで出力したprovider |
   | `ANDROID_GCP_SERVICE_ACCOUNT` | `lifeagent-android-check@lifeagent-505614.iam.gserviceaccount.com` |
   | `ANDROID_GCP_CREDENTIALS_SECRET` | `lifeagent-gcp-check-credentials` |
   | `ANDROID_FIREBASE_API_KEY` | Firebase Android SDKの公開API設定 |
   | `ANDROID_FIREBASE_APPLICATION_ID` | Firebase Android SDKの公開application ID |

   API key/application IDは公開SDK設定で、パスワードやTokenをGitHubへ複製しない。
   最後にrepo variable `ANDROID_GCP_CHECK_ENABLED=true` を設定する。未設定のcloud jobは
   **skipped（実GCP未検証）**。独立したAndroid build/unit/lint jobは引き続き走る。

WIFはrepository ID、workflow、environment、イベント、refを検証する。`pull_request_target`は使わない。
GitHub environment承認が、同一repoでも未レビューコードへSecretを渡さないための信頼境界になる。
管理者はforkからの認証拒否、別workflow/provider指定でのdeployer認証拒否も実確認する。

検証SAには対象Secretのaccessor、Cloud Run viewer、Firestore get/list/deleteだけのcustom roleを付ける。
Secret管理、Firebase管理、DB書込み、配備権限は付けない。Firestore IAMはGoal所有者単位の制約を
表現しないため、このcleanup権限はDB内の読取り・削除が可能。専用SAをレビュー済みworkflowに限定し、
コードでもUIDと実行IDを二重に検査する。通常のGoal作成/更新はSDK認証した業務API経由だけで行う。
本人データの参照/更新禁止をFirebase検証アカウントでは維持するが、cleanup SAのIAMでは
本人データの参照/削除を禁止できていない。所有者認可付き削除API等への移行とBackend反映を未完事項とする。

## Macでの同じ検証

Android SDKとJava 21、gcloud、uvを用意する。専用AVD `LifeAgent_GCP_Check` をAPI 35で作る。
Macはホストに応じarm64-v8a、CIはx86_64を使う。普段のAVDをコピーせず、同じuserdataを使わない。
専用AVDは終了時にアプリデータを消去する。専用端末以外はrunnerが拒否する。

`android/config/auth-cloud-debug.properties` に上記の公開Firebase設定3項目を配置する（Git管理外）。
`api-release.properties` のURLは検証対象Cloud Run URLと一致させる。

```sh
cd android
./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug -Plifeagent.useCloudFirebase=true
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
cd ..
uv run --project backend/services/core-api python scripts/gcp/check-account-android.py --sha "$(git rev-parse HEAD)"
```

Macの開発者にも対象Secret accessor、Cloud Run viewer、cleanup読取り/削除権限が必要。
`--serial` で専用AVDを指定する。起動済みの通常AVDには実行しない。

検査はSDKログイン→実AI計画取得→編集・Goal保存→画面で目標超過と明示0→全0→API再取得・画面再表示を通る。
入力はstdinでapp-privateファイルへ渡し読取り直後削除する。SDK例外・instrumentation出力をログに出さず、
終了/失敗時はログアウトとアプリデータ消去を行う。APK・引数・スクリーンショット・artifact・snapshot・
キャッシュにパスワードやTokenを残さない。CIは端末画像/ログ/テストレポートをuploadしない。

検証ログはPR head SHAと実際にビルドしたcheckout SHA、接続URL、Cloud Run配信revision/割合、
実AI、run_idを記録する。通常Fake検査と区別する。Cloud Run切替中の検査は避ける。
実AI計画は1回で自動再試行なし、各HTTP/SDKは既存timeout、端末操作最大10分、job最大35分。
同一PRは直列でキャンセルしない。Mac/別PRはランダムrun_idで分離し、実AI失敗を自動再試行しない。

## 後片付け・更新・停止

Goal titleは `gcp-check-<32桁run_id>`。今回のUIDとtitle完全一致のGoalだけをsubcollection込みで削除する。
同UIDの他runや本人のデータは削除しない。失敗/通常キャンセルもfinallyで同じ削除を行う。
強制kill・runner喪失時はログのrun_idを使って管理用Macから回復する。

```sh
uv run --project backend/services/core-api python scripts/gcp/check-account-android.py --sha recovery --cleanup-run-id <run_id>
```

端末が残った場合は専用AVDのアプリを `adb shell pm clear com.yoshiki.lifeagent` で消去する。
Secret更新時はまず検査を停止し、`register-check-account.py --rotate` を実行する。
管理者がFirebaseのパスワードを再設定しSecret新versionへ更新する方式で、架空メールへのメール送信はしない。
更新後にMacとPRの検査を通し、不要な旧Secret versionを無効化する。
停止はrepo enable変数をfalse→Firebaseアカウント無効化→refresh token失効→Secret accessor/WIF binding除去。
本人UIDは維持し、検証UIDだけ追加許可から除去する。アカウント再作成でUIDが変わったら明示更新する。

## この変更で未確認の項目

アカウント作成、Secret/IAM/WIF/GitHub設定の適用、GCPの複数UID認可、Mac/CI実エミュレーター、
実Firebaseログイン・実AI・GCP保存/再表示・実cleanupは未確認。Draft PRで検査結果と区別して記載する。
これらが成功するまでIssue #50の完了/リリース完了とは扱わない。
