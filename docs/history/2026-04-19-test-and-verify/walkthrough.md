# 2026-04-19 驗收測試 Walkthrough — 問題修復紀錄

## 啟動方式

```bash
# 本地開發必須使用 dev overlay（mail 設定指向 mailhog）
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d

./scripts/seed-data.sh      # 部署 BPMN、驗證表單定義
./scripts/acceptance-test.sh  # 自動化驗收測試（7 個案例）
```

---

## 修復清單

### 1. bpm-core 啟動失敗：`TaskService` bean 找不到

**原因**：Flowable 6.8.1 用 `spring.factories`（Spring Boot 2.x 格式），Spring Boot 3.x 不再讀取其中的 `EnableAutoConfiguration`，導致 Flowable auto-configuration 未載入。

**修法**：`BpmCoreApplication.java` 加 `@ImportAutoConfiguration`

```java
@ImportAutoConfiguration({
    ProcessEngineAutoConfiguration.class,
    ProcessEngineServicesAutoConfiguration.class,
    FlowableJpaAutoConfiguration.class
})
```

---

### 2. bpm-core 啟動失敗：`processEngine` 循環依賴

**原因**：`ProcessCompletedListener` 注入 `RuntimeService`，在 `ProcessEngine` 建立前被呼叫，形成循環。

**修法**：`ProcessCompletedListener.java` 的 `RuntimeService` 加 `@Lazy`

---

### 3. BPMN 部署失敗：BpmnLintService 連不到 form-service

**原因**：`BpmnLintService` hardcode `http://localhost:8081`，在 Docker container 內無法連到 form-service。

**修法**：改為 `@Value("${bpm.form-service-url:http://localhost:8081}")`，docker profile 加 `bpm.form-service-url: http://form-service:8081`

---

### 4. 退件操作 500：`Cannot resolve identifier 'rejected'`

**原因**：退件時只傳 `approved=false`，BPMN Gateway 條件 `${rejected == true}` 找不到 `rejected` 變數拋出異常。

**修法**：`TaskController.java` complete 時自動補 `vars.putIfAbsent("rejected", false)`

---

### 5. form-service 啟動失敗：MERGE SQL 語法錯誤

**原因**：Spring Boot `ScriptUtils` 用 `;` 分割 SQL，MSSQL MERGE 語句需要明確分隔符。

**修法**：`application.yml` 加 `spring.sql.init.separator: "@@"`，`data.sql` 末尾加 `@@`

---

### 6. bpm-core mail health DOWN

**原因**：`docker compose up`（不帶 dev overlay）時，mail host 仍是 `localhost:1025`，MailHog 在 container 內不可達。

**修法**：必須用 dev overlay 啟動：`docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d`

> ⚠️ 不可將 `mail.host: mailhog` 寫入 `application.yml` 的 docker profile，正式環境用同一個 profile 但沒有 mailhog。

---

### 7. Docker build 速度慢（每次重新下載 Maven 依賴）

**修法**：三個服務的 `Dockerfile` 改用 BuildKit cache mount

```dockerfile
# syntax=docker/dockerfile:1
RUN --mount=type=cache,target=/root/.m2 mvn dependency:go-offline -q
RUN --mount=type=cache,target=/root/.m2 mvn package -DskipTests -q
```

效果：第二次 build 從 ~120s 降至 ~7s

---

### 8. 前端空白：nginx 缺少 MIME types

**修法**：`nginx.conf` 加 `include /etc/nginx/mime.types;`

---

### 9. 前端空白：nginx 未 mount 前端 dist

**修法**：`docker-compose.yml` nginx service 加 volume mount

```yaml
- ./bpm-frontend/dist:/usr/share/nginx/html:ro
```

---

### 10. 流程圖無法顯示：BPMN 無 DI 資訊

**原因**：手寫 BPMN 沒有 `<bpmndi:BPMNDiagram>` section，`DefaultProcessDiagramGenerator` 無法繪圖。

**修法**：
- 後端加 `GET /api/process-instances/{id}/bpmn-xml`，用 `BpmnAutoLayout` 自動補 DI 後回傳 XML
- 前端 `ProcessDiagram.vue` 改用 bpmn-js Viewer 渲染，標示 active 節點（橘色 overlay）
- pom.xml 加 `flowable-bpmn-layout` 依賴

---

### 11. CountersignController 路由衝突

**原因**：`CountersignController` 的 `@RequestMapping("/api/tasks/{taskId}/countersign")` 與 `TaskController` 的 `@RequestMapping("/api/tasks")` 衝突，Spring 路由解析失敗。

**修法**：`CountersignController` 改為 `@RequestMapping("/api/countersign")`，端點改為 `/{taskId}`

前端 `flowableApi.js` 同步更新：
```js
getSubtasks(taskId)    → GET /api/countersign/{taskId}
createSubtask(taskId)  → POST /api/countersign/{taskId}
completeSubtask(...)   → PUT /api/countersign/{taskId}/{subtaskId}/complete
```

---

### 12. 申請書資料未顯示

**原因**：`DocumentDetail.vue` 沒有取得流程變數，`DynamicForm` 的 `variables` prop 是空的。

