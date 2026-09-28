# 全系統安全與正確性審查

**日期**：2026-09-28
**方法**：4 個獨立 reviewer 對 bpm-core（55 檔）、form-service（11 檔）、config、BPMN、前端相關檔案做逐行靜態閱讀
**⚠️ 限制**：**無編譯器、無執行環境**。所有發現皆為讀檔推導，標註「無法確定」者需實測確認。

---

## 先修這 5 個

依「風險 ÷ 修復成本」排序。前三項都是小改動，擋掉的是最嚴重的三類失效。

| # | 一句話 | 檔案 | 成本 |
|---|---|---|---|
| 1 | 附件上傳可寫入容器任意路徑，且以 root 執行 → 覆寫 app.jar 即 RCE | `controller/AttachmentController.java:40-44` | 小 |
| 2 | `FlowableConfig` 沒呼叫 `setBeans()` → BPMN 運算式可取用任意 Spring bean | `config/FlowableConfig.java:14-18` | 一行 |
| 3 | 稽核 `integrityCheck` 從不驗證鏈結 → 刪除或篡改都回報 intact | `audit/service/AuditLogService.java:41-63` | 小 |
| 4 | 多個 controller 用 JPA entity 當 `@RequestBody` → 帶 `id` 即覆寫任意資料列 | 見 P0-4 | 中 |
| 5 | `TaskController` 變數無白名單 → 簽核人可改寫 `initiator` 自選下一關簽核人 | `controller/TaskController.java:103-110` | 小 |

⚠️ **第 2 項修復時必須注意**：現有 BPMN 使用 `delegateExpression="${notifyTaskListener}"`（`purchase-approval.bpmn20.xml:20`），`setBeans()` 的 map 必須包含 `notifyTaskListener`，否則現有流程會立刻壞掉。

---

## P0 — 嚴重

### P0-1 附件上傳路徑穿越 → 容器內任意檔案寫入（root 權限）

`controller/AttachmentController.java:40-44`

```java
Path dir = uploadDir.resolve(processInstanceId);   // @RequestParam，零驗證
Files.createDirectories(dir);
String storedName = UUID.randomUUID() + "_" + file.getOriginalFilename();
file.transferTo(dir.resolve(storedName));
```

兩個向量：`processInstanceId=../../../../etc/cron.d` 可建立任意目錄並寫檔；`originalFilename` 由 client 完全控制（Servlet multipart 不剝除路徑），`"uuid_" + "/../../../x"` 正規化後仍可逐層上跳。

`transferTo(Path)` 預設是 `CREATE + TRUNCATE_EXISTING` → 可覆寫既有檔案。而 `bpm-core/Dockerfile` **無 `USER` 指令 → 以 root 執行**，`WORKDIR /app` → 可覆寫 `/app/app.jar`，下次重啟即 RCE。

`controller/DeploymentController.java:52` 的 `bpmnDir.resolve(deployName)` 有完全相同的問題。

**修法**：`processInstanceId` 以 `[0-9a-f-]+` 白名單驗證並確認流程實例存在；檔名一律棄用 client 值（只存 UUID，原始檔名僅存 DB）；寫檔前斷言 `target.normalize().startsWith(uploadDir.normalize())`；Dockerfile 加非 root `USER`。

### P0-2 BPMN 運算式白名單在結構上無法成立

`config/FlowableConfig.java:14-18`、`lint/BpmnLintService.java:137-148`

**⚠️ 這推翻了 CLAUDE.md 原本的說法（已修正）。** 三層都破：

1. **執行期完全無限制**：`FlowableConfig` 只設 `setEventListeners(...)`，**沒有 `setBeans(...)`**。Flowable 在 `beans` 未設定時把整個 `ApplicationContext` 當 EL 命名空間 → 執行期任何 Spring bean（`taskService`、`dataSource`、`auditLogService`…）都能從運算式取用。白名單只存在於部署前的靜態文字檢查。
2. **regex 可繞過**：`\$\{(\w+)\.` 對 `${''.getClass().forName(...)}` 完全不 match（`${` 後是 `'` 不是 `\w`）→ 一個 error 都不產生，lint 判定通過。`${ orgService.x()}`（前置空白）、`${dept}`（無點）、`${orgService['getClass']()}` 同樣繞過。且即使用白名單 bean 開頭，regex 只驗鏈的第一節，`${orgService.getClass().forName(...)}` 照樣通過。
3. **只掃 UserTask 的 3 個屬性**：`conditionExpression`、`taskListener`/`executionListener` 的 `expression`/`delegateExpression`、`serviceTask` 的 `flowable:class`、multi-instance、timer、`formKey` 全未檢查。現存 `purchase-approval.bpmn20.xml:20` 的 `${notifyTaskListener}` 不在白名單卻順利部署，就是活證據。
4. **不遞迴 SubProcess / CallActivity**：`process.getFlowElements()` 非遞迴 → 把 UserTask 包進一層 `<subProcess>`，rule a/b/c/g 全部失效。這是繞過成本最低的一條路，連運算式技巧都不需要。

