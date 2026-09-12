# Weekend 4: Firebase Authentication

## Status

Implemented

## User Story

ユーザーとして、メールアドレスとパスワードでLifeAgentへログインし、自分のGoalだけを作成・閲覧したい。他ユーザーにGoalを閲覧・変更されたくないため。

## Acceptance Criteria

- Androidでメールアドレスとパスワードによる登録、ログイン、ログアウトができる。
- AndroidはFirebase ID TokenをCore APIのBearer Tokenとして送信する。
- Core APIはFirebase ID Tokenを検証し、`uid`を信頼できるユーザーIDとして扱う。
- `/health`を除くGoal APIは認証を必須とする。
- Goalを確定すると、ログインユーザーの`uid`が所有者として保存される。
- Goal取得は所有者で絞り込み、他ユーザーのGoalを返さない。
- 単体テストはFirebaseへ接続せず、Token VerifierをFakeへ差し替える。
- ローカル開発ではFirebase Authentication Emulatorへ接続できる。
- Firebase Emulator用設定を本番では有効にしない。

## Migration

開発中の既存Goalデータは破棄可能とし、`goals.owner_id`は最初から必須とする。Weekend 4 migration適用前にローカルDBを作り直す。

## Out of Scope

- Googleログイン、メール確認、パスワードリセット、アカウント削除
- 管理者ロールとCustom Claims
- GCPへの実デプロイ
- Cloud Run間のサービス認証

## Done

- Backendのlint・単体テスト、Androidの単体テスト・Debug buildが成功する。
- ローカルのAuth Emulator、Core API、PostgreSQLを通してユーザー分離を確認できる。
- 次の開発対象はWeekend 5のGCP開発環境。
