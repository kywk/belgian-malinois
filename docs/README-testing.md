# BPM Platform 驗收測試操作說明

## 快速開始

### 1. 啟動服務

> **本地開發必須使用 dev overlay**，否則 bpm-core 的 mail 設定會指向 `localhost:1025`（不存在），導致 health check 失敗。

```bash
# ✅ 正確：本地開發（含 MailHog）
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d

# ❌ 錯誤：直接 up 不帶 dev overlay，mail health 會 DOWN
docker compose up -d
```

`docker-compose.dev.yml` 覆蓋了 bpm-core 的 mail 設定，指向 container 內的 `mailhog`。

### 2. 初始化測試資料

```bash
chmod +x scripts/seed-data.sh
./scripts/seed-data.sh
```

### 3. 執行驗收測試

```bash
chmod +x scripts/acceptance-test.sh
./scripts/acceptance-test.sh
```

---

## 服務端點

| 服務 | URL | 說明 |
|------|-----|------|
| 前端 | http://localhost | Vue3 SPA |
| bpm-core | http://localhost:8080 | BPM API |
| form-service | http://localhost:8081 | 表單 API |
| RabbitMQ 管理介面 | http://localhost:15672 | guest/guest |
| MailHog Web UI | http://localhost:8025 | Email mock（dev 環境） |

---

## 測試用戶

| userId | 姓名 | 角色 | 直屬主管 |
|--------|------|------|---------|
| user001 | 王小明 | 一般員工 (dept001) | mgr001 |
| user002 | 李小華 | 一般員工 (dept001) | mgr001 |
| user003 | 張小芳 | 一般員工 (dept001) | mgr001 |
| user004 | 陳大文 | 一般員工 (dept002) | mgr002 |
| user005 | 林小玲 | 一般員工 (dept002) | mgr002 |
| mgr001 | 李主管 | 部門主管 (dept001) | dir001 |
| mgr002 | 陳主管 | 部門主管 (dept002) | dir001 |
| dir001 | 王總監 | 總監 | — |
| admin001 | 系統管理員 | 管理員 | — |

前端登入：在 token 欄位輸入 userId（如 `user001`）即可切換身分。

---

## 手動測試流程

> R-01 之後所有使用者路徑都用 JWT（`Authorization: Bearer`），不再用
> `X-User-Id` 標頭自報身分。取得 token：`TOK=$(./scripts/dev-token.sh user001)`。
> 下文的 `{TOK:user001}` 請換成實際 token。
>
> ⚠️ 啟動流程**不可**帶 `initiator`（#66）：發起人一律由伺服器從已認證的身分
> 決定，body 帶了會被明確拒絕成 400。另外 `variables` 是 **object**（欄位 id ==
> 流程變數名，spec §8.5），不是 `{name,value}` 陣列 —— 那是 `PUT /api/tasks/{id}`
> 的格式，兩者不同。

### 請假流程（leave-approval）

**申請（以 user001 身分）：**
```bash
curl -X POST http://localhost:8080/api/process-instances \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $(./scripts/dev-token.sh user001)" \
  -d '{
    "processDefinitionKey": "leave-approval",
    "variables": {
      "leaveType": "annual",
      "dateRange": "2026-05-01~2026-05-03",
      "reason": "年假"
    }
  }'
```

**查詢 mgr001 的待辦：**
```bash
curl "http://localhost:8080/api/tasks?assignee=mgr001" \
  -H "Authorization: Bearer $(./scripts/dev-token.sh mgr001)"
```

**同意（將 {taskId} 替換為實際 ID）：**
```bash
curl -X PUT http://localhost:8080/api/tasks/{taskId} \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $(./scripts/dev-token.sh mgr001)" \
  -d '{"action":"complete","variables":[{"name":"approved","value":true},{"name":"approverComment","value":"同意"}]}'
```

**退件：**
```bash
# approved=false, rejected 不設（走 default flow → endReturned）
-d '{"action":"complete","variables":[{"name":"approved","value":false},{"name":"approverComment","value":"請補充資料"}]}'
```

**拒絕（終止流程）：**
```bash
# approved=false + rejected=true → endRejected
-d '{"action":"complete","variables":[{"name":"approved","value":false},{"name":"rejected","value":true},{"name":"rejectReason","value":"不符規定"}]}'
```

### 採購流程（purchase-approval）

採購流程有兩個審核節點：主管審核 → 財務審核。

財務審核為 candidateUsers（`finance:payment:approve` 權限），需先認領再完成：

```bash
# 查詢候選任務
curl "http://localhost:8080/api/tasks?candidateUser=mgr001" \
  -H "Authorization: Bearer $(./scripts/dev-token.sh mgr001)"

# 認領
curl -X PUT http://localhost:8080/api/tasks/{taskId} \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $(./scripts/dev-token.sh mgr001)" \
  -d '{"action":"claim"}'

# 完成
curl -X PUT http://localhost:8080/api/tasks/{taskId} \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $(./scripts/dev-token.sh mgr001)" \
  -d '{"action":"complete","variables":[{"name":"approved","value":true},{"name":"approverComment","value":"財務審核通過"}]}'
```

---

## 進階功能測試

### 加簽
```bash
# 建立加簽子任務
curl -X POST http://localhost:8080/api/tasks/{taskId}/countersign \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $(./scripts/dev-token.sh mgr001)" \
  -d '{"countersignUserId": "dir001", "message": "請提供意見"}'
```

### 批註
```bash
curl -X POST http://localhost:8080/api/tasks/{taskId}/comments \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $(./scripts/dev-token.sh mgr001)" \
  -d '{"message": "請注意此案金額已超過授權額度"}'
```

### 稽核 Log
```bash
# 查詢特定流程的稽核紀錄（audit-log-service 已於 2026-04-24 併入 bpm-core）
curl "http://localhost:8080/api/audit-logs?processInstanceId={procId}"

# 驗證 hash chain 完整性
curl "http://localhost:8080/api/audit-logs/integrity-check"
```

---

## 常見問題

**Q: 啟動後 bpm-core 無法連線 MSSQL？**
MSSQL 啟動較慢，等待 healthcheck 通過（約 30-60 秒）。可用 `docker compose ps` 確認狀態。

**Q: 流程啟動後 mgr001 沒有待辦任務？**
確認 MockOrgController 的 `getManager("user001")` 回傳 `mgr001`：
```bash
curl http://localhost:8080/mock/org/api/users/user001/manager
```

**Q: 財務審核任務找不到？**
確認 MockPermController 的 `finance:payment:approve` 包含 `mgr001`：
```bash
curl "http://localhost:8080/mock/perm/api/permissions/finance:payment:approve/users"
```

**Q: Email 通知沒收到？**
使用 dev 環境（含 MailHog），到 http://localhost:8025 查看攔截的 email。