**修法**：(a) 執行期補 `setBeans(Map.of("orgService",…, "permService",…, "bpmQueryService",…, "notifyTaskListener",…))` —— 投入產出最高的一行；(b) lint 改用 AST 走訪而非正則，拒絕 `getClass`/`forName`/`newInstance`/`invoke` 等 member access；(c) lint 遞迴 `FlowElementsContainer`；(d) 把「掃哪些欄位」反轉為白名單制。

### P0-3 稽核不可篡改性實際上不存在

`audit/service/AuditLogService.java:41-63`、`:65-73`、`:26-32`

四個獨立問題疊加：

1. **從不驗證鏈結**：`integrityCheck()` 只對每筆做 `computeHash(log, log.getPreviousHash())` 與自己的 `hashValue` 比對 —— 用的是**該筆自己儲存的** `previousHash`，**從未比對 `log[n].previousHash == log[n-1].hashValue`**。後果：刪除中間任一筆 → 其餘仍自我一致，回報 `intact: true`；篡改 `detail` 後重算該筆 hash（不動 `previousHash`）→ 通過。
2. **hash 只涵蓋 7 個欄位**（共 17 個）：未涵蓋 `traceId`、`operatorName`、`operatorSource`、`processDefinitionKey`、`businessKey`、`previousState`、`newState`、`ipAddress`、`userAgent`。這些正是 ISO 27001 事件調查要用的「誰、從哪裡、改了什麼」。
3. **hash 在 `createdAt` 賦值前計算**：`append()` 在 `save()` 之前呼叫 `computeHash()`，但 `createdAt` 是 `@PrePersist` 才填。凡未預設 `createdAt` 的呼叫端（`AuditEventConsumer.java:50-55` 在訊息缺 `timestamp` 時就是）存進去的 hash 是用字串 `"null"` 算的 → integrityCheck 重算時用真實時間戳 → **永久誤報該筆遭篡改**。
4. **`synchronized` 跨實例無效**：`append()` 無 `@Transactional`，`findLastRecord()` 與 `save()` 是兩個獨立交易。多副本（prod compose 的既定方向）會讀到相同 `previousHash` → 鏈分叉，而依第 1 點分叉永遠不會被發現。`previousHash` 無 unique index，DB 層也沒防護。

另外 `application.yml:9-15` 兩個 DataSource 都用 **sa** 連線 → 應用自身即可 `DROP TRIGGER`；且 `TRUNCATE TABLE` 本來就不觸發 DELETE trigger，可整表清空。

**修法**：integrityCheck 改真正走鏈（逐筆驗 `previousHash` 相等 + id 連續性）；hash 納入所有持久化欄位並用長度前綴避免分隔符歧義；`append()` 內先補 `createdAt` 再算 hash；`@Transactional("auditTransactionManager")` + `previousHash` 加 UNIQUE；稽核 DB 改用只有 INSERT/SELECT 權限的專用 login。

### P0-4 mass-assignment：用 JPA entity 當 `@RequestBody` → 帶 `id` 即覆寫任意列

Spring Data 的 `save()` 以 `id == null` 判斷 isNew，`id` 非 null 時走 `em.merge()` 變成 **UPDATE**。受影響：

| 端點 | 檔案 | 後果 |
|---|---|---|
| `POST /api/forms` | `form/controller/FormDefinitionController.java:24-27` | 改寫已發布表單的 `schemaJson`，並被強制改成 `draft` → 繞過「只有 draft 能改」守衛，之後連 delete 守衛也失效 |
| `POST /api/form-data` | `form/controller/FormDataController.java:23-29` | 覆寫他人案件已送出的表單資料，而 `submittedAt` 是 `updatable=false` → **篡改無跡** |
| `POST /api/admin/notify-templates` | `controller/NotifyAdminController.java:27,53` | 覆寫既有郵件模板植入釣魚連結，且該 controller **零稽核** → 無人知道被改過 |
| `POST /api/documents` | `controller/DocumentController.java` | 改寫他人公文的 `documentNumber`/`title` |

**修法**：改用 request DTO，或在 entity 的 `id` 標 `@JsonProperty(access = READ_ONLY)`，並在 create 端點顯式 `if (x.getId() != null) throw 400`。

### P0-5 簽核人可改寫 `initiator` → 自選下一關簽核人

`controller/TaskController.java:103-110`

`req.variables().forEach(v -> vars.put(v.name(), v.value()))` 完全信任呼叫端的變數名，無 formKey schema 白名單。（與已登錄的 R-23 不同：R-23 只涵蓋 `_` 前綴，`initiator` 沒有底線。）

兩支已部署 BPMN 都用 `${orgService.getDirectManager(initiator)}` 解析主管、用 `${initiator}` 指派補件任務。申請人完成自己的補件任務時附帶 `{"name":"initiator","value":"某共犯"}`，下一輪主管審核就會派給該人的主管。而前端 `DocumentDetail.vue:107-113` 本來就把整份表單欄位當變數送出（符合 spec §8.5 的 id==變數名約定），所以表單有同名欄位甚至不需改 payload。

### P0-6 加簽可被第三方刪除以跳過簽核，且無痕

`controller/CountersignController.java:62-77`

