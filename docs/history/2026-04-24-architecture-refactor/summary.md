# 2026-04-24 微服務架構調整與表單版本鎖定

## 討論背景

針對 bpm-core、flowable、form-service、audit-log-service 四個元件的微服務拆分策略進行架構評估，並實作兩項重要變更。

---

## 一、架構討論結論

### bpm-core + Flowable 是否該拆分？

**結論：維持合併（現狀）。**

- Flowable 引擎以 `flowable-spring-boot-starter-process` 嵌入 bpm-core，Controller 層直接注入 `RuntimeService`、`TaskService`、`HistoryService`，耦合極深
- 已透過 RabbitMQ 做非同步解耦（webhook、notification、audit），比拆微服務更有效
- 程式碼量 ~2500 LOC，尚未到需要拆分的規模
- 若未來需擴展，建議路徑：read/write 分離 → Flowable async job executor 獨立 worker → 完全拆分

### form-service 是否該獨立？

**結論：維持獨立。**

- 與 bpm-core 耦合度極低（僅 `BpmnLintService` 一個 REST call 驗證 formKey）
- 使用獨立資料庫 `bpm_form_db`
- 未來可預見需求：公文渲染 PDF（CPU intensive），適合獨立服務處理
- 但需解決「流程進行中表單被異動」的一致性問題 → 見下方表單版本鎖定

### audit-log-service 是否該獨立？

**結論：合併進 bpm-core。**

- 功能單純（MQ consumer + append-only 寫入 + 查詢 API），~250 LOC
- 合併可省下一個 JVM 的 fixed overhead（~200-300 MB RAM）
- 保留獨立資料庫 `bpm_audit_db`（多 DataSource），append-only trigger 不受影響

---

## 二、實作：audit-log-service 合併進 bpm-core

### 變更內容

| 類型 | 檔案 | 說明 |
|------|------|------|
| 新增 | `core/audit/model/AuditLog.java` | Entity，指向 bpm_audit_db |
| 新增 | `core/audit/model/OperationType.java` | Enum |
| 新增 | `core/audit/repository/AuditLogRepository.java` | JPA Repository |
| 新增 | `core/audit/service/AuditLogService.java` | Append-only + hash chain |
| 新增 | `core/audit/consumer/AuditEventConsumer.java` | RabbitMQ consumer（保留給 form-service） |
| 新增 | `core/audit/controller/AuditLogController.java` | `/api/audit-logs` REST API |
| 新增 | `core/config/PrimaryDataSourceConfig.java` | Primary DataSource (bpm_core_db) |
| 新增 | `core/config/AuditDataSourceConfig.java` | Audit DataSource (bpm_audit_db) |
| 修改 | `core/audit/AuditEventPublisher.java` | 改為直接呼叫 AuditLogService（@Async） |
| 修改 | `BpmCoreApplication.java` | 加上 @EnableAsync |
| 修改 | `application.yml` | 加入 spring.datasource.audit |
| 修改 | `docker-compose.yml` | 移除 audit-log-service |
| 修改 | `docker-compose.prod.yml` | 移除 audit-log-service |
| 修改 | `nginx.conf` | 移除 audit upstream，/api/audit-logs 走 bpm-core |
| 修改 | `.gitlab-ci.yml` | 移除 audit-log-service CI/CD jobs |
| 刪除 | `.github/workflows/audit-log-service.yml` | 移除 GitHub Actions workflow |

### 設計決策

1. **保留 bpm_audit_db 獨立資料庫** — 多 DataSource 隔離，append-only trigger 不受影響
2. **保留 RabbitMQ consumer** — form-service 仍透過 MQ 發送 audit event，不需改 form-service
3. **bpm-core 內部改為直接呼叫** — 省掉 MQ 序列化開銷，@Async 保持非阻塞
4. **所有呼叫端零修改** — AuditEventPublisher.publish(AuditEvent) 簽名不變

### Commit

```
a955a06 refactor: merge audit-log-service into bpm-core
```

---

## 三、實作：表單版本鎖定與刪除保護

### 問題

流程進行中，管理員若 publish 新版表單或刪除表單，會導致審核者看到的表單結構與流程啟動時不一致。

### 解法：流程啟動時鎖定表單版本

```
流程啟動
  └─ FormVersionLocker.lockVersions()
       ├─ 掃描 BPMN 所有 UserTask/StartEvent 的 formKey
       ├─ 查詢 form-service 取得 latest published version
       └─ 存入 process variable: _formVersions = {"leave-request": 1, "leave-review": 1}

使用者開啟任務
  └─ TaskController.toMap() → 從 _formVersions 取版本 → response 附加 formVersion
       └─ 前端 DynamicForm → getFormSchema(formKey, version) → 取得固定版本 schema
```

### 表單刪除保護

新增 `archived` 狀態，三態生命週期：

| 狀態 | 可編輯 | 可刪除 | 可封存 | 可被版本查詢 | 管理列表可見 |
|------|--------|--------|--------|-------------|-------------|
| draft | ✅ | ✅ | ❌ | ❌ | ✅ |
| published | ❌ | ❌ | ✅ | ✅ | ✅ |
| archived | ❌ | ❌ | — | ✅ | ❌ |

新增 API：
- `POST /api/forms/{id}/archive` — 封存（僅 published 可操作）
- `DELETE /api/forms/{id}` — 刪除（僅 draft 可操作）

### 變更檔案

| 服務 | 檔案 | 說明 |
|------|------|------|
| bpm-core | `service/FormVersionLocker.java` | 新增，掃描 BPMN + 查詢版本 + 存入 process variable |
| bpm-core | `controller/ProcessController.java` | 啟動後呼叫 lockVersions |
| bpm-core | `external/ExternalApiController.java` | 同上 |
| bpm-core | `controller/TaskController.java` | toMap 附加 formVersion |
| 前端 | `components/DynamicForm.vue` | 新增 formVersion prop，帶入查詢 |
| 前端 | `views/DocumentDetail.vue` | 傳遞 formVersion 給 DynamicForm |
| form-service | `model/FormDefinition.java` | status 加入 archived |
| form-service | `service/FormService.java` | 新增 archive()、delete()，list 排除 archived |
| form-service | `repository/FormDefinitionRepository.java` | 新增 findByStatusNot |
| form-service | `controller/FormDefinitionController.java` | 新增 archive、delete endpoints |

---

## 四、JVM 記憶體建議（待處理）

目前所有 Java 服務的 Dockerfile 和 docker-compose 都沒設 JVM memory limit，建議加上：

```yaml
bpm-core:
  environment:
    JAVA_TOOL_OPTIONS: "-Xms256m -Xmx512m"
  deploy:
    resources:
      limits:
        memory: 768M

form-service:
  environment:
    JAVA_TOOL_OPTIONS: "-Xms128m -Xmx256m"
  deploy:
    resources:
      limits:
        memory: 384M
```

---

## 最終架構

```
nginx:80
  ├─ /api/forms, /api/form-data  →  form-service:8081  (bpm_form_db)
  └─ /api/*                      →  bpm-core:8080      (bpm_core_db + bpm_audit_db)
       ├─ Flowable 引擎（嵌入）
       ├─ 流程管理 API
       ├─ 稽核紀錄 API + MQ consumer
       ├─ Webhook / 通知
       └─ 外部系統 API

基礎設施：MSSQL、RabbitMQ、Redis
```

從 3 個 Java 微服務縮減為 2 個，省下約 200-300 MB RAM。
