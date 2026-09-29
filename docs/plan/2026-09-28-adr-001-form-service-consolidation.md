# ADR-001：form-service 整併進 bpm-core

**日期**：2026-09-28
**狀態**：✅ **已實施**（2026-09-28）
**決策者**：Bruce
**預估**：3 人日

---

## 實施結果（2026-09-28）

已完成。實際順序與決策時的設計一致（合併部署單元、保留 `bpm_form_db` 獨立）。

**施作順序**（刻意把守衛放在最前面）：

1. 先建 `DataSourceBindingTest` —— 第三個 persistence unit 會放大 `cecdbe4`
   那類「交易綁錯 EntityManagerFactory → 寫入靜默不落地」的風險。
   已驗證這個守衛不是空的：暫時移除 `@Qualifier` 後它立刻失敗。
2. 建 `FormDataSourceConfig` / `FormFlywayConfig`（每個注入點都寫明 `@Qualifier`）
3. 搬 7 個類別到 `com.bpm.core.form.*`；刪掉 4 個不需要搬的
   （`FormServiceApplication`、以及與 bpm-core 逐字重複的
   `JacksonAmqpConfig`／`HealthController`／`AuditEventPublisher`）
4. `FormVersionLocker` 與 `BpmnLintService` 改為 in-process 注入 `FormService`
   —— 這消除了本文 §2 描述的那個靜默失效
5. 表單稽核改走 in-process publisher —— 一併消除 P1-15 的 MQ 遺失窗口
6. `data.sql` 轉為 Flyway migration（`spring.sql.init` 只作用於主 DataSource）
7. 刪除 form-service 模組、nginx 兩條路由、compose 服務、CI pipeline、
   `cicd/envs/*` 的 `form.service.url`

**驗證**：bpm-core **143 → 149 個測試**全過；`seed-data.sh` 與
`acceptance-test.sh` 皆全綠（PASS 7 / FAIL 0）；表單中文完整、改版路徑正常、
表單稽核立即落地。

**行為差異（刻意記錄）**：表單 seed 從「每次啟動跑一次 MERGE」變成
「Flyway 只執行一次」。由於原本的 MERGE 只有 `WHEN NOT MATCHED INSERT`、
沒有 UPDATE 分支，既有列從來也不會被 `data.sql` 更新，
因此這個差異在實務上不改變任何結果。

---

## 背景

專案原始設計（`docs/bpm-platform-spec.md`）為三個微服務各持一個資料庫。實際程式碼量：

| 模組 | Java 檔 | 行數 | 佔比 |
|---|---|---|---|
| bpm-core | 61 | 3,529 | 89% |
| form-service | 11 | 434 | 11% |
| audit-log-service（已廢） | 9 | 418 | — |

前端 42 個檔。整個後端 3,963 行 Java。

form-service 只有 2 個 entity（`FormDefinition`、`FormData`）、8 個 REST 端點、434 行 CRUD 邏輯，卻背負一整套獨立服務的營運成本：獨立 JVM（約 200–300MB）、Docker image、CI pipeline、DB 連線池、RabbitMQ 連線、健康檢查、部署步驟，以及一份與 bpm-core 逐字重複的 `JacksonAmqpConfig`。

**前提**：本決策成立於「form-service 僅服務 BPM，不會有其他消費方」的前提上。若表單設計器將來要成為全公司共用能力，請重新評估 —— 但即使如此，下述「可逆性設計」也能讓抽出成本維持在設定變更層級。

## 決策

**將 form-service 併入 bpm-core 成為單一部署單元，但保留 `bpm_form_db` 資料庫分離。**

整併的是**部署單元**，不是**資料模型**。

## 理由

### 1. 先例已在，且這次理由更強

audit-log-service 在 2026-04-24 就是為同樣理由併入（`docs/history/2026-04-24-architecture-refactor/summary.md`，省 200–300MB RAM）。而 audit 尚有獨立的正當理由（append-only、3 年保存、可能被其他系統共用）；form-service 在上述前提下**一項都沒有**。

