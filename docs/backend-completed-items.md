# Greyhound BPM 平台 — 已完成工項清單

> 產出日期：2026-06-08
> 最後更新：2026-09-29（新增第八節：`feature/tech-debt-remediation` 分支，66 個 commit）
> 基於程式碼實際狀態與歷史紀錄分析
>
> 第一～七節保留 2026-06-08 當時的紀錄；其後有變動的項目以「⤷」加註，不改寫原文。

---

## 一、Phase 1 — 微服務基礎建設（已完成）

| # | 工項 | 服務 | 估計人天 |
|---|------|------|---------|
| 1 | 專案骨架建立（3 個 Spring Boot module + Vue 3 + Vite）⤷ 現為單一模組 bpm-core（第八節 Stage 1、3） | All | 0.5d |
| 2 | Docker Compose 開發環境（MSSQL×3 + RabbitMQ + Redis + Nginx） | Infra | 1d |
| 3 | Nginx 配置（路由 + JWT 轉發） | Infra | 0.5d |
| 4 | RabbitMQ exchange / queue / binding 配置 | Infra | 0.5d |
| 5 | Flowable + MSSQL + JPA 環境建置 | BPM Core | 1d |
| 6 | Flowable REST API 封裝（/api/ Controller） | BPM Core | 1.5d |
| 7 | OrgService REST Client + Mock 實作 | BPM Core | 1d |
| 8 | BpmPermissionService REST Client + Mock 實作 | BPM Core | 1d |
| 9 | 快取失效 webhook 端點（CacheInvalidateController） | BPM Core | 0.5d |
| 10 | BpmQueryService 組合查詢骨架 | BPM Core | 0.5d |
| 11 | AuditLog Entity + hash chain 實作 | Audit Log | 1d |
| 12 | MQ Consumer + append-only 寫入 | Audit Log | 1d |
| 13 | 稽核查詢 API（AuditLogController） | Audit Log | 1d |
| 14 | AuditEventPublisher 共用元件 | BPM Core / Form | 0.5d |
| 15 | 前端 AuditLog.vue 查詢頁面 | Frontend | 1d |
| 16 | 整合測試（服務啟動 + 健康檢查 + 路由） | All | 1.5d |

**Phase 1 小計：13d**

---

## 二、Phase 2 — 核心待辦 + 基礎表單（已完成）

| # | 工項 | 服務 | 估計人天 |
|---|------|------|---------|
| 17 | Form Service 微服務建置（FormDefinition + FormData Entity + CRUD API）⤷ 2026-09-28 併入 bpm-core | Form Service | 2d |
| 18 | 手動建立請假/採購表單 Schema（含 review mode） | Form Service | 0.5d |
| 19 | BPM Core 待辦清單 API（assignee/candidateUser/candidateGroups） | BPM Core | 1.5d |
| 20 | BPM Core 我發起的流程 API（進行中 + 已結束） | BPM Core | 1d |
| 21 | 審批操作 API（同意/退件/拒絕/轉發 + AuditEvent 發送） | BPM Core | 2d |
| 22 | 批註 API（新增/查詢/歷史批註） | BPM Core | 1d |
| 23 | Email 通知基礎版（TaskListener + 硬編碼模板 + MQ 非同步） | BPM Core | 1.5d |
| 24 | 請假/採購審核流程 BPMN 設計（含退回/拒絕分支） | BPM Core | 1d |
| 25 | DynamicForm.vue（依 Schema 渲染 + 流程變數填入 + mode 控制） | Frontend | 3d |
| 26 | Dashboard.vue + TaskInbox.vue（統計卡片 + 待辦清單表格） | Frontend | 2d |
| 27 | MyApplications.vue（進行中/已完成/已拒絕 Tab） | Frontend | 1.5d |
| 28 | DocumentDetail.vue（表單 + 審核操作） | Frontend | 2d |
| 29 | ActionDialog.vue（同意/退件/拒絕/轉發 Dialog） | Frontend | 1.5d |
| 30 | CommentPanel.vue + ApprovalTimeline.vue | Frontend | 1.5d |
| 31 | StartProcess.vue（選擇流程 + 填寫表單 + 啟動） | Frontend | 2d |
| 32 | API Service 層（flowableApi.js / formApi.js / orgApi.js / auditLogApi.js） | Frontend | 1d |

