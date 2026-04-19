# Phase 1（3週）— 微服務基礎建設

## 目標

建立三個微服務的專案骨架、基礎設施（Nginx / RabbitMQ / Redis），以及 BPM Core 與外圍系統的整合。Phase 結束時應能啟動所有服務、通過健康檢查、寫入/查詢稽核 log。

---

## Implementation Plan

### Week 1：專案骨架 + 基礎設施

1. 建立 monorepo 或 multi-module 專案結構
   - `bpm-core/`：Spring Boot + Flowable + JPA + MSSQL
   - `form-service/`：Spring Boot + JPA + MSSQL
   - `audit-log-service/`：Spring Boot + JPA + MSSQL
   - `bpm-frontend/`：Vue 3 + Vite
2. Docker Compose 開發環境
   - MSSQL（3 個 DB：bpm_core_db / bpm_form_db / bpm_audit_db）
   - RabbitMQ（management UI）
   - Redis
   - Nginx（API Gateway）
3. Nginx 配置
   - `/api/` → BPM Core
   - `/api/forms/` → Form Service
   - `/api/audit-logs/` → Audit Log Service
   - JWT 驗證（lua-resty-jwt 或轉發到外圍身分系統驗證）
4. RabbitMQ 基礎設定
   - `audit.exchange`（topic）→ `audit.log.queue`
   - `bpm.exchange`（topic）→ 通知 / webhook 相關 queue
   - Dead Letter Exchange + DLQ

### Week 2：BPM Core 核心

5. Flowable 環境建置
   - Spring Boot + Flowable 6.x 整合
   - MSSQL 資料來源配置（Flowable ACT_* 表自動建立）
   - Flowable REST API 封裝為 `/api/` 前綴 Controller
6. OrgService REST Client wrapper
   - `OrgRestClient`：呼叫外圍組織系統 API
   - `OrgService` Bean：Redis 快取 + 外圍 API 呼叫
   - 快取失效端點 `POST /api/internal/cache-invalidate/org`
7. BpmPermissionService REST Client wrapper
   - `PermRestClient`：呼叫外圍權限系統 API
   - `BpmPermissionService` Bean：Redis 快取 + 外圍 API 呼叫
   - 快取失效端點 `POST /api/internal/cache-invalidate/perm`
8. BpmQueryService
   - 組合 OrgService + PermService 的查詢邏輯

### Week 3：Audit Log Service + 驗證

9. Audit Log Service 核心
   - `AuditLog` JPA Entity（含 hashValue / previousHash）
   - MQ Consumer：監聽 `audit.log.queue`，寫入 DB
   - Hash chain 實作：SHA-256(前一筆 hash + 本筆內容)
   - append-only 保證：DB 層面禁止 UPDATE/DELETE（trigger 或權限控制）
10. Audit Log API
    - `GET /api/audit-logs?processInstanceId=&operatorId=&operationType=&startDate=&endDate=`
    - `GET /api/audit-logs/integrity-check?startDate=&endDate=`
11. 前端稽核 Log 查詢頁面（基礎版）
    - `AuditLog.vue`：條件查詢表單 + 結果表格 + 分頁
12. 各服務 AuditEventPublisher
    - BPM Core / Form Service 共用的 MQ 發送元件
13. 整合測試
    - 所有服務啟動 + 健康檢查
    - Nginx 路由驗證
    - OrgService / PermService mock 外圍 API 測試
    - 稽核 log 寫入 + 查詢 + hash chain 驗證

---

## Tasks Breakdown

