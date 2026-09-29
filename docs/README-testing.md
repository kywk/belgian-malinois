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

### 權限碼（2026-09-29 起）

`/api/**` 不再一律「登入即可」，有兩條路徑改由**權限中心的權限碼**控制。
權限碼的 dev fixture 在 `MockPermController`（`/mock/perm/api/...`）。

| 權限碼 | 保護什麼 | dev 持有者 | ROLE_ADMIN 可否 |
|--------|----------|-----------|----------------|
| `audit:log:read` | `GET /api/audit-logs/**`；流程變數／表單資料／附件的稽核讀取旁路 | `dir001` | **否** |
| `bpm:form:design` | `POST` / `PUT` / `DELETE /api/forms/**`（`GET` 維持登入即可） | `mgr001` | **是** |

兩列刻意不同：

- **稽核不含 `admin001`** —— 稽核紀錄的 `detail` 會帶整包流程變數
  （`AuditEvent.detail`，見 `ExternalApiController.completeTask`），
  也就是全公司的薪資、簽核意見與核決金額。而 `ProcessAccessGuard`
  刻意拒絕 `ROLE_ADMIN` 讀案件流程變數；若這裡放行，管理員就能用稽核查詢
  把 `requireReadAccess` 擋下的資料整批撈出來 —— 那是側門，不是政策。
- **表單設計含 `admin001`** —— 表單 schema 是**設定資產**不是個人資料，
  把「能否改流程的設定行為」納入系統管理是合理的；通配持有者本來就是
  超級使用者，剝奪這項權只換不到安全收益。設計 schema 的操作全部留下
  `FORM_UPDATE` 稽核。

⚠️ **`dev-token.sh` 的第二個參數會取代權限中心查詢**（JWT 的 `roles` claim
優先）。要測「依權限中心指派」的路徑就**不要**帶它：

```bash
# ✅ 有 audit:log:read（dir001）
./scripts/dev-token.sh dir001

# ❌ 變成管理員，但沒有 audit:log:read → 查稽核會 403
./scripts/dev-token.sh dir001 admin
```

---

## 權限矩陣速查（實測回應碼）

| 請求 | user001（無權限） | mgr001（`bpm:form:design`） | dir001（`audit:log:read`） | admin001（`*`） |
|------|------|------|------|------|
| `GET /api/forms` | 200 | 200 | 200 | 200 |
| `POST /api/forms` | **403** | 200 | **403** | 200 |
| `POST /api/forms/{key}/revisions` | **403** | 200 | **403** | 200 |
| `PUT /api/forms/{id}` | **403** | 200 | **403** | 200 |
| `POST /api/forms/{id}/publish` | **403** | 200 | **403** | 200 |
| `POST /api/forms/{id}/archive` | **403** | 200 | **403** | 200 |
| `DELETE /api/forms/{id}` | **403** | 200 | **403** | 200 |
| `GET /api/audit-logs` | **403** | **403** | 200 | **403** |
| `GET /api/audit-logs/integrity-check` | **403** | **403** | 200 | **403** |

讀表單 schema 對**任何**登入者都是 200：業務人員要能看到同事做了哪些表單，
才知道自己要建哪一個。讀不開與改得動是兩件事。

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

### 表單設計（2026-09-29 起需要 `bpm:form:design`）

`POST`／`PUT`／`DELETE /api/forms/**` 需要權限碼 `bpm:form:design`（或 `ROLE_ADMIN`）。
這條規則取代「任何登入者都能改表單 schema」—— 依 spec §8.5，**表單欄位 id 就是
流程變數名**，所以能改 schema 就能加一個欄位 id 叫 `approved`，
讓送件人在填表時就決定簽核結果。

```bash
# ✅ mgr001 由權限中心持有 bpm:form:design
FORM_TOKEN=$(./scripts/dev-token.sh mgr001)

# 讀：任何登入者都可以（業務人員要能看到別人做的表單）
curl "http://localhost:8080/api/forms" \
  -H "Authorization: Bearer $(./scripts/dev-token.sh user001)"

# 建立改版 draft（formKey 換成 data.sql 裡的既有表單，例如 leave-form）
curl -X POST "http://localhost:8080/api/forms/leave-form/revisions" \
  -H "Authorization: Bearer $FORM_TOKEN"

# 改 schemaJson（id 用上一個指令回傳的 id）
curl -X PUT "http://localhost:8080/api/forms/{id}" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $FORM_TOKEN" \
  -d '{"name":"改版後的表單","schemaJson":"{\"fields\":[{\"id\":\"leaveType\"}]}"}'

# 發布
curl -X POST "http://localhost:8080/api/forms/{id}/publish" \
  -H "Authorization: Bearer $FORM_TOKEN"

# ❌ 同一件事用沒有該權限的身分 → 403
curl -X POST "http://localhost:8080/api/forms/leave-form/revisions" \
  -H "Authorization: Bearer $(./scripts/dev-token.sh user001)"
```

### 稽核 Log

```bash
# ⚠️ 必須用持有 audit:log:read 的 dir001 —— admin001 刻意不行。
# 稽核紀錄含全公司薪資與簽核意見，「能管理系統」不等於「能看全公司薪資」。
# （2026-09-29 起移除 ROLE_ADMIN 的旁路，理由見上方權限碼說明。）
# 不要帶 dev-token.sh 的第二個參數：roles claim 會取代權限中心查詢。
AUDIT_TOKEN=$(./scripts/dev-token.sh dir001)

# 查詢特定流程的稽核紀錄（audit-log-service 已於 2026-04-24 併入 bpm-core）
curl "http://localhost:8080/api/audit-logs?processInstanceId={procId}" \
  -H "Authorization: Bearer $AUDIT_TOKEN"

# 驗證 hash chain 完整性
curl "http://localhost:8080/api/audit-logs/integrity-check?startDate=2026-01-01T00:00:00Z&endDate=2027-01-01T00:00:00Z" \
  -H "Authorization: Bearer $AUDIT_TOKEN"
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