**Phase 2 小計：25.5d**

---

## 三、Phase 3 — 表單設計器 + 公文系統（已完成）

| # | 工項 | 服務 | 估計人天 |
|---|------|------|---------|
| 33 | Form Editor 三欄佈局骨架（FormEditor.vue） | Frontend | 1d |
| 34 | FieldPalette.vue — 13 種元件拖拉來源 | Frontend | 1.5d |
| 35 | FormCanvas.vue — 拖放目標區域 + 排序 | Frontend | 2d |
| 36 | FieldConfig.vue — 元件屬性設定面板 | Frontend | 2d |
| 37 | FormPreview.vue — 即時預覽 | Frontend | 0.5d |
| 38 | useFormEditor.js composable | Frontend | 1d |
| 39 | 公文系統 Entity + API（DocumentRequest + 文號管理） | BPM Core | 2d |
| 40 | DocumentController（建立公文 + 啟動流程） | BPM Core | 1d |
| 41 | 附件上傳功能（FileAttachment Entity + API） | BPM Core | 2d |
| 42 | 通知模板管理（NotifyTemplate + NotifyConfig Entity） | BPM Core | 1.5d |
| 43 | 通知模板管理 API（CRUD /api/admin/notify-*） | BPM Core | 1d |
| 44 | 前端加簽 UI（CountersignDialog.vue + ActionDialog 擴充） | Frontend | 1.5d |
| 45 | ExternalFormLink.vue（外部表單連結元件） | Frontend | 0.5d |
| 46 | OrgSelector.vue（組織人員選擇器） | Frontend | 1d |

**Phase 3 小計：19d**

---

## 四、Phase 4 — 流程設計平台（已完成）

| # | 工項 | 服務 | 估計人天 |
|---|------|------|---------|
| 47 | bpmn-js Editor 整合（BpmnEditor.vue + Modeler 初始化） | Frontend | 2d |
| 48 | useBpmnModeler.js composable（modeler 生命週期封裝） | Frontend | 1d |
| 49 | flowableModdle.js（Flowable 擴充屬性定義） | Frontend | 0.5d |
| 50 | FlowablePropertiesProvider.js（自訂 Properties Panel） | Frontend | 1d |
| 51 | AssigneeProps.js — 審核對象類型選擇器（6 種類型 + EL 自動產生） | Frontend | 3d |
| 52 | FormProps.js — formKey 綁定選擇器（內建表單/外部連結） | Frontend | 1.5d |
| 53 | WebhookProps.js — 節點級 Webhook 設定 UI | Frontend | 2d |
| 54 | ProcessDiagram.vue（流程圖顯示 + 進度標示） | Frontend | 1.5d |
| 55 | BPMN Lint 前端即時驗證（UserTask 必填檢查、節點命名） | Frontend | 1d |
| 56 | BPMN Lint 後端驗證（BpmnLintService + BpmnLintController） | BPM Core | 2d |
| 57 | Webhook 後端（WebhookTaskListener + WebhookConsumer + ProcessCompletedListener） | BPM Core | 3d |
| 58 | CI/CD Pipeline 配置（GitLab CI + GitHub Actions） | Infra | 2d |
| 59 | Docker Compose 生產環境（docker-compose.prod.yml） | Infra | 0.5d |

**Phase 4 小計：21.5d**

---

## 五、Phase 5 — 外部系統接入（已完成）

| # | 工項 | 服務 | 估計人天 |
|---|------|------|---------|
| 60 | ExternalSystem Entity + 管理 API（CRUD + rotate-key） | BPM Core | 2d |
| 61 | ExternalApiAuthFilter（API Key 認證 + 權限檢查） | BPM Core | 2d |
| 62 | ApiKeyUtil（SHA-256 hash + 驗證） | BPM Core | 0.5d |
| 63 | ProcessVariableSpec Entity + Admin API | BPM Core | 1.5d |
| 64 | 外部系統發起流程 API（ExternalApiController） | BPM Core | 2d |
| 65 | 外部系統查詢流程狀態 API | BPM Core | 1d |
| 66 | 節點 API 觸發（自動化審批）| BPM Core | 1d |
| 67 | 前端 ExternalSystemAdmin.vue | Frontend | 2d |
| 68 | 前端 ProcessVariableSpecAdmin.vue | Frontend | 1d |
| 69 | externalApi.js service 層 | Frontend | 0.5d |
| 70 | ProcessList.vue（流程定義管理列表） | Frontend | 1d |
| 71 | FormList.vue（表單管理列表） | Frontend | 1d |