`completeSubtask` 的路徑參數 `{taskId}`（父）與 `{subtaskId}`（子）**從未驗證屬於同一組**（`getParentTaskId()` 根本沒讀），且 `taskService.deleteTask(subtaskId, true)` 的 cascade=true 會**連歷史一併刪除**。

後果：帶任意 `{taskId}` + 他人的 `{subtaskId}` 即可刪掉尚未審的加簽子任務（流程內任務因有 executionId 刪不掉，但 standalone 加簽子任務可以）。子任務一消失，`TaskController:100` 的守門立刻放行父任務 → 加簽人從未表態，案子照樣過關。且 `completeSubtask` 不發任何稽核事件 + 歷史被 cascade 刪除 → 完全無痕。

---

## P1 — 高

### P1-1 簽核稽核查不出是誰核准的

`controller/TaskController.java:132`、`:111-123`

兩個問題：

- **operatorId 全為 null**：`new AuditEvent(auditType, req.assignee(), ...)` 一律拿 `assignee` 當操作者，但前端主要簽核入口 `ActionDialog.vue:64-84` 的 payload **只有 `action` 與 `variables`，沒有 assignee** → TASK_APPROVE / TASK_RETURN / TASK_REJECT / TASK_DELEGATE 的 operatorId 全是 null。稽核記錄存在，但查不出是誰核准的。
- **三個 operationType 不在 enum 裡**：`"TASK_RESUBMIT"`、`"TASK_RESOLVE"`、`"TASK_UPDATE"` 不存在於 `OperationType`（20 個常數），`AuditEventPublisher:29` 的 `valueOf` 拋 `IllegalArgumentException`，而該方法是 `@Async` 且例外被吞掉只寫 log → 呼叫端收到 200，稽核表**完全沒有那筆**。而兩支 BPMN 的補件任務名稱都含「補件」，`task.getName().contains("補件")` 必中 → **每一次退回重送都不進稽核**。

**修法**：操作者改取 `task.getAssignee()`；把 `AuditEvent` 的 operationType 改傳 enum 而非 String（編譯期綁定，一併消除 `valueOf` 問題）。

### P1-2 併發任務會讓案件列表對所有人 500

`controller/ProcessController.java:57-58`、`:81-82`

`taskService.createTaskQuery().processInstanceId(x).singleResult()` 在結果 >1 筆時拋 `FlowableException`。`:81-82` 位於 `/api/process-instances` 的 stream 之中 → 只要系統中**任何一個**案件有兩個併發任務（平行閘道、multi-instance 會簽），這個端點就對**所有使用者**整體 500。

這是低程式碼平台 —— 業務人員在設計器畫一個平行閘道就能觸發，屬必然而非假設。也很可能是 TC-A02（多方意見）驗收不過的成因。

`:57-58` 則讓 `POST /api/process-instances` 在流程已成功啟動後回 500 → 使用者重送造成重複案件。

### P1-3 有未完成加簽時回 HTTP 200 → 前端誤判為成功

`controller/TaskController.java:100-101`

`return Map.of("taskId", id, "status", "error", ...)` 走正常回應路徑，HTTP 200。`ActionDialog.vue:65-88` 只靠 axios 是否 throw 判斷結果 → 顯示「操作成功」並導航離開。更糟的是 `ActionDialog.vue:60-62` 會在 complete **之前**先寫入 comment → DB 留下一筆「核准意見」而對應的核准從未發生。

該檔其他錯誤路徑都用 `ResponseStatusException`，此處是唯一不一致的。**修法**：改 `throw new ResponseStatusException(HttpStatus.CONFLICT, ...)`。

### P1-4 claim 的搶佔保護可被繞過；未知 action 靜默改派並回報成功

`controller/TaskController.java:88-124`

- `taskService.claim()` 在已被他人 claim 時會拋 `FlowableTaskAlreadyClaimedException`，但 `else if (req.assignee() != null) taskService.setAssignee(...)` **沒有這層檢查** → claim 失敗後改送 `{"assignee":"自己"}`（不帶 action）即可無條件奪取他人任務，且不檢查新 assignee 是否為候選人。
- `claim` 分支未檢查 assignee 為 null。`claim(taskId, null)` 語意是取消認領且跳過已認領檢查 → `{"action":"claim"}` 空 body 可強制釋放他人任務。
- `action` 為任何非四個關鍵字的值（typo、舊版前端）且帶 assignee → 落入改派分支，**本意「核准」變成「改派」**，回應仍是 `{"status":"ok"}`。

**修法**：改顯式 switch，未知 action 回 400。

### P1-5 「同一人不得重複簽核」只是 UI 效果，且會讓案件卡死

`controller/TaskController.java:69-81`

- **範圍是整個流程實例而非節點**：只要 `filterUser` 在該 instance 完成過**任何**任務，所有未指派的候選任務都被移除。實際情境：財務退回 → 主管再審通過 → 案子回到 `financeReview`（candidateUsers），但當初退件的財務人員已有 finished 歷史 → **該任務對他永久隱藏**。若該權限只有一人，案件靜默卡死。
- **只隱藏不阻擋**：`PUT /api/tasks/{id}` 完全沒有對應檢查 → 知道 taskId 就能簽第二次。這個被當成業務規則展示的東西實際上沒有被強制。
- 每次待辦查詢都撈該使用者**全部歷史已完成任務**（無時間範圍、無分頁）。

