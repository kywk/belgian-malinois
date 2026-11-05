# 2026-11-05 內勤 SSO、組織與 AuthZ 整合紀錄

基底 `d1900de`。本輪依 [v1 契約](../plan/contracts/staff-agent-v1/README.md) 實作 BPM 端的內勤垂直流程。本紀錄涵蓋實作與隔離測試；未切換正式環境。Git 提交與推送狀態以 repository 紀錄為準。

## 已接線的行為

- `staff` profile：受控 RS256 issuer / audience / kid / PEM 公鑰，必要 sub / iat / exp / nbf 驗證；不要求 SAML 提供 OIDC discovery，不接受開發 HMAC secret。
- SSO 一次性交換碼：同源 callback、頁籤 state、清除網址憑證且保留 router history metadata；正式前端只顯示內勤 SSO，token 存 sessionStorage。
- `/api/me/session` 回權威 canonical USERID 與當次權限；STAFF ACTIVE 才能登入。JWT roles、office_code 與 caller query 不參與正式資格判定。
- 正式身分與 AuthZ HTTP adapters；每請求重新查詢，未使用舊 Redis mock／grant cache。AuthZ 使用 subject_ids、system_code=bpm、空 subject_attributes、字串 code=0000。
- 待辦使用權威部門／組織／grant 群組；簽核先保留 holder guard，再新查 task act 與流程 approval grant。加簽、轉發及改派同樣受控，目標須為有效內勤。
- 功能權限分離：console、task read / act、form design、config manage、audit、deploy。CI 保留獨立 gateway＋AuthZ 機器主體，不取得內勤 console 身分。
- 前端拒絕來源中斷時從 JWT roles 降級；以伺服器 USERID 顯示身分，處理換人登入的舊回應競爭。沒有 task act 時僅顯示唯讀提示。
- 修正水平選單自動折疊造成登出項目不可見；保留橫向捲動。

## 測試與修正紀錄

最終驗證：2026-11-05 20:27（Asia/Taipei）。JDK 21.0.9、Node 24.21.0、Docker / OrbStack，全部使用隔離測試資料。

| 驗證 | 最終結果 | 重跑入口／紀錄 |
| --- | --- | --- |
| 完整後端 | **1,699 通過，0 失敗／錯誤／略過**；4 分 8 秒 | `mvn -B -f bpm-core/pom.xml test`；`/tmp/bpm-staff-full-backend-final.log` |
| 新內勤後端 | 12 組 HTTP／Flowable 整合＋2 組 JWT／啟動設定測試通過，已包含在 1,699 項 | `StaffIntegrationTest`、`StaffJwtDecoderTest` |
| 完整前端 | **279 通過，27 檔** | `npm --prefix bpm-frontend test`；`/tmp/bpm-staff-frontend-final.log` |
| 正式模式前端建置 | **PASS**；既有 bundle >500 kB 提示不影響建置 | `VITE_STAFF_SSO=true npm --prefix bpm-frontend run build`；`/tmp/bpm-staff-build-final.log` |
| 瀏覽器 E2E | **既有 6 組＋內勤 1 組，合計 7 組通過**；Failsafe 14 項全過（含 12 組重跑的內勤整合測試） | `bash scripts/browser-e2e.sh --prepared`；`/tmp/bpm-staff-browser-final.log` |
| v1 文件／schema | **PASS**：26 schemas、47 正例＋32 反例、8 permissions、6 roles、25 errors、16 links、8 inline JSON | `node scripts/validate-integration-contract.cjs` |
| 變更格式 | `git diff --check` 通過 | 提交前格式檢查 |

上述 `/tmp` log 為本機執行紀錄；可重現入口與結果保存在本文件。正式 SAML、權威身分與 AuthZ 環境尚未連線驗收。

- 既有後端第一次完整回歸為 1,699 項、1 失敗；失敗是 `StartProcessWithFormDataTest` 把其他請求的背景稽核算入全表筆數，案件與表單零副作用斷言均通過。使用注入無關 DLQ 稽核建立可重現案例，再改為依本次 businessKey 查核，並保留成功送件的稽核正控。
- SSO 瀏覽器測試使用暫時 RSA 金鑰、本機 SAML／身分／AuthZ 合約 provider，以及隔離 MSSQL、RabbitMQ、Redis、MailHog。測試資料為合成 STAFF reviewer，沒有呼叫正式來源。
- 瀏覽器涵蓋 SSO 轉址／code 交換、canonical 身分、待辦點選、實際 Flowable 簽核完成；另一件在畫面開啟後撤權，PUT 回 403，DB 任務仍存在。
- 新舊服務測試原先競用基底固定 port，改用正式模式測試專屬隨機 port。合成表單經 validator 檢查並鎖版，不放寬生產的表單驗證／版本檢查。
- v1 schema validator 只驗證文件與範例；43 項完整雙入口行為案例包含未實作的 agent BFF，不宣稱全部已執行。

## 正式上線前仍需提供

完整環境變數、HTTP 欄位與設定方式見 [內勤整合指南](../api/staff-sso-integration.md)。主要阻塞如下：

1. **權威身分橋接服務尚未存在**：需提供 issuer/sub → USERID、STAFF、ACTIVE 與授權組織證據。既有 ODS 沒有在職狀態，不能補成固定 ACTIVE；這不是只缺一個 URL。
2. 正式 SAML issuer / audience / kid / 公鑰、callback allowlist、handoff origins 與金鑰輪替程序。這版使用單一受控 key，未做多 key 重疊輪替或 IdP 全域登出／token 撤銷事件。
3. AuthZ 網路／服務認證、bpm catalog、真實個人／組織 grants，以及每個正式 processDefinitionKey 的 approval permission 對照。
4. 主管鏈、代理、部門成員、搜尋、依權限反查人員仍缺正式來源契約；相關 adapter 明確拒絕，不退回 mock。依賴這些 EL 的流程不可宣稱已可正式上線。
5. CI gateway 來源白名單、服務 ID、AuthZ subject 與 deploy grant。

外勤 agent BFF、external v1 案件鎖版／防重／對帳仍屬下一階段；本輪不改變現有外部 API 契約。