**Phase 5 小計：16d**

---

## 六、架構重構（2026-04-24，已完成）

| # | 工項 | 服務 | 估計人天 |
|---|------|------|---------|
| 72 | audit-log-service 合併入 bpm-core（多 DataSource 配置） | BPM Core | 1.5d |
| 73 | AuditDataSourceConfig + PrimaryDataSourceConfig | BPM Core | 0.5d |
| 74 | AuditEventPublisher 改為 @Async 直接呼叫 ⤷ 2026-09-29 改為同步 fail-closed（4aa45ab） | BPM Core | 0.5d |
| 75 | 表單版本鎖定（FormVersionLocker） | BPM Core | 1.5d |
| 76 | TaskController 附加 formVersion 到 response | BPM Core | 0.5d |
| 77 | DynamicForm.vue 新增 formVersion prop | Frontend | 0.5d |
| 78 | DocumentDetail.vue 傳遞 formVersion | Frontend | 0.5d |
| 79 | Form Service 三態生命週期（draft/published/archived） | Form Service | 1d |
| 80 | archive/delete API + 保護邏輯 | Form Service | 0.5d |
| 81 | docker-compose / nginx / CI 移除 audit-log-service | Infra | 0.5d |

**架構重構小計：7.5d**

---

## 七、驗收測試環境（2026-04-19，已完成）

| # | 工項 | 服務 | 估計人天 |
|---|------|------|---------|
| 82 | MockOrgController 強化（含完整測試用戶） | BPM Core | 0.5d |
| 83 | MockPermController 強化（依 permCode 回傳正確清單） | BPM Core | 0.5d |
| 84 | docker-compose.dev.yml（含 MailHog） | Infra | 0.5d |
| 85 | seed-data.sh（部署 BPMN + 建立表單 mock data） | Infra | 1d |
| 86 | acceptance-test.sh（完整簽核流程自動化測試） | Infra | 2d |
| 87 | README-testing.md（驗收測試操作說明） | Docs | 0.5d |

**驗收測試小計：5d**

## 八、技術債修復與安全強化（2026-09-28 ～ 09-29，已完成）

分支 `feature/tech-debt-remediation`（66 個 commit）。編號 P0／P1／P2 對應
`docs/plan/2026-09-28-security-audit.md`，R 編號對應 `docs/plan/2026-09-28-remediation-backlog.md`。
這些是修復與強化，多數不在原 65 項待辦內，因此只列 commit、不回推人天。

