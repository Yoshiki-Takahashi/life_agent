# UI刷新の検討

> 履歴資料です。現在の実装仕様は[仕様の入口](../../README.md)を参照してください。

1. [理念・型化したユースケース](01-principles-and-use-cases.md)
2. [5構想・ユースケース別評価・推奨理由](02-concept-comparison.md)
3. [採用案「対話コーチ」のUI設計書](03-conversation-coach-design.md)
4. [採用後の改訂: 共通メニューと全目標コーチ](04-navigation-and-global-coach.md)

2026-10-25、利用者がD「対話コーチ」を採用しました。
当初の推奨と採点は比較資料に残し、採用案の仕様は設計書へまとめています。
構想採用済み。2026-10-31に[UI刷新1: 対話による進捗記録](../backlog/ui-coach-1.md)の最初の記録フローを実装・検証しました。
全体の実装完了とは区別し、机上評価と実利用者テストも分けて記録します。

設計書には画面構成、遷移、記録確認、可視化仕様、計画差分、状態・エラー、
アクセシビリティ、API対応、移行、受け入れテストを定義しています。
2026-10-31の改訂で全目標共通の相談APIと常設メニュー、Goal詳細のタブを追加しました。
現在の仕様は04を優先します。会話永続化・同期は引き続き対象外です。

外部の設計基準:
- [Android: Layouts and navigation patterns](https://developer.android.com/design/ui/mobile/guides/layout-and-content/layout-and-nav-patterns): 画面階層と補助ナビゲーションの検討に利用。
- [Android: Touch target size](https://support.google.com/accessibility/android/answer/7101858): 操作対象は48dp以上を基本とする。
- [W3C: Contrast minimum](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html): 通常テキスト4.5:1、大きなテキスト3:1を配色検証の参考基準とする。Web基準の参照であり、現状のAndroid適合を宣言するものではない。