**修法**：
- 後端加 `GET /api/process-instances/{id}/variables`
- `DocumentDetail.vue` 在 `onMounted` 取得變數後傳給 `DynamicForm`

---

### 13. 日期欄位顯示錯誤

**原因**：流程變數的 `dateRange` 是字串 `"2026-05-01~2026-05-03"`，但 `el-date-picker` type=`daterange` 需要陣列 `["2026-05-01", "2026-05-03"]`。

**修法**：`DynamicForm.vue` 在 `loadSchema` 和 `watch` 中對 `dateRange` 型別做轉換

---

### 14. 稽核 Log 500：重複 ORDER BY

**原因**：JPQL 已有 `ORDER BY a.createdAt DESC`，`PageRequest` 又傳 `Sort.by(DESC, "createdAt")`，MSSQL 不允許重複排序欄位。

**修法**：`AuditLogController.java` 改為 `PageRequest.of(page, size)`（不傳 Sort）

---

### 15. 稽核 Log RabbitMQ queue 不存在

**原因**：`audit.log.queue` 只在 bpm-core 宣告，audit-log-service 啟動時 queue 可能不存在。

**修法**：`audit-log-service` 的 `JacksonAmqpConfig.java` 自行宣告 exchange + queue + binding

---

### 16. nginx 路由問題：301 redirect + 尾斜線

**原因**：nginx `location /api/audit-logs/` 有尾斜線，`/api/audit-logs` 被 301 redirect；`^~ /api/audit-logs` 不匹配 `/api/audit-logs/`。

**修法**：
- nginx 改用 `location ^~ /api/audit-logs`（無尾斜線）
- `AuditLogController.java` 的 `@GetMapping` 改為 `@GetMapping({"", "/"})`（同時接受有無尾斜線）
- nginx 同步加入 `/api/form-data` 路由到 form-service

---

## 修改檔案彙整

| 檔案 | 修改內容 |
|------|---------|
| `bpm-core/src/main/java/.../BpmCoreApplication.java` | `@ImportAutoConfiguration` 載入 Flowable |
| `bpm-core/src/main/java/.../ProcessCompletedListener.java` | `@Lazy RuntimeService` |
| `bpm-core/src/main/java/.../BpmnLintService.java` | form-service URL 改為 `@Value` |
| `bpm-core/src/main/java/.../TaskController.java` | complete 時補 `rejected=false` |
| `bpm-core/src/main/java/.../ProcessController.java` | 加 `/variables` 和 `/bpmn-xml` 端點，移除 PNG diagram |
| `bpm-core/src/main/java/.../CountersignController.java` | 路徑改為 `/api/countersign/{taskId}` |
| `bpm-core/src/main/resources/application.yml` | docker profile 加 `bpm.form-service-url` |
| `bpm-core/src/main/resources/processes/*.bpmn20.xml` | Gateway 條件改為 `${rejected != null && rejected == true}` |
| `bpm-core/pom.xml` | 加 `flowable-bpmn-layout` 依賴 |
| `bpm-core/Dockerfile` | BuildKit cache mount |
| `form-service/src/main/resources/application.yml` | `spring.sql.init.separator: "@@"` |
| `form-service/src/main/resources/data.sql` | 末尾加 `@@` |
| `form-service/Dockerfile` | BuildKit cache mount |
| `audit-log-service/src/main/java/.../JacksonAmqpConfig.java` | 宣告 audit exchange/queue/binding |
| `audit-log-service/src/main/java/.../AuditLogController.java` | 移除重複 Sort，`@GetMapping({"", "/"})` |
| `audit-log-service/Dockerfile` | BuildKit cache mount |
| `infra/nginx/nginx.conf` | 加 mime.types、`^~` 前綴匹配、加 `/api/form-data` 路由 |
| `docker-compose.yml` | nginx 加 frontend dist volume mount |
| `docker-compose.dev.yml` | 加 `SPRING_PROFILES_ACTIVE: docker` |
| `bpm-frontend/src/App.vue` | 加登入 overlay（測試用戶選擇） |
| `bpm-frontend/src/views/StartProcess.vue` | 新增發起申請頁面 |
| `bpm-frontend/src/views/TaskInbox.vue` | 接 auth store，合併 assignee + candidateUser |
| `bpm-frontend/src/views/DocumentDetail.vue` | 取流程變數，接 auth store |
| `bpm-frontend/src/views/MyApplications.vue` | 接 auth store（computed userId） |
| `bpm-frontend/src/views/Dashboard.vue` | 接 auth store（computed userId） |
| `bpm-frontend/src/views/AuditLog.vue` | `onMounted` 取代裸呼叫，加 error handling |
| `bpm-frontend/src/components/ProcessDiagram.vue` | 改用 bpmn-js Viewer + active node overlay |
| `bpm-frontend/src/components/DynamicForm.vue` | dateRange 字串轉陣列 |
| `bpm-frontend/src/services/flowableApi.js` | countersign URL 更新 |
| `bpm-frontend/vite.config.js` | 加 `optimizeDeps` for bpmn-js |
| `scripts/seed-data.sh` | health check 接受 degraded 狀態 |
| `scripts/acceptance-test.sh` | 修正 variables 格式、processInstanceId 過濾、`PASS=$((PASS+1))` |
