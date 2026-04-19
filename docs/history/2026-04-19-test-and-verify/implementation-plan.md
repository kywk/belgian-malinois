# BPM Platform Phase 1-5 驗收測試計畫

> 建立時間：2026-04-19
> 目標：Phase 1-5 已實作完成，進行完整驗收測試

---

## 系統概覽

| 服務 | Port | 說明 |
|------|------|------|
| bpm-core | 8080 | Flowable 引擎、任務操作、通知、Webhook、外部接入 |
| form-service | 8081 | 表單定義管理、表單資料讀寫 |
| audit-log-service | 8082 | 稽核紀錄（append-only + hash chain） |
| nginx | 80 | API Gateway（路由 + JWT 驗證轉發） |
| mssql | 1433 | 資料庫（bpm_core_db / bpm_form_db / bpm_audit_db） |
| rabbitmq | 5672 / 15672 | 訊息佇列（非同步通知、稽核寫入） |
| redis | 6379 | 快取（org/perm API 回應） |

---

## 測試用戶規劃

| userId | 姓名 | 角色 | 部門 | 直屬主管 |
|--------|------|------|------|---------|
| user001 | 王小明 | 一般員工 | dept001 | mgr001 |
| user002 | 李小華 | 一般員工 | dept001 | mgr001 |
| user003 | 張小芳 | 一般員工 | dept001 | mgr001 |
| user004 | 陳大文 | 一般員工 | dept002 | mgr002 |
| user005 | 林小玲 | 一般員工 | dept002 | mgr002 |
| mgr001 | 李主管 | 部門主管 | dept001 | dir001 |
| mgr002 | 陳主管 | 部門主管 | dept002 | dir001 |
| dir001 | 王總監 | 總監 | dept001 | — |
| admin001 | 系統管理員 | 管理員 | admin | — |

權限設定（MockPermController）：
- `hr:leave:approve` → mgr001, mgr002, dir001
- `finance:payment:approve` → mgr001, dir001
- `purchase:order:approve` → mgr001, mgr002, dir001

---

## 測試流程

### 流程一：請假審核（leave-approval）

```
申請人 (user001) → 主管審核 (mgr001) → [同意/退件/拒絕]
```

BPMN formKey 對應：
- Start Event: `leave-request`
- UserTask 主管審核: `leave-review`

測試案例：
1. **正常同意**：user001 申請 → mgr001 同意 → 流程結案 (endApproved)
2. **退件**：user001 申請 → mgr001 退件 (approved=false) → 流程走 default → endReturned
3. **拒絕**：user001 申請 → mgr001 拒絕 (rejected=true) → 流程終止 (endRejected)

### 流程二：採購審核（purchase-approval）

```
申請人 (user001) → 主管審核 (mgr001) → 財務審核 (mgr001/dir001) → [同意/退件/拒絕]
```

BPMN formKey 對應：
- Start Event: `purchase-request`
- UserTask 主管審核: `purchase-review`
- UserTask 財務審核: `purchase-review`（candidateUsers 由 permService 決定）

測試案例：
1. **完整同意**：user001 申請 → mgr001 同意 → mgr001 財務認領並同意 → 流程結案
2. **主管退件**：user001 申請 → mgr001 退件 → endReturned
3. **財務拒絕**：user001 申請 → mgr001 同意 → mgr001 財務拒絕 → endRejected

---

## 任務清單

### Task 1：強化 MockOrgController
- 加入真實測試用戶資料（user001~user005, mgr001, mgr002, dir001, admin001）
- 建立正確的主管對應關係
- 加入 `GET /mock/org/api/users` 列表端點

### Task 2：強化 MockPermController
- `hr:leave:approve` → [mgr001, mgr002, dir001]
- `finance:payment:approve` → [mgr001, dir001]
- `purchase:order:approve` → [mgr001, mgr002, dir001]
- 依 userId 回傳對應的權限清單

### Task 3：建立 docker-compose.dev.yml
- 繼承 docker-compose.yml 所有服務
- 加入 MailHog（port 1025 SMTP / 8025 Web UI）
- bpm-core 的 mail.host 指向 mailhog

### Task 4：建立 seed-data.sh
- 等待服務健康後自動執行
- 部署 BPMN（leave-approval, purchase-approval）
- 建立表單定義（透過 form-service API）
- 驗證部署結果

### Task 5：建立 acceptance-test.sh
- 完整測試請假流程（同意/退件/拒絕）
- 完整測試採購流程（完整同意/主管退件/財務拒絕）
- 測試批註功能
- 測試加簽功能
- 輸出測試結果摘要

### Task 6：建立 README-testing.md
- 環境啟動步驟
- 測試用戶說明
- 手動測試操作說明（前端 UI）
- 自動化測試腳本使用說明
- 常見問題排查

---

## 關鍵 API 端點

### bpm-core (port 8080)

```
# 健康檢查
GET  /actuator/health

# Mock 組織/權限（開發用）
GET  /mock/org/api/users
GET  /mock/org/api/users/{userId}
GET  /mock/org/api/users/{userId}/manager
GET  /mock/perm/api/permissions/{permCode}/users

# 流程部署
POST /api/deployments          (multipart: file=*.bpmn20.xml)

# 流程操作
POST /api/process-instances    啟動流程
GET  /api/tasks?assignee={userId}
GET  /api/tasks?candidateUser={userId}
PUT  /api/tasks/{id}           完成任務（同意/退件/拒絕）
POST /api/tasks/{id}/comments  加入批註

# 歷史查詢
GET  /api/history/process-instances?initiator={userId}
GET  /api/history/tasks?processInstanceId={id}
```

### form-service (port 8081)

```
GET  /api/forms/{formKey}      取得表單 Schema
POST /api/forms                建立表單定義
POST /api/form-data            提交表單資料
GET  /api/form-data/{processInstanceId}
```

### audit-log-service (port 8082)

```
GET  /api/audit-logs?processInstanceId={id}
GET  /api/audit-logs/integrity-check
```

---

## 注意事項

1. **MockOrgController** 的 `getDirectManager(initiator)` 在 BPMN EL 中被呼叫，必須回傳正確的 managerId
2. **MockPermController** 的 `getUsersByPermission('finance:payment:approve')` 決定採購流程財務審核的 candidateUsers
3. 前端 JWT token 目前為 mock，格式：`Bearer {userId}`（auth store 直接存 userId 作為 token）
4. 表單資料（FormData）在啟動流程時同步寫入 form-service，流程變數寫入 Flowable
5. 稽核 log 透過 RabbitMQ 非同步寫入，測試時需稍等片刻再查詢
