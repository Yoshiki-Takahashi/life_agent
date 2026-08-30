# Coding Standards

## 優先順位

コードは、頑健性や将来の汎用性よりも、現在の要件に対する簡潔さと理解しやすさを優先します。ただし、入力検証、秘密情報、データ破損につながる処理は省略しません。

## 共通ルール

- 名前から役割が分かる、小さな関数と型を使う。
- 一度しか使わない抽象化や、将来を予測した拡張ポイントを作らない。
- 同じ業務ルールをUI、API、DBへ無秩序に重複させない。
- エラーはユーザーが次の操作を選べる形で表示する。
- コメントは処理内容ではなく、コードだけでは分からない理由を書く。
- 変更と同じ単位でテスト、README、契約文書を更新する。

## Backend

- Pythonの実行・依存管理にはuvだけを使う。
- FastAPIのrequest/responseはPydantic Schemaで明示する。
- DB操作は短いSession境界に閉じ、Migrationを必ず用意する。
- API Layer、Model、Schemaを基本構成とし、必要になるまでRepositoryやService層を増やさない。
- Ruffとpytestを完了条件にする。

## Android

- Compose UIは表示とイベント通知に集中させる。
- 非同期処理と画面状態はViewModelとStateFlowで管理する。
- HTTP処理をComposableやViewModelへ直接書かず、Repositoryを境界にする。
- 画面状態は一つの`UiState`から読み取り、相反する状態を増やさない。
- Gradle Wrapperによるテストとビルドを完了条件にする。
