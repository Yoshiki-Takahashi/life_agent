# 開発からリリースまで

ユーザーとCodexが共通で使う手順です。MacまたはCodex Cloudで開発し、GitHub Actionsで共通の検査を行います。
PR検査では同じコミットのAndroidと開発版Backendを使い、GCPへ接続しません。
リリース完了は、GCPへの反映と、新しいAndroidアプリを接続した動作確認までです。
変更内容別の必須検証・実行コマンド・結果の記録方法は[検証手順](validation.md)を参照してください。

## 進め方

開発開始時に[未整理の改善メモ](https://github.com/Yoshiki-Takahashi/life_agent/issues?q=is%3Aissue%20is%3Aopen%20label%3Afeedback%20label%3Aneeds-triage)を確認します。
アプリから届くIssueは自由文のメモです。重複は既存Issueへ集約し、着手するものに
ユーザーストーリー・受け入れ条件・完了条件を補って`needs-triage`を外します。
送信だけで実装開始・優先順位確定とはしません。実装したPRから`Refs #番号`で参照し、必要なチェック・デプロイ完了後にIssueを閉じます。

Issue → 開発 → PR・自動テスト → マージ前の動作確認 → 承認・マージ → GCP反映 → リリース確認

| 段階 | 実施すること・環境 | 完了の目安 |
| --- | --- | --- |
| ① Issue確認 | Codexが目的・受け入れ条件・関連仕様を読む | 変更範囲が明確 |
| ② 開発 | MacまたはCodex Cloudで実装。Fake AIとテスト用DBで関連テストを実行し、関連文書も更新 | 変更部分のテストが成功。実行できない検証を記録 |
| ③ PR作成・更新 | Issueを紐づけ、ActionsでBackend検査と、Android単体・lint・ビルド・開発版Backendとの結合を実行 | 必要な自動検査が成功 |
| ④ マージ前の追加確認 | 自動テストでカバーしない画面・端末操作をMacで確認。AI関連変更は実OpenAIでも評価 | 変更に応じた確認が成功 |
| ⑤ レビュー・マージ | Codexが検証結果・未確認事項をPRへ記載。ユーザーの依頼・利用するスキルに従ってmainへマージ | 必要なチェックがすべて成功し、マージ権限・依頼範囲を満たす |
| ⑥ GCP反映 | マージしたコードの新しいサーバー版を準備し、基本動作の確認後に利用する版を切り替える | API・サービス間通信・DB保存が成功 |
| ⑦ リリース確認 | 新しいAndroidアプリをエミュレーターまたは実機へインストールし、GCP接続で主要操作を確認 | アプリとサーバーを組み合わせて利用できる |

## テストの使い分け

Fakeは、AIの返答を決めた内容に置き換えるものです。実AIの提案内容は別に確認します。

| タイミング | 接続先 | 主な確認 |
| --- | --- | --- |
| 開発中・BackendのPR検査 | Mac／Codex Cloud／Actions上のAPI・Firestore Emulator＋Fake | 入力検証、認証、二重登録防止、サービス間連携、保存・再送・競合 |
| AndroidのPR検査 | ActionsのAndroid Emulator → 同じコミットのCore API → Firestore Emulator＋Fake AI・Fake認証 | 作成・編集・保存・再表示、実績の0・目標超過、再送、種別別記録、完了 |
| マージ前の追加確認 | Macのエミュレーター／実機 → 同じブランチのローカルBackend。AI変更は実OpenAI | 自動テスト外の変更した操作と実AIの品質 |
| GCP反映後 | エミュレーター／実機 → GCPのCore API・Goal Planner・DB → 実OpenAI | 実際の認証・接続権限・保存・AI連携 |

- UI・APIをまたぐ変更は、同じコミットのAndroidとBackendで結合検証する。現行GCPとの接続成功・失敗をPRの合格判定に使わない。AI関連変更では「読書・アプリ開発・筋トレ」の回帰評価と提案内容を別途確認する。
- 修正後は、影響する検査・動作確認を再実行する。失敗を残したままマージしない。
- ⑥の切替前確認に失敗した場合は、旧版を維持して修正する。GCPは現行の認証制限を守り、検証データで確認する。
- GCP反映とAndroidアプリの更新は別に行う。更新中も既存アプリが使えるAPIを維持する。
- Codexは、実施した環境・Fake／実AI・結果・未確認事項・確認したコミットやサーバーの版をPR／Issueへ記録する。未確認の段階を完了扱いにしない。

## 完了の判断

実装とPRの作成は、実行できない検証の引き継ぎ先を明記して進められます。
「実装・PR作成済み」「マージ可能」「Backend反映完了」「リリース確認完了」を区別します。
必要な検査の未実施・失敗・skipを成功として扱いません。`lifeagent-issue`は通常のOpen PRを作り、
Actions成功後にマージし、必要なデプロイを確認してからIssueを閉じます。自動クローズするキーワードは使いません。
Macの手動確認など対象Issueの完了条件が未確認なら、Issueを閉じず確認内容と引き継ぎ先を残します。

## 自動検査

| 項目 | 実行内容 | 実行場所 |
| --- | --- | --- |
| BackendのPR検査 | テスト・lint・Dockerビルド・結合・競合 | `backend-quality.yml` |
| AndroidのPR検査 | 単体・lint・ビルド・同じコミットのローカルCore APIとDBを使う端末テスト | `android-quality.yml` |
| GCPへの反映 | main更新→Backendテスト→両サービスの候補版検証→切替。失敗時は元の配信先へ復旧 | `deploy-dev.yml`。端末での最終確認は別途実施 |

通常のPR検査はFakeを使います。AI品質評価は明示実行し、GCP反映時は疎通確認に必要な質問・計画生成だけ実OpenAIを使います。MacのAPIは`uv`で起動でき、Docker／Rancherは必須ではありません。
エミュレーターはMacやActions上の仮想スマホです。PRでは開発版Backendへ、配備後の手動確認ではGCPへ接続します。ビルド時に接続先を確認してください。

実行方法は[Backend](../backend/README.md)・[Android](../android/README.md)・[結合テスト](../tests/e2e/README.md)・[GCP運用](../infra/gcp/README.md)を参照します。

Android→現行GCPの検査はPR自動検査から除外しています。
配備後はMacのエミュレーターから通常の本人アカウントで[手動確認](android-gcp-check.md)します。