| # | Task | 服務 | 預估 | 依賴 |
|---|------|------|------|------|
| 1.1 | 專案骨架建立（3 個 Spring Boot module + Vue 3） | All | 0.5d | - |
| 1.2 | Docker Compose（MSSQL×3 + RabbitMQ + Redis + Nginx） | Infra | 1d | - |
| 1.3 | Nginx 配置（路由 + JWT 轉發） | Infra | 0.5d | 1.2 |
| 1.4 | RabbitMQ exchange / queue / binding 配置 | Infra | 0.5d | 1.2 |
| 1.5 | Flowable + MSSQL + JPA 環境建置 | BPM Core | 1d | 1.1, 1.2 |
| 1.6 | Flowable REST API 封裝（/api/ Controller） | BPM Core | 1.5d | 1.5 |
| 1.7 | OrgService REST Client + Redis 快取 | BPM Core | 1d | 1.5 |
| 1.8 | BpmPermissionService REST Client + Redis 快取 | BPM Core | 1d | 1.5 |
| 1.9 | 快取失效 webhook 端點 | BPM Core | 0.5d | 1.7, 1.8 |
| 1.10 | BpmQueryService | BPM Core | 0.5d | 1.7, 1.8 |
| 1.11 | AuditLog Entity + hash chain 實作 | Audit Log | 1d | 1.1, 1.2 |
| 1.12 | MQ Consumer + append-only 寫入 | Audit Log | 1d | 1.4, 1.11 |
| 1.13 | 稽核查詢 API + 完整性驗證 API | Audit Log | 1d | 1.11 |
| 1.14 | AuditEventPublisher 共用元件 | BPM Core | 0.5d | 1.4 |
| 1.15 | 前端 AuditLog.vue 查詢頁面 | Frontend | 1d | 1.13 |
| 1.16 | 整合測試 | All | 1.5d | All |

---

## Subagent Handoff Prompts

### Agent A：基礎設施（Week 1）

```
你負責建立 BPM 平台的開發環境基礎設施。

請完成以下工作：

1. 建立專案結構：
   - bpm-core/：Spring Boot 3.x + Flowable 6.x + Spring Data JPA + MSSQL
   - form-service/：Spring Boot 3.x + Spring Data JPA + MSSQL
   - audit-log-service/：Spring Boot 3.x + Spring Data JPA + MSSQL
   - bpm-frontend/：Vue 3 + Vite + Pinia
   每個 Spring Boot 服務需有 application.yml、健康檢查端點。

2. Docker Compose 檔案：
   - MSSQL：建立 3 個 DB（bpm_core_db / bpm_form_db / bpm_audit_db）
   - RabbitMQ：啟用 management plugin
   - Redis：單節點
   - Nginx：反向代理

3. Nginx 配置：
   - /api/forms/* → form-service:8081
   - /api/audit-logs/* → audit-log-service:8082
   - /api/* → bpm-core:8080
   - 路由順序重要：長路徑優先匹配
   - JWT 驗證：轉發 Authorization header，由各服務自行驗證（或透過外圍身分系統）

4. RabbitMQ 配置（Spring Boot auto-config + 初始化 Bean）：
   - audit.exchange (topic) → audit.log.queue (routing key: audit.#)
   - bpm.exchange (topic) → bpm.notify.queue, bpm.webhook.queue
   - Dead Letter Exchange: dlx.exchange → dlq.audit, dlq.bpm
   - 重試策略：指數退避 1s→2s→4s，最多 3 次

技術約束：
- ORM 用 Spring Data JPA，不用 MyBatis
- 資料庫用 MSSQL
- API Gateway 用 Nginx
- 所有服務 port：BPM Core 8080, Form Service 8081, Audit Log 8082

完成後確認所有服務可啟動並通過健康檢查。
```

### Agent B：BPM Core 服務（Week 2）