半途而廢的重構比不重構更糟：現在的架構既不是乾淨的微服務，也不是乾淨的單體。

### 2. 移除流程啟動路徑上的同步依賴 —— 這裡有一個真實的靜默錯誤

`bpm-core/src/main/java/com/bpm/core/service/FormVersionLocker.java:59-67`：

```java
private Integer resolveVersion(String formKey) {
    try {
        var resp = formClient.get().uri("/api/forms/{formKey}", formKey)
                .retrieve().body(Map.class);
        return resp != null ? (Integer) resp.get("version") : null;
    } catch (Exception e) {
        return null;          // ← 例外被吞掉
    }
}
```

form-service 不可用時例外被吞掉、回傳 `null`，`versions` map 保持為空，`lockVersions()` 的 `if (!versions.isEmpty())` 判斷不成立 → **`_formVersions` 完全不寫入，但流程照常啟動成功**。

該案件此後永遠走「最新版表單」，表單版本鎖定機制靜默失效 —— 這正是版本鎖定要防的事，而且**沒有任何錯誤訊息**。

改成同一 JVM 內直接注入 `FormService`，這個失敗模式從根本消失。`lint/BpmnLintService.java` 的 formKey 存在性檢查是第二個同樣的同步依賴。

### 3. 認證授權只需實作一次

最大的待辦（JWT / Sa-Token，backlog #62）目前必須做兩遍，或者讓 form-service 無條件信任 bpm-core 傳來的 header —— **而這個內部信任邊界從來沒有人設計過，也沒有任何程式碼在守它**。整併直接砍掉一半工作量與一個未定義的安全邊界。

### 4. 消除稽核寫入的不一致

form-service 走 RabbitMQ 發 audit 事件（`form/audit/AuditEventPublisher`），bpm-core 自己卻在 `@Async` 中直接呼叫 `AuditLogService.append()`。**這個歧異只因為服務被切開才存在。** 整併後統一走 in-process，同時少一個 MQ round-trip 與一個 DLQ 失敗面。

### 5. 減少 Spring Boot 4 升級面

見 `2026-09-28-springboot4-upgrade.md` Stage 3。沒有理由先把 form-service 升到 Boot 4 + Jackson 3，再把它刪掉。

### 6. nginx 設定簡化

可刪除 `^~ /api/forms` 與 `^~ /api/form-data` 兩條 prefix 規則 —— 這組規則本身就製造過一次 bug（`docs/history/2026-04-19-test-and-verify/walkthrough.md`）。

## 已評估的反對理由

| 顧慮 | 評估 |
|---|---|
| 失去獨立部署能力 | **今天不存在這個能力**。所有 deploy job 都是 `echo` 佔位，也沒有測試可擋。這是理論損失。 |
| 表單設計器迭代較快，耦合發版週期 | 成立，但在真實 CD 建立之前是空談。若未來成真，見「可逆性設計」。 |
| 故障半徑擴大 | 434 行 CRUD 的風險遠低於 bpm-core 已內建的 Flowable、webhook consumer、mail sender。 |
| 未來若表單成為共用能力需再拆 | **唯一需要設計應對的顧慮** → 見「可逆性設計」。 |

## 可逆性設計（本決策的關鍵條件）

```
bpm-core/src/main/java/com/bpm/core/form/
  ├─ config/FormDataSourceConfig.java   ← 第三個 persistence unit，指向 bpm_form_db
  ├─ model/       FormDefinition, FormData
  ├─ repository/  FormDefinitionRepository, FormDataRepository
  ├─ controller/  FormDefinitionController, FormDataController
  └─ service/     FormService            ← 唯一對外介面
```

三條硬性約束，違反即失去可逆性：