### P1-6 附件：無授權、可列舉、容器重建即遺失

`controller/AttachmentController.java:57-71`、`application.yml:56-57`、`docker-compose.yml`

- **無物件層授權**：`download(@PathVariable String id)` 只做 `findById`，沒有「呼叫者是否為該案件關係人」檢查。Flowable processInstanceId 是**遞增數字** → 可列舉全公司案件附件並下載。即使補上 authN，程式碼裡也沒有可掛授權判斷的位置。`list()` 還回傳 `filePath` 實體路徑。
- **無 volume**：`bpm.upload.dir: ./uploads` → `/app/uploads` 在容器可寫層，**dev 與 prod 兩份 compose 的 bpm-core 都沒有任何 `volumes:`**（mssql/rabbitmq/redis 都有）。容器重建後附件永久消失，但 `bpm_file_attachment` 列仍在 → 下載端點回 200 但串流時 FileNotFoundException。對 ISO 27001 紀錄保存是直接違反。
- **附件上傳/下載/列表零稽核**：`AttachmentController` 未注入 `AuditEventPublisher`。誰下載了薪資單，沒有紀錄。

### P1-7 金額分級授權是假的（外觀正常但授權降級）

`service/OrgService.java:31-34`、`service/BpmPermissionService.java:58-61`

```java
public String getAuthorizedManager(String userId, BigDecimal amount) {
    // Delegate to getDirectManager; amount-based logic can be extended later
    return getDirectManager(userId);
}
public List<String> getUsersByPermissionAndCondition(String permCode, Map<String,Object> attrs) {
    return getUsersByPermission(permCode);
}
```

`amount` 與 `attrs` 完全未使用，但方法簽名會讓流程設計者相信有金額分級／條件過濾，且這兩個方法都在 EL 白名單允許的 bean 上。

業務人員寫 `${orgService.getAuthorizedManager(initiator, amount)}`，以為一千萬的採購會往上送到有權限的層級，實際永遠只送到一階直屬主管 —— **lint 全綠、執行期無警告、稽核看起來完全正常**。這是簽核系統裡最難發現的一類 fail-open。

**修法**：真正實作前應直接 `throw new UnsupportedOperationException`，或加進 lint 的 error 規則。留一個語意錯誤的可用實作比留一個會爆的 stub 危險得多。

### P1-8 `hasPermission` 冷熱快取答案不同，且用子字串比對

`service/BpmPermissionService.java:45-56`

```java
if (cached != null) return cached.contains(permCode);        // 熱：對 "a,b,c" 子字串比對
List<String> perms = permRestClient.getUserPermissions(userId);
if (perms != null) return perms.contains(permCode);          // 冷：精確比對
```

- **同一組輸入在冷／熱快取下答案不同**：第一次（miss，精確）回 false，5 分鐘內（hit，子字串）可能回 true。授權判定不具決定性，事故無法重現。
- **子字串誤放行**：持有 `hr:leave:approve` 的人，`hasPermission(u, "hr:leave")`、`hasPermission(u, "approve")` 全為 true。以本專案的階層式權限碼命名，「檢視層級的碼」被「核准層級的碼」誤中的機率很高。

此方法被 `BpmQueryService.getManagerWithPermission` 用來挑選簽核人。

### P1-9 主管鏈不去重、不排除本人 → 自我簽核

`service/BpmQueryService.java:22-28`、`service/OrgService.java:49-58`

`getManagerChain` 回傳結果沒有任何清理。組織資料成環（A 的主管是 B、B 的主管是 A）→ chain = `[B,A,B,A,B]` → `getManagerWithPermission` 的 `.findFirst()` **可能回傳 A 自己**。更常見的不需要環：高層自己是自己的主管（組織表常見的頂點表示法）→ chain = `[u,u,u,u,u]` → 必然自我簽核。

**Mock 資料本身就有這個環**：`MockOrgController` 的 `getManager("dir001")` 走 default 回 `mgr001`，形成 `dir001 → mgr001 → dir001` → 正好觸發自我簽核，而且是**下屬核准上司的案件**。

反向失效：`dir001` 這種沒有上級的人 chain 為空 → `getManagerWithPermission` 回 null → UserTask assignee 為 null → 案件卡住無人可簽。

### P1-10 外部依賴故障的失效模式

**組織/權限系統**（`client/OrgRestClient.java:16-17`、`client/PermRestClient.java:16-17`）：

