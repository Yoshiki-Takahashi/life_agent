# LifeAgentの仕様

現在のコードで利用できる機能を説明します。読み始める順番は次のとおりです。

| 文書 | 分かること |
| --- | --- |
| [サービスの仕様](product-overview.md) | 目的、できること、できないこと |
| [画面と操作](ui.md) | 画面の移動、対話、確認と保存 |
| [データとシステム](system-architecture.md) | 保存する情報、サービスの役割、認証 |
| [APIの約束](api.md) | 通信の入口、入力・保存・競合の規則 |
| [AIの動作](progress-ai-quality.md) | 生成範囲、送信する情報、不確実な結果の扱い |

開発を始めるときは、[開発からリリースまで](development-workflow.md)で全体の流れと検証環境を確認してください。
個別の実行手順は[Backend](../backend/README.md)、[Android](../android/README.md)、
[インフラ](development-infrastructure.md)、[開発ガイド](development-guide.md)にあります。
これらは利用仕様とは分けて管理します。
[設計原則](service-design-guidelines.md)と[ADR](decisions/README.md)は開発者向けの判断記録です。
旧構想やSprint別の経緯は[履歴](archive/README.md)にあります。

今後の開発は[GitHub Issues](https://github.com/Yoshiki-Takahashi/life_agent/issues)で管理します。
保存先の変更計画は[Firestore Standard移管計画書](plans/firestore-standard-migration.md)を参照してください。
対応する[Issue #23](https://github.com/Yoshiki-Takahashi/life_agent/issues/23)を進捗の正本とし、移管前の現行仕様とは区別します。
Weekend 10は[音声入力 #19](https://github.com/Yoshiki-Takahashi/life_agent/issues/19)、Weekend 11は[Google認証 #20](https://github.com/Yoshiki-Takahashi/life_agent/issues/20)です。