```
你負責建立 BPM Core 微服務的核心功能。專案骨架和基礎設施已由 Agent A 完成。

請閱讀 docs/bpm-platform-spec.md 的以下章節：
- §四 Flowable 整合設計（4.1 EL 函數、4.2 Task API）
- §五 OrgService 規格
- §六 BpmPermissionService 規格
- §七 BpmQueryService 規格
- §十五 Cache 策略彙總

請完成以下工作：

1. Flowable 環境建置：
   - Spring Boot + Flowable 6.x 整合配置
   - MSSQL 資料來源（Flowable ACT_* 表自動建立）
   - 確認 Flowable 引擎正常啟動

2. Flowable REST API 封裝（/api/ 統一前綴）：
   - TaskController：GET /api/tasks, PUT /api/tasks/{id}, POST /api/tasks/{id}/comments
   - ProcessController：POST /api/process-instances, GET /api/process-instances/{id}/diagram
   - HistoryController：GET /api/history/tasks, GET /api/history/process-instances
   - DeploymentController：POST /api/deployments
   - 所有 Controller 呼叫 Flowable Java API，不暴露 Flowable 原生 REST

3. OrgService（REST Client wrapper）：
   - OrgRestClient：用 RestTemplate/WebClient 呼叫外圍組織系統
   - OrgService Bean：實作 getDirectManager, getManagerChain, getDeptGroup 等方法
   - Redis 快取：先查 Redis，miss 時呼叫外圍 API
   - Cache Key 格式見 §五 5.3
   - POST /api/internal/cache-invalidate/org 端點

4. BpmPermissionService（REST Client wrapper）：
   - 同上模式，實作 getUsersByPermission, hasPermission 等方法
   - Cache Key 格式見 §六 6.4
   - POST /api/internal/cache-invalidate/perm 端點

5. BpmQueryService：
   - getManagerWithPermission, getDeptUsersWithPermission
   - 組合 OrgService + PermService

6. AuditEventPublisher：
   - 共用元件，發送 AuditEvent 到 RabbitMQ audit.exchange
   - 在各 Controller 操作後呼叫

技術約束：
- 外圍系統 URL 從 application.yml 讀取（開發時用 mock）
- 為外圍系統建立 mock controller 供開發測試
- Redis 快取用 @Cacheable 或手動 RedisTemplate
```

### Agent C：Audit Log Service（Week 2-3）

```
你負責建立 Audit Log Service 微服務。這是獨立微服務，需符合 ISO 27001 稽核要求。

請閱讀 docs/bpm-platform-spec.md §十七 Audit Log Service。

請完成以下工作：

1. AuditLog JPA Entity：
   - 欄位見 §17.3（id, traceId, operationType, operatorId, operatorName, operatorSource,
     processDefinitionKey, processInstanceId, taskId, businessKey, detail, previousState,
     newState, ipAddress, userAgent, hashValue, previousHash, createdAt）
   - createdAt 不可修改
   - DB 層面禁止 UPDATE/DELETE（用 MSSQL trigger 或 DB 權限）

2. Hash Chain 實作：
   - 寫入時：hash = SHA-256(本筆JSON內容 + 前一筆hash)
   - 用 synchronized 或 DB 序列保證順序
   - 完整性驗證 API：遍歷指定區間，重算 hash 比對

3. MQ Consumer：
   - 監聽 audit.log.queue
   - 反序列化 AuditEvent → 計算 hash → 寫入 DB
   - 失敗進 DLQ

4. 查詢 API：
   - GET /api/audit-logs?processInstanceId=&operatorId=&operationType=&startDate=&endDate=
   - 分頁（page + size）
   - GET /api/audit-logs/integrity-check?startDate=&endDate=
   - 需稽核員權限（從 JWT 取角色）

5. 操作類型 enum：見 §17.4（PROCESS_START, TASK_APPROVE, TASK_REJECT 等 20 種）

技術約束：
- append-only：絕對不可有 UPDATE/DELETE 操作
- hash chain 不可斷裂
- 獨立 DB：bpm_audit_db
- 時間戳統一 UTC
```

### Agent D：前端稽核 Log 頁面（Week 3）

```
你負責建立前端稽核 Log 查詢頁面。

技術棧：Vue 3 + Vite + Pinia + 公司 UI 元件庫（Element Plus 或 Ant Design Vue）

請完成：

1. AuditLog.vue 頁面：
   - 查詢條件表單：流程實例 ID、操作人、操作類型（下拉）、時間區間
   - 結果表格：顯示所有 AuditLog 欄位，分頁
   - 操作類型用 tag 顏色區分（approve=綠, reject=紅, return=橙 等）

2. services/auditLogApi.js：
   - 呼叫 GET /api/audit-logs（帶查詢參數）
   - 呼叫 GET /api/audit-logs/integrity-check

3. 路由配置：/audit-log → AuditLog.vue（需稽核員權限）

API 回應格式：
{
  "content": [{ id, traceId, operationType, operatorName, processInstanceId, businessKey, detail, createdAt, ... }],
  "totalElements": 100,
  "totalPages": 10,
  "number": 0
}
```