- **無 timeout**（全 repo grep `connect-timeout|read-timeout|requestFactory` 只命中設定檔的 URL）→ 組織系統 hang 住則呼叫執行緒無限期阻塞。
- **例外一路往上拋**，且發生在 JUEL 求值 `${orgService.getDirectManager(initiator)}` 時，也就是在 `taskService.complete()` 的 DB 交易之內 → 整筆 rollback。實際行為是「fail-closed 但案件卡住」：審核者按同意得到 500，重試會一直失敗直到外部系統恢復。同步路徑沒有 async job 重試可依賴。
- **`org-service-url: http://localhost:8080/mock/org` 指向自己，且 docker profile 沒有覆蓋** → 容器內 bpm-core 對自己發同步 HTTP 並在持有 DB 交易時等待。每次任務建立佔用 2 個 Tomcat 執行緒 → 執行緒池飽和時**自我死鎖**，且無 timeout 不會自行解開。表現為「整個 bpm-core 無回應」。

**Redis**（`service/OrgService.java` 各方法第一行、`BpmPermissionService.java:25,36,47`）：每個方法第一件事就是 `redis.opsForValue().get(key)`，**無 try/catch**。Redis 不可用時連「直接去問組織系統」的退路都走不到 → 所有 assignee 解析在讀快取那行就爆。**快取層變成比被快取的系統更關鍵的單點。**

### P1-11 公文編號併發撞號 + 孤兒流程

`controller/DocumentController.java:41-54`

```java
int seq = docRepo.countByPrefix(prefix) + 1;    // COUNT 不是序號
...
var pi = runtimeService.startProcessInstanceByKey(...);   // 先啟流程
DocumentRequest saved = docRepo.save(req);                // 後存檔
```

兩個請求同時 `countByPrefix` 得到相同值 → `documentNumber` 有 unique 約束會擋掉第二筆，但**流程實例已啟動且不會回滾**（方法無 `@Transactional`）→ 留下沒有對應公文列、businessKey 已被佔用的孤兒流程，使用者看到 500。且 COUNT 語意錯誤：刪掉一筆舊公文後下一個號會重複。

**可疑**：`repository/DocumentRequestRepository.java:12` 的 `@Query("... WHERE d.documentNumber LIKE :prefix%")` —— JPQL 不允許具名參數後直接接 `%`。**無法確定**實際行為（可能 bootstrap 就拋 QuerySyntaxException 導致啟動失敗，也可能退化成等值比對使 seq 永遠為 1）。`acceptance-test.sh` 沒有任何 `/api/documents` 案例，這條路徑很可能從未被執行過。

### P1-12 已發布表單沒有任何改版路徑（低程式碼核心功能斷掉）

`form/service/FormService.java:26-30`、`:45-54`、`:56-71`

`create()` 無條件 `setVersion(1)`，沒有「為既有 formKey 建立下一版 draft」的 API；published 列不能 update、不能 delete。

**data.sql 種下的四張表單（`leave-request` 等）都是 `version=1, status='published'` → 透過 API 完全不可修改。** 想改只能呼叫 `publish()` 產生一份內容完全相同的 v2，再也沒有辦法把新的 `schemaJson` 放進去。對 formKey 重新 POST 則撞 unique 約束 500。這直接堵死「讓業務人員自行設計、維運」的產品目標。

`publish()` 另有三個問題：無狀態守衛（對 archived 呼叫會把它**復活**；對 published 重複呼叫版本號無限膨脹）；**一次 publish 產生兩筆 published**（clone 到 v2，又把來源 draft 也標 published）→ UI 出現重複表單；`findMaxVersion()` 讀寫非原子且無 `@Version` → TOCTOU 競態。整個 form-service **沒有任何 `@Transactional`**。

### P1-13 郵件模板機制形同虛設

`notify/NotifyTaskListener.java:29`

```java
msg.put("processDefinitionKey", task.getProcessDefinitionId());   // 是 Id 不是 Key
```

`getProcessDefinitionId()` 回傳 `leave-approval:1:2504`（含版號），而 `EmailConsumer` 用它查 `findByProcessDefinitionKeyAndEventType...`，DB 存的是 `leave-approval` → **永遠查不到任何設定**，`NotifyAdminController` 維護的整套模板機制形同虛設，一律落到硬編中文模板。`${processName}` 會被渲染成那串 id。

（`webhook/WebhookTaskListener.java:29` 對同一件事有正確的 `extractProcessKey()`，兩處不一致。）

另：`NotifyConfig.templateId` 無 `nullable=false` 且 create 不驗證 → 一筆 `templateId=null` 的設定會讓 `findById(null)` 拋 `IllegalArgumentException`，retry 3 次後進 `dlq.bpm`，**該事件通知永久遺失**，且同一 config 之後每則通知都重踩。

候選群組任務的 `task.getAssignee()` 為 null → `EmailConsumer:38-41` 直接 return，**群組待辦完全不發通知**。

### P1-14 稽核寫入 fail-open + DLQ 無人消費無告警

`audit/AuditEventPublisher.java:25-44`、`config/RabbitMQConfig.java:64-80`

