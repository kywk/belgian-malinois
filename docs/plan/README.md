# docs/plan — 待開工計畫文件

本目錄收錄**尚未執行**的計畫與決策文件。已完成的工作請移至 `docs/history/<日期>-<主題>/`，與既有慣例一致。

## 文件索引

> 📌 **先讀這兩份**
>
> 1. [`2026-09-28-handover.md`](2026-09-28-handover.md) —— 重構交接。✅ **已驗證並併入 main（2026-09-28～29）**；僅供回溯。
> 2. [`2026-09-28-security-audit.md`](2026-09-28-security-audit.md) —— 全系統安全審查，**40+ 項發現；P0～P2 已修**。
>    文末「其他功能缺失」殘餘見該檔與 `2026-09-28-remediation-backlog.md`。
>
> ⚠️ **新接手的人請先讀 [`docs/handoff/README.md`](../handoff/README.md)**（交接索引＋未完成總表）與最新的
> [`2026-10-04-round21-handoff.md`](../handoff/2026-10-04-round21-handoff.md)；本目錄的文件是「計畫／審查」的原始記錄。

| 文件 | 內容 | 狀態 | 剩餘人日 |
|---|---|---|---|
| [`2026-09-28-handover.md`](2026-09-28-handover.md) | 重構交接：commit 清單、待決策事項、審查發現 | ✅ 已驗證並併入 main（2026-09-28～29） | — |
| [`2026-09-28-security-audit.md`](2026-09-28-security-audit.md) | 全系統安全與正確性審查（4 個 reviewer，40+ 項） | P0～P2 已修；文末「其他功能缺失」殘餘待估 | 待估 |
| [`2026-09-28-springboot4-upgrade.md`](2026-09-28-springboot4-upgrade.md) | Spring Boot 3.5 → 4.1.1 + Flowable 6.8.1 → 8.0.x 分階段升級 | ✅ **Stage 0–6 全部完成（2026-10-03，#70 結案）** | 0 |
| [`2026-09-28-adr-001-form-service-consolidation.md`](2026-09-28-adr-001-form-service-consolidation.md) | form-service 併入 bpm-core 的決策紀錄 | ✅ 已完成（＝升級 Stage 3，2026-09-28） | 0 |
| [`2026-10-05-adr-002-bpmn-as-code.md`](2026-10-05-adr-002-bpmn-as-code.md) | R-08 選項 A：BPMN-as-code（repo 單一來源、`POST /api/deployments` 單一入口、classpath 退場） | 📝 **設計完成、待實作**（T1–T5 ~2.5 人日；IdP／維運前置不計） | **~2.5** |
| [`2026-09-28-remediation-backlog.md`](2026-09-28-remediation-backlog.md) | 工程品質與安全性改進項（R-01 ~ R-25） | R-01／02／03／05／06／09／10／11／12／13／14／17／18／19／20／23 ✅；R-04 步驟 1 ✅（剩 0.5）；**R-21／22／24／25 ✅（2026-10-05）**；**R-08 選項 A 設計完成（ADR-002）、實作待派**；R-07／15／16 待開工 | **~6.5** |

剩餘約 **6.5 人日**（全部在 remediation；R-08 依 ADR-002 上修至 ~2.5；升級已結案）。功能 backlog `docs/backend-development-backlog.md`
已於 **2026-10-04 結清：97 項 ✅ 97／🟡 0／⬜ 0**。security-audit 文末殘餘待估。
**交接入口：`docs/handoff/README.md`（索引＋未完成總表）＋ `docs/handoff/2026-10-04-next-agent-prompt.md`（派工 prompt）。**

## 已完成

**2026-09-28（1 人日）**

- **升級 Stage 0**：`bpm-core` / `form-service` 的 Spring Boot 3.5.13 → **3.5.16**（3.5 線最後一個 OSS 版本）
- **升級 Stage 1**：刪除 `audit-log-service/` 死碼並清理 8 處殘留引用
- **R-04 步驟 1**：`infra/mssql/entrypoint.sh` 密碼改讀環境變數 —— 解掉 prod 首次部署必然失敗的問題

**2026-09-28～29（後續輪次，詳見 `docs/backend-completed-items.md` 第八節）**

- **升級 Stage 2**：Flyway 接手三個 DB schema、Testcontainers 測試網（後端）
- **升級 Stage 3（＝ADR-001）**：form-service 併入 bpm-core
- **升級 Stage 4**：Flowable 6.8.1 → **7.2.0**（Boot 不動）
- **R-01**：Spring Security＋JWT 驗證＋信任閘道、授權矩陣預設 denyAll（R-18 阻斷項隨之解除）
- **R-02／R-03**：前端集中 HTTP instance／路由角色守衛；**R-06 前端**：Vitest 測試框架
- **R-09／R-10／R-20**：外部 API 精確比對、Redis 改用 `SCAN`、`initiator` 由 server 決定
- **R-11／R-12／R-13**：根目錄重複 backlog 刪除、`bpm-frontend/dist/` 移出版控、README-testing 更正