1. **`bpm_form_db` 不合併**。DB 分離的成本幾乎為零，卻讓未來抽出只是改設定而非重寫。
2. **不跨界直接引用 entity**。`com.bpm.core.form` 以外的程式碼只能透過 `FormService` 介面取用，不得直接 `import` `FormDefinition`／`FormData`，也不得在其他 entity 上建立指向它們的 JPA 關聯。
3. **URL 路徑逐字保留**。前端 `bpm-frontend/src/services/formApi.js` 的 8 個呼叫必須零修改。

雙 DataSource 模式已在 bpm-core 驗證過（`config/PrimaryDataSourceConfig.java` + `config/AuditDataSourceConfig.java`），加入第三個 persistence unit 是已知的低風險操作。

## ⚠️ 不要承諾的事

**同一個 JVM 不會讓 `bpm_core_db` 與 `bpm_form_db` 進入同一個 transaction。**

整併**不會**帶來表單送出與任務完成的原子性。不要為此引入 JTA。正確做法是接受最終一致，或在確認沒有合規理由分庫後再另案評估合併 schema（forms 沒有合規理由；audit 有：3 年保存 + append-only 觸發器，**audit 的分離必須維持**）。

## 施工清單

- [ ] 建立 `com.bpm.core.form` 套件與 `FormDataSourceConfig`（第三個 persistence unit，persistence unit 名稱 `form`）
- [ ] 搬移 11 個 Java 檔並改 package 宣告，移除重複的 `JacksonAmqpConfig`
- [ ] 搬移 `form-service/src/main/resources/data.sql` 的 4 筆種子表單
      → **改由 Flyway migration 承接**（見升級計畫 Stage 2），順帶解決 `data.sql` snake_case 欄位名 vs `@UniqueConstraint` camelCase 的不確定性
- [ ] `FormVersionLocker` 移除 `RestClient`，改注入 `FormService`；**移除吞例外的 catch，改為明確失敗或明確記錄**
- [ ] `BpmnLintService` 移除 `RestClient`，改注入 `FormService`
- [ ] `form/audit/AuditEventPublisher` 改走 in-process `AuditLogService.append()`，與 bpm-core 既有行為一致
- [ ] 刪除 `form-service/` 目錄、`pom.xml`、`Dockerfile`、`.project`、`.settings/`
- [ ] `docker-compose.yml` / `.dev.yml` / `.prod.yml` 移除 form-service service 定義
- [ ] `infra/nginx/nginx.conf` 移除兩條 `^~` prefix 規則
- [ ] CI：移除 `.github/workflows/form-service.yml` 與 `.gitlab-ci.yml` 的 form-service jobs
- [ ] `cicd/envs/*.yml` 移除 `form.service.url`
- [ ] `docs/bpm-platform-spec.md` 補架構決策紀錄（§19），`docs/README-testing.md` 更新服務端點表
- [ ] 更新 `CLAUDE.md` 架構表

## 驗收條件

- [ ] **前端零修改**通過完整回歸：表單設計器 CRUD、發布、封存、動態表單渲染、表單資料送出與回讀
- [ ] `/api/forms`、`/api/form-data` 全部 8 個端點回應與整併前逐欄位一致
- [ ] `bpm_form_db` schema 與資料未變動（用整併前的 DB 直接啟動新版，不需遷移）
- [ ] 表單三態生命週期規則不變：僅 `draft` 可編輯／刪除、僅 `published` 可封存、`publish()` 複製新版本至 `maxVersion+1`
- [ ] 表單版本鎖定生效：新流程實例的 `_formVersions` 正確寫入；**form-service 不可用的失敗模式已不存在**
- [ ] `./scripts/seed-data.sh` 與 `./scripts/acceptance-test.sh` 全 PASS
- [ ] 後端可部署單元由 3 個降為 1 個；記錄實際 RAM 節省量（預期 200–300MB）

## 結果

後端部署單元：**3 → 1**。架構定位由「微服務」正名為**模組化單體**，符合實際程式碼量，也完成 2026-04 那次重構已經開始、只是沒做完的方向。