- `publish()` 是 `@Async` 且 `catch (Exception)` 只 `log.error` → 稽核寫不進去，業務操作照樣 200。
- `@EnableAsync` 未指定 executor → 用 Boot 預設 `applicationTaskExecutor`，**佇列容量 `Integer.MAX_VALUE`**。DB 慢時稽核事件堆在 heap，重啟／OOM 即全部遺失，無任何可觀測指標。
- **`dlq.audit` / `dlq.bpm` 沒有任何 consumer、沒有告警**（全 repo 只有一個 `@RabbitListener`）。一筆 operationType 拼錯的訊息靜默沉到 DLQ，永遠沒人知道稽核少了一筆。
- retry 是 stateless in-memory（在消費者執行緒 sleep）且預設 concurrency=1 → 稽核 DB 短暫抖動時整條佇列被阻塞每則 3 秒然後永久死信。
- `AuditEventConsumer` **無幂等鍵** → broker 重投會再 append 一列，hash chain 多出重複節點且 integrityCheck 察覺不到。

### P1-15 form-service 的稽核事件可靜默遺失

`form/audit/AuditEventPublisher.java:18-26`、`config/RabbitMQConfig.java`、`docker-compose.yml`

`audit.exchange` / `audit.log.queue` / binding **只在 bpm-core 宣告**，form-service 只發不收且沒有任何 Exchange bean。docker-compose 中 form-service 的 `depends_on` **不含 bpm-core**，兩者平行啟動。且兩份 yml 都沒設 `publisher-confirm-type`。

exchange 尚不存在時 publish → broker 回 channel-level 404，RabbitTemplate 在無 confirm/return 下**不拋例外** → `POST /api/form-data` 照樣 200，稽核記錄沒了。窗口在乾淨 volume 首次啟動最明顯 —— 恰好是 `seed-data.sh` 的情境。

另：`FormDefinitionController` 注入了 `auditPublisher` 但**一次都沒用** → 表單定義的 create/update/publish/archive/delete 全部零稽核。「誰改了審核表的欄位」沒有軌跡，而改 schema 等於改流程行為。

---

## P2 — 中

### P2-1 Webhook 投遞是死碼，但 SSRF 潛伏

`webhook/WebhookConsumer.java:39-64`

全 repo grep：**沒有任何 producer 設定 `__webhookUrl`**（`WebhookTaskListener` 與 `ProcessCompletedListener` 都只把變數放在巢狀的 `variables`/`allVariables` 下）→ 所有 webhook 都走早退分支，**投遞功能實際上是死碼**。

但接線後的問題已存在：

- **HMAC 簽章接收端無法驗證**：`signature = HMAC(json)` 的 `json` 是尚未含 `hmacSignature` 的序列化結果，但送出的 body 是塞入該欄位後**重新序列化**的 `signedJson`。接收端要驗章必須移除欄位後重現位元完全相同的 JSON，而 `payload` 是 `HashMap`，Jackson 鍵序不保證 → 有 header 卻沒有可用的完整性保護。無時間戳/nonce → 無防重放。
- **SSRF**：`url` 由 payload 決定，無 scheme 限制、無 allowlist、不阻擋 `127.0.0.1` / `169.254.169.254` / 內網。`bpm.exchange` 的 binding 是 `bpm.webhook.#`，任何持有 broker 憑證者（form-service 共用同一 broker，帳密明文在 `docker-compose.yml`）都能讓 bpm-core 以伺服器身分打任意內網位址。
- **無 timeout** → 掛住的 endpoint 佔住 listener 執行緒直到 TCP 超時，整條佇列阻塞。
- **全量流程變數外送**：`WebhookTaskListener:52-53` 送 `getVariablesLocal()` 全部、`ProcessCompletedListener:55` 送 `allVariables`，無白名單。表單欄位 id 即流程變數名 → 薪資、身分證號會原封不動送到外部 URL。
- `ProcessCompletedListener:40-46` 的 `catch (Exception ignored)` 後 `vars` 為空 → `result` 一律算成 `"approved"`，**駁回結案會被回報為核准**。

### P2-2 `ddl-auto: update` 的具體風險

`bpm-core/application.yml:17-19`、`form-service/application.yml:12-23`

`update` 只會「加」，不會改也不會刪：

- 改欄位名（`schemaJson` → `schemaBody`）→ 新增 `schema_body`，舊的 `schema_json NOT NULL` 原地保留 → **此後所有 INSERT 全部失敗**。這是最容易踩到的「資料遺失」：不是刪資料，是整張表變成唯讀。
- 縮短 length、改型別 → 靜默忽略造成 DB 與 entity 漂移；把既有欄位改成 `nullable=false` → 有資料的表直接讓**啟動失敗**。
- `flowable.database-schema-update: true` 與 Hibernate `ddl-auto` 在**同一 schema** 各自做 DDL，多副本同時啟動時 MSSQL DDL 互鎖有機率死鎖。

**ADR-001 把兩個 schema 併進 bpm-core 之前，必須先上 Flyway** —— 否則合併那一刻就是上述失敗模式的高風險窗口。

### P2-3 雙 DataSource：`hikari.*` 設定被靜默丟棄

`config/PrimaryDataSourceConfig.java:36-38`、`AuditDataSourceConfig.java:29-32`

**先確認正確的部分**：`@Primary` 四個 bean 齊備；稽核寫入**確實走到 audit DataSource**（`@EnableJpaRepositories` 的 basePackages 與 entity package 無重疊，已逐一比對）；Flowable ACT_* 走 `@Primary` → `bpm_core_db`。

問題：