**2026-10-05（remediation B 批次，3 人日）**

- **R-21／R-22**：外部系統授權設定寫入端驗證（400 零副作用、UI 多選）＋反向代理後真實 client IP
- **R-24／R-25**：擁有權查詢與授權分離（`_externalSystemId`）＋API key v2（HMAC、寬限期、失敗節流）

**2026-10-05（R-08 設計票，0.5 人日）**

- **ADR-002（選項 A 拍板）**：repo `bpmn-definitions/` 單一來源、`POST /api/deployments` 單一部署入口、
  runtime 目錄為帳本；CI 以服務帳號 JWT 部署、失敗大聲紅；classpath 退場「先關設定、再移檔案」；
  測試以 Maven `testResources` 映射零複本。實作票 T1–T5 約 2.5 人日（未決 7 條見 ADR-002 §8）。

**下一個開工點：R-08 實作（T1 無相依可先派；T2 需 ADR-002 §8-Q1／Q2 拍板與 R-07 runner 前提）、
remediation 其餘（R-04 步驟 2–5／R-07／R-15／R-16）與部署前檢查清單（round22 §5）。
CI/CD 平台決策已定：保留 GitLab CI。功能 backlog 已於 2026-10-04 全數結清（97/97）；
派工 prompt 見 `docs/handoff/2026-10-04-next-agent-prompt.md`（B 批次已勾消；C～E 仍有效、R-08 以 ADR-002 為準）。**

## 三個必須知道的結論

> ✅ **2026-10-03 更新**：以下三個結論均已落實 —— Boot **4.1.1**＋Flowable **8.0.0** 已上 `main`
> （升級 Stage 0–6 全部完成，#70 結案；見 `docs/handoff/2026-10-03-round15/16-handoff.md`）。
> 本節保留作為決策脈絡。

### 1. Spring Boot 3.5 已 EOL，升級是 P0 安全性阻斷項

3.5 於 **2026-06-30 結束 OSS 支援**，最後 OSS 版本為 **3.5.16**。此後 CVE 只修給付費商業支援。

👉 **已升到 3.5.16（Stage 0，2026-09-28）。但這只是止血** —— 3.5.16 之後的新 CVE 仍不會有 OSS 修補，真正的解法是走完 Boot 4 升級。

### 2. 升級 Boot 4 會強制 Flowable 跳兩個主版本

Flowable 8 才支援 Spring Boot 4；Flowable 7.2.0 在 Boot 4 上無法運作。所以是 **6.8.1 → 7.2.0（✅ 2026-09-28）→ 8.0.x（待開工）**。

好消息：Flowable 6→7 的移除項目（async history、Form Engine、Content Engine）本專案一項都沒用到，且引擎層無 DB schema 變更。關鍵作法**已落實：先在 Boot 3.5.16 上完成 Flowable 6→7（✅），再一起跳 Boot 4 + Flowable 8** —— 一次只換一個主版本。

### 3. 施工順序刻意讓每一步降低下一步的成本

```
刪死碼（縮小面積）→ 建測試網（安全前提）→ 整併 form-service（3 模組降 1）
→ Flowable 6→7（Boot 不動）→ Boot 4 + Flowable 8 → 認證授權（只做一次）
```

其中兩個順序決策值得說明：

- **測試網排在升級之前**。（當時）三個模組皆無 `src/test`，CI 的 `mvn verify` 是空門 —— 沒有測試就升級流程引擎，等於閉著眼睛換飛機引擎；測試網已於 Stage 2 補上。
- **form-service 整併排在升級之前**。沒有理由先把它升到 Boot 4 + Jackson 3，再把它刪掉。

完整順序見 [`2026-09-28-remediation-backlog.md` §施工順序](2026-09-28-remediation-backlog.md#施工順序含相依)。

## 兩個最高風險單點

1. **`org.flowable.engine.impl.persistence.entity.ExecutionEntity`**（`webhook/ProcessCompletedListener`）—— 全專案唯一的 Flowable internal API 使用。`.impl.` 套件不保證跨主版本穩定，是升級最可能爆的地方，請優先驗證。
2. **Flowable 8 的日期格式改為 ISO 8601 UTC** —— 唯一會外溢到前端的破壞性變更，影響 `ApprovalTimeline.vue`、`TaskInbox.vue`、`MyApplications.vue`、`AuditLog.vue`。

## 撰寫慣例

- 語言：繁體中文，與 `docs/` 既有文件一致。
- 每份計畫必須包含：**動機、已查證的事實（附來源）、施工清單、驗收條件**。
- **明確區分「已查證」與「施工時需確認」** —— 升級計畫的 §8 就是為此而設。版本號與 API 相容性隨時間變動，不要把未驗證的推測寫成事實。
- 決策文件用 ADR 格式（背景／決策／理由／已評估的反對理由／結果），編號連續。
