# APIの約束

業務APIは`Authorization: Bearer <Firebase ID Token>`を要求します。
メール認証とGoogle認証で同じFirebaseのuidを所有者として扱います。Google ID TokenはAndroidでFirebaseへ交換し、業務APIにはFirebase ID Tokenだけを送ります。
個人利用のクラウド環境では、検証済みTokenのuidが`ALLOWED_USER_ID`と一致する利用者だけを許可します。
他のuidは、DB操作・AI呼出し前に403で拒否します。クライアントが送るメールアドレスやuidでは判定しません。
現在の正確なフィールドと型はCore APIの`/openapi.json`、内部通信の型は各サービスのPydanticモデルが正本です。
この文書では利用者に影響する規則を説明します。生成されたSchemaの写しは手編集しません。

## 入口

| 操作 | Core API | 保存 |
| --- | --- | --- |
| 計画案を作る | POST /api/v1/goals/preview | なし |
| 計画を確定する | POST /api/v1/goals/confirm | 目標と計画 |
| 一覧を読む | GET /api/v1/goals | なし |
| 詳細を読む | GET /api/v1/goals/{id} | なし |
| 今回値を解析する | POST /api/v1/goals/{id}/progress/preview | なし |
| 実績を保存する | POST /api/v1/goals/{id}/progress | 増分と記録 |
| 助言を取得する | POST /api/v1/goals/{id}/advice | なし |
| 再計画案を作る | POST /api/v1/goals/{id}/replan/preview | 未適用の候補 |
| 再計画を適用する | POST /api/v1/goals/{id}/replan/apply | 承認した変更 |
| 全目標を相談する | POST /api/v1/coach/messages | なし |

`GET /health`は認証不要の稼働確認です。
メンテナンス中は`/api/`以下に503と`Retry-After: 60`を返します。保存結果が不明な実績は、再開後も同じ保存IDで再送します。
内部AI APIは`/internal/v1/goal-plans`、`progress-preview`、`advice`、`replan`、`coach`です。
Androidから直接呼びません。

## 計画

タイトルは1〜120文字、説明は任意で2,000文字以内、期限は今日以降です。
計画は測定項目1〜3個、中間目標3〜5個です。中間目標の期限は今日からGoal期限までで、表示順に並びます。
数値目標は正数、小数2桁まで、最大10億です。
再計画の見直し理由は1〜2,000文字。現在値を下回る目標値へは変更できません。
候補の適用には提案IDを使い、適用済み・古い計画版の候補は409で拒否します。

## 実績

本文は1〜2,000文字。`metric_updates`は既存Metricへの正の増分を1〜3件指定します。
同じMetricの重複、非表示・他目標のMetric、目標値の超過を拒否します。
増分は小数2桁まで、最大10億。ゼロや文章だけの実績は保存しません。
解析候補は0件でも返せます。その場合は理由を示し、修正・手入力を選べます。

`client_request_id`は1〜64文字。同じ保存操作の再試行では同じIDと内容を送ります。
同じIDによる保存は一回だけ反映します。新しい実績には新しいIDを使います。
助言の再取得は保存APIとは別です。
Firestoreへの変更後も公開JSON・UUID・順序・全履歴返却を維持します。内部の数値は整数で保存し、APIでは従来どおり小数として扱います。

## 全目標コーチ

入力は`body`（1〜2,000文字）と`history`（最大12発話）。発話はuserまたはassistantで、各本文は1〜2,000文字です。
返答は`reply`、`intent`（discuss / record）、`goal_id`、`record_body`です。
相談では対象と実績本文をnullとし、記録候補では所有Goal IDと利用者の現在・過去発話の原文を必須とします。
AIが生成した実績文や、不明なGoal IDは採用しません。このAPIだけでは実績を保存しません。

## 失敗

入力不正は422、未認証は401、利用を許可していないアカウントは403、他人・存在しないGoalは404、再計画の競合は409です。
AIの障害・不正出力は503。保存失敗は500で返し、途中までの加算だけを確定しません。
コーチで保存結果が不明な場合は内容を固定して再送し、助言障害では助言だけを再試行します。