1. `initializeDataSourceBuilder().build()` 只綁 url/username/password/driver → **`spring.datasource.hikari.*` 完全不生效**。兩個池都跑預設 `maximumPoolSize=10`，而 primary 池同時要餵 web 執行緒**和** Flowable async executor → 負載一上來 job executor 會餓死 web 層，運維在 yml 加參數會**靜默無效**，極難診斷。
2. **交易管理器混用的地雷**：目前只有一處 `@Transactional`（未限定，解析到 `@Primary`，剛好正確）。但慣例沒寫下來 —— 修 P0-3 時若寫成未限定的 `@Transactional`，交易會開在 `bpm_core_db` 上而 audit repository 綁 `auditEntityManagerFactory` 不會加入該交易 → 稽核寫入毫無原子性。**必須寫成 `@Transactional("auditTransactionManager")`。**
3. `spring.datasource.audit.*` 巢狀在 `spring.datasource` 之下，能運作純粹靠 `ignoreUnknownFields=true`；一旦開啟嚴格綁定，primary 的綁定就會失敗。建議移到 `bpm.datasource.audit.*`。

### P2-4 缺漏的稽核事件與未使用的 OperationType

已定義但**全 repo 從未被使用**的 enum 值：`CONFIG_CHANGE`、`DATA_ACCESS`、`EXPORT_DATA`、`PROCESS_COMPLETE`、`PROCESS_CANCEL`、`TASK_URGE`。

零稽核的操作：附件全部端點、通知模板/設定的 8 個 CRUD、**稽核紀錄查詢本身**（ISO 27001 要求稽核存取也要留痕）、流程結案。`BPMN_DEPLOY` 的 operatorId 硬寫 `null`（`DeploymentController.java:60`）—— 部署流程定義是可改寫整條簽核路徑的最高權限操作，卻查不到是誰做的。

`PROCESS_CANCEL` / `TASK_URGE` 未使用是因為**功能本身不存在**（無撤案端點），非僅稽核缺失。

### P2-5 lint 規則自身的缺陷

`lint/BpmnLintService.java`

- **rule h 是死碼**：`boolean isExternalAllowed = false; // Could be read from process extension` 硬寫 false 且無處修改 → 該規則**從未執行過**。這與已登錄的 R-20（severity 是 warning）是兩件事：即使改成 error 也不會有任何效果。
- **rule d 誤報擋住合法流程**：對**每一個** ExclusiveGateway 要求 default flow（severity=error），但匯聚型 gateway 只有一條 outgoing flow，本來就不需要 → 合法 BPMN 被判部署失敗。
- **rule c 把「form-service 不可用」誤判為「表單不存在」**：`catch (Exception e)` 一律回報 severity=error 的「表單定義不存在」→ form-service 掛掉時所有部署失敗並附誤導訊息。且 `formClient` 同樣**無 timeout**。
- `POST /api/bpmn/lint` 無認證且每個 UserTask 觸發一次同步外呼 → 含 1000 個 UserTask 的 XML 會發出 1000 次無 timeout 的循序請求，是廉價的 DoS 面。

### P2-6 設計器產生的運算式與白名單、與實作不一致

`bpm-frontend/src/bpmn/AssigneeProps.js:49,51,52`

- 「特定 Callback」產生 `${callbackService.resolve()}` —— `callbackService` 不在白名單（會被判 error 擋掉部署），且**全 repo 沒有這個 bean**。設計器提供了一個一定無法部署、部署了也一定爆的選項。
- 「直屬主管（N 階）」產生 `${orgService.getManagerChain(initiator, N)[N-1]}` —— 組織鏈長度不足時（頂層主管、或 P1-9 提到的空 list）**list index 越界 → 求值失敗 → 任務建立整筆 rollback**。這是最容易被真實資料觸發的崩潰。
- 「特定單位」產生 `${dept}`，正是 P0-2 所述「無點所以 regex 不 match」的漏洞受益者 → **修 regex 時會連帶擋掉這個產品功能**，需一併處理。

### P2-7 Mock 對未知輸入 fail-open → 真實系統接上時行為反轉

`controller/MockOrgController.java:49-51,55,73,83`、`MockPermController.java:34`

每個 lookup 都有「猜一個答案」的 fallback：未知 userId → `mgr001`；未知 deptId → `user001/002/003`；**未知權限碼 → `List.of("mgr001")`**。

最具體的後果：BPMN 裡權限碼打錯成 `finance:paymnet:approve`，lint 不檢查權限碼，Mock 回 mgr001 → **驗收測試會通過**。接上真實權限中心後同一支流程回空集合 → 任務沒有 candidate → 沒有人在待辦看到它，案件無聲卡死。**開發期的 fail-open 直接轉成生產期的 fail-stuck，且部署後才發現。**

Mock 自身也不自洽：`getManager("dir001")` 回 `mgr001`，但 `getManagerChain("dir001", n)` 回空 `[]` —— 同一事實兩個端點兩種答案。

### P2-8 快取一致性：同一事實被三個不同 TTL 各自快取

`service/OrgService.java:22-29,36-43,69-78,91-103`