| # | 工項 | 範圍 | commit |
|---|------|------|--------|
| 88 | Stage 1：移除已無用的 audit-log-service 模組、Boot 3.5.13 → 3.5.16、MSSQL SA 密碼改讀環境變數 | Infra | 0d667da、c1bd733、f75fded |
| 89 | Stage 2：Flyway 接手三個 DB 的 schema、關閉 ddl-auto | BPM Core | 7eb9a04、8337fcc |
| 90 | Stage 2：Testcontainers 整合測試骨架（真實 MSSQL／RabbitMQ／Redis）、多 DataSource 綁定守衛 | Test | cb32431、d881f2f、4ca666d |
| 91 | 稽核交易管理器綁錯 EntityManagerFactory（稽核一筆都寫不進去） | BPM Core | cecdbe4 |
| 92 | 全 schema 56 個文字欄位 VARCHAR → NVARCHAR（中文被靜默換成問號） | DB | 1cc1a4c、0dd8c46 |
| 93 | P0 ×6：路徑穿越與容器非 root、BPMN EL 範圍（`setBeans`）、稽核 hash chain v2、mass assignment、任務變數保護名單、加簽保護 | Security | e55369a、d90f994、9788be0、9fb1700、9f4ebed、5d1bf22 |
| 94 | P1 ×15：簽核稽核操作者、併發任務 500、三個 fail-open、逾時與快取容錯、公文撞號、待辦查詢、附件授權、通知模板、DLQ 與投遞幂等、表單改版、表單鎖定錯誤不再被吞 | BPM Core | 6979b95、32db1cc、64aef4c、f9b0cc8、f78e218、57abfea、66e4267、639e843、6497ba0、84626a4、fd89df3、4a6a173 |
| 95 | P2：webhook 駁回誤報與 SSRF、Hikari 設定失效、mock fail-open、快取一致性、設計器運算式、lint 規則、稽核缺口 | BPM Core | bcdcb78、b65411a、1a9e04d、fcdcc84、7253e69、7986158、ca3bb44 |
| 96 | Stage 3：form-service 併入 bpm-core（ADR-001，資料庫仍獨立） | BPM Core | 9d67a2e |
| 97 | Stage 4：Flowable 6.8.1 → 7.2.0（Boot 不動） | BPM Core | 581e176 |
| 98 | R-01 平台層認證：Spring Security＋JWT 驗證＋信任閘道、授權矩陣預設 denyAll；錯誤回應被蓋成 403 的修正 | Security | 617b457、8c306bb |
| 99 | R-09 外部 API 授權精確比對；R-20 initiator 由伺服器決定、代員工發起需系統授權 | Security | f12a8c2、4ee75d4 |
| 100 | P1-14 稽核 fail-closed（寫不進稽核即回滾業務），及其後浮現的兩個回歸 | BPM Core | 4aa45ab、5a47dad |
| 101 | 「沒有人看得到的任務」commit 後告警 | BPM Core | 8ca7faa |
| 102 | 權限持有人全部不在時改派代理人 | BPM Core | 68d8518 |
| 103 | 稽核人員（`audit:log:read`）唯讀調閱任何案件的附件，每次留痕 | BPM Core | 0704b1d |
| 104 | 批註記錄作者 | BPM Core | 039252e |
| 105 | 前端：集中 HTTP 與身分處理、路由角色守衛、auditor 角色收斂為明確清單、Vitest 框架與日期格式化集中 | Frontend | 9a19fbf、0ce839b、b5fd0b3、6b86040、a92ec42 |

測試：後端約 280 個、前端 59 個，全綠；`acceptance-test.sh` PASS 7 / FAIL 0。
驗收案例 TC-A01（加簽）、TC-A02（多方意見）、TC-A04（外部系統 API）已有對應的自動化測試（`bpm-core/src/test/.../acceptance/`）。

---

## 總結

| 階段 | 工項數 | 估計人天 |
|------|--------|---------|
| Phase 1：微服務基礎建設 | 16 | 13d |
| Phase 2：核心待辦 + 基礎表單 | 16 | 25.5d |
| Phase 3：表單設計器 + 公文系統 | 14 | 19d |
| Phase 4：流程設計平台 | 13 | 21.5d |
| Phase 5：外部系統接入 | 12 | 16d |
| 架構重構 | 10 | 7.5d |
| 驗收測試環境 | 6 | 5d |
| **合計（第一～七節）** | **87** | **~107.5 人天** |
| 技術債修復與安全強化（第八節） | 18 | 不回推（見第八節） |

---

## 備註

- 以上估時基於程式碼規模與複雜度逆向推估，實際開發可能含 AI Coding Tool 輔助加速
- ~~Phase 5 外部系統接入的業務邏輯（API Key 完整驗證、IP 白名單、allowedActions 檢查等）尚未完善~~
  —— 已於第八節完成（R-09、R-20）。剩餘缺口見待辦清單 #16、#20（#68 的 R-20 文件收尾已於 2026-10-02 完成）
- 目前 OrgService / PermService 仍以 MockController 替代外圍系統（mock 已改為 fail-closed），
  正式整合等權限中心（`docs/rbac-enterprise-backlog.md`，未開工）
- ~~驗收測試案例中，加簽（TC-A01）與外部系統 API（TC-A04）尚未通過~~ —— 已有對應自動化測試且通過（第八節）
