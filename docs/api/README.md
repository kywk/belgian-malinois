# BPM API 使用指南

主要流程、成功／失敗範例與重試條件見 [操作流程](workflows.md)。
本頁補充 OpenAPI 以外的操作順序與安全邊界，依目前 Controller、DTO 與 SecurityConfig 整理。不是正式環境驗收報告，也不是所有端點的完整欄位規格。

雙入口規格見 [內勤與 agent BFF 契約 v1](../plan/contracts/staff-agent-v1/README.md)。內勤模式已實作 `/api/me/session`、SSO、組織與 AuthZ 接線，啟用與正式環境缺口見 [內勤整合指南](staff-sso-integration.md)；`/api/external/v1/**` 仍待實作。下列 ROLE_ADMIN 等規則描述既有開發模式，正式內勤模式改用 v1 分離權限。

## 入口與身分

服務端自有 API 使用 `/api/`，不直接暴露 Flowable 原生 REST。一般使用者請求使用 `Authorization: Bearer <JWT>`；可信 gateway 身分與外部系統 API key 是不同入口，不可互換。

[SecurityConfig](../../bpm-core/src/main/java/com/bpm/core/security/SecurityConfig.java) 的路徑授權先於一般 `/api/**` 規則：

- `/api/internal/**` 要求 GATEWAY 角色；`/api/admin/**` 及流程部署 POST 要求 ADMIN。
- `/api/audit-logs/**` 要求對應稽核讀取 authority。
- 表單 POST／PUT／DELETE 有專屬權限判斷，不能因 GET 可用就假設可寫入。
- 其餘 `/api/**` 要求登入，Controller／guard 仍會檢查案件與任務權限。
- `/api/external/**`、`/api/callback/**` 在 Spring Security 路徑層放行，是讓各自認證機制處理，不代表匿名業務操作已獲授權。

[OpenApiConfig](../../bpm-core/src/main/java/com/bpm/core/config/OpenApiConfig.java) 提供全域資訊，springdoc 從 mapping 產生端點。dev/test 可使用 `/v3/api-docs` 與 Swagger UI；prod 設定關閉文件端點。不要為了取得文件直接改變正式環境安全設定。

## 啟動申請的順序

1. `GET /api/process-definitions` 取得可用流程定義；`latestVersion` 預設為 false。
2. `GET /api/process-definitions/{key}/start-form` 取得申請表單與確切流程／表單版本。
3. 依回傳 DSL 與權限填寫，呼叫 `POST /api/process-instances`。

[StartProcessRequest](../../bpm-core/src/main/java/com/bpm/core/dto/StartProcessRequest.java) 以 `processDefinitionKey`、`businessKey`、`variables`、`processDefinitionId`、`formData` 承載請求。綁定申請主表的流程需送出 start-form 回傳的 `processDefinitionId`，以及 `formData.formDefinitionId`、`formData.formVersion`、`formData.dataJson`。`dataJson` 是 JSON 物件的字串，不是直接巢狀物件；不能用 formKey 代替表單定義 UUID。未綁主表的技術流程可只帶業務變數，不能自行附加未綁定表單。

身分由伺服器決定，不傳 `initiator`；`variables` 不得偽造受保護變數或 `_` 前綴內部狀態，也不得與 formData 重複定義同一值。完整檢核與回應以 [ProcessController](../../bpm-core/src/main/java/com/bpm/core/controller/ProcessController.java) 為準。

## 處理任務

| 操作 | API | 重點 |
|---|---|---|
| 查詢任務 | GET `/api/tasks` | 結果仍受目前身分與任務持有規則限制 |
| 取得任務表單 | GET `/api/tasks/{taskId}/form` | 使用流程已鎖定的表單版本 |
| 認領、完成、委派、回交、改派 | PUT `/api/tasks/{id}` | 必填 `action`：`claim`、`complete`、`delegate`、`resolve`、`reassign` |
| 加入／讀取批註 | POST／GET `/api/tasks/{id}/comments` | 必須通過相關案件／任務權限檢查 |
| 催辦 | POST `/api/tasks/urge` | 要求案件識別，含申請人判斷與冷卻限制 |

[TaskActionRequest](../../bpm-core/src/main/java/com/bpm/core/dto/TaskActionRequest.java) 的 `variables` 是 `{name,value}` 陣列，與啟動流程的 map 形狀不同。委派使用 `delegateUser`；改派需明確 `action: reassign` 與 `assignee`，單獨送 assignee 不會改派。

完成表單任務可送 `formData: {"dataJson":"{...}"}`；任務版本由伺服器解析，呼叫端不指定表單定義 ID。`returnTo: "initiator"` 僅供 complete 使用，不能與 `approved=true` 或 `rejected=true` 同時要求。它不是任意節點跳轉功能。

[TaskController](../../bpm-core/src/main/java/com/bpm/core/controller/TaskController.java) 會對不存在任務回 404、缺失／不合法 action 回 400，並在執行動作前檢查持有者。催辦另外可能回 409、429 或 503；收到錯誤先依回應與目前流程狀態處理，不盲目重送完成或啟動動作。

## 文件與契約維護

[平台規格](../bpm-platform-spec.md) 保存廣泛設計背景，但其中舊版範例允許只帶 assignee，與目前要求 action 的實作不同。呼叫端應使用本頁連結的現行 DTO／Controller，不能直接複製歷史設計範例。

Controller 已補逐 operation 的 summary、參數與錯誤 response，並以實際 Springdoc 隔離生成核對 97 個 operations。框架錯誤格式也已與實際 MVC 回應比對；業務流程仍須結合本頁與 DTO 閱讀，不能把 route 數量當作所有業務情境的驗收。

驗證入口見 [測試指南](../README-testing.md)。本輪已執行隔離 MVC 樣本與既有針對性測試；依賴使用替身，未驗證正式流程引擎、身分系統及外部 callback provider。

返回 [文件入口](../README.md)。