`resolveEffective` 與 `isUserAvailable` **都從 `getSubstitute(userId)` 這一個事實推導**，卻各自存不同 key、不同 TTL（`org:substitute` 1 分鐘 vs `org:available` 5 分鐘）。

具體後果：使用者 A 設定代理人後，`resolveEffective(A)` 在 1 分鐘內改指向代理人，但 `isUserAvailable(A)` 還有最多 5 分鐘認為 A 可用 → 這段窗口內任務會派給已在休假的 A，而同一時間另一條路徑認為應派給代理人。**同一個人同時是「可用」和「已委派」。**

其餘：空結果不寫快取 → 「查不到」正是最常被重複查詢的情況（頂層主管、打錯的權限碼）→ 每次任務建立都打穿後端；全部固定 TTL 無 jitter → 重啟後同時到期的雪崩；`org:dept-members:*` 按 deptId 存但 `invalidateCache` 按 userId 刪 → **無任何失效路徑**；`invalidateCache` 的 `type` 參數**整個沒用到**（呼叫端傳了但被丟棄）→ 精準失效的 API 契約是假的。

key 設計隱患：`perm:users:{code}` 與 `perm:users:{code}:{deptId}` 共用前綴而權限碼本身含 `:` → `getUsersByPermissionAndDept("hr:leave", "approve")` 會讀到 `getUsersByPermission("hr:leave:approve")` 的快取。

---

## TC-A01（附屬簽）驗收不過的根因

已找到，是前端呼叫錯誤：

`bpm-frontend/src/components/CountersignDialog.vue:33` 呼叫 `createSubtask({ parentTaskId, assignee, description })` —— 只傳**一個**參數，但 `services/flowableApi.js:17-18` 的簽章是 `(taskId, data)`。實際發出的是 `POST /api/countersign/[object Object]` 且 **body 為 undefined** → `@RequestBody`（required 預設 true）直接 400。

即使修好參數，欄位名也對不上：前端送 `assignee`/`description`，後端讀 `countersignUserId`/`message`（`CountersignController.java:33,38`）→ 落入 null 路徑，而 `:43` 的 `Map.of("assignee", assignee)` 遇 null **直接拋 NPE**，此時子任務已在 `:39` 落地且 assignee 為 null → 沒人看得到它，而父任務被守門永遠無法完成 → **案件死鎖，只能進 DB 手動清**。

另：`completeSubtask`（`flowableApi.js:23`）**全前端無任何呼叫端** → spec §4.4.1「加簽人意見自動附加到原任務 comments」的唯一實作是死碼。

## 其他經確認的功能缺失

- `FormVersionLocker` 只掃 `model.getMainProcess().getFlowElements()` 的**頂層**元素 → SubProcess 與 CallActivity 內的 UserTask formKey **從未被鎖版**（spec §4.4.2 明確支援 Call Activity）。
- `ProcessController.java:100-102` 的 `/{id}/bpmn-xml` 只查 `runtimeService` → **已結案案件的流程圖永遠空白**。
- `BpmnAutoLayout` 就地改動 `repositoryService.getBpmnModel()` 回傳的物件 → 該物件可能是 deployment cache 的共享實例，會污染引擎快取（**無法確定** Flowable 6.8.1 是否回傳共享實例）。且 `convertToXML` 再序列化會流失註解與非 Flowable 擴充元素 → BpmnEditor 讀回後重新部署有 round-trip 資料遺失。
- **全模組無 `@ControllerAdvice`／`@ExceptionHandler`** → 所有 Flowable 例外變成裸 500。併發 complete 同一任務時樂觀鎖讓第二人收到 500 而非 409；重複點擊也是 500。
- `actuator` 的 `show-details: always` 在無認證且 8080/8081 直接 publish 到主機的情況下，會回傳 DB/RabbitMQ/Redis 各 component 狀態與版本字串。（`env`/`heapdump` 未暴露，這部分沒問題。）
- `form-service` 的 `GET /api/forms/health` 與 `GET /api/forms/{formKey}` 共存 → **formKey 名為 `health` 的表單永遠取不到**。

---

## 建議的處理順序

1. **P0-1 附件 path traversal + Dockerfile 加 `USER`** —— 唯一有明確 RCE 路徑的項目
2. **P0-2 (a) `FlowableConfig.setBeans()`** —— 一行，讓白名單在執行期真的成立（記得含 `notifyTaskListener`）
3. **P0-4 mass-assignment** —— 四個端點加 DTO 或 `id` 設 null
4. **P0-3 稽核鏈結驗證 + `createdAt` 順序** —— 否則稽核報告不可信
5. **P1-10 三個 `RestClient` 設 timeout + docker profile 的 org/perm URL** —— 避免自我死鎖與執行緒耗盡
6. **P1-2 `.singleResult()` 改 `.list()`** —— 避免畫個平行閘道就全站 500
7. **P1-8 / P1-9 授權判定**（精確比對、distinct + 排除本人）
8. 之後才是 lint 的 AST 重寫、快取架構、form-service 改版路徑

**但這一切的前提仍是 Stage 2 的測試網** —— 上述每一項修復都會動到簽核路徑，沒有迴歸網就是拆一個補一個。
