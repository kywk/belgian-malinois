# docs/plan — 待開工計畫文件

本目錄收錄**尚未執行**的計畫與決策文件。已完成的工作請移至 `docs/history/<日期>-<主題>/`，與既有慣例一致。

## 文件索引

> 📌 **先讀這兩份**
>
> 1. [`2026-09-28-handover.md`](2026-09-28-handover.md) —— 重構交接。分支 `feature/tech-debt-remediation`
>    有 11 個 commit **未經編譯驗證**（當時環境無 javac/maven/docker），回來後第一件事是跑 `mvn verify`。
> 2. [`2026-09-28-security-audit.md`](2026-09-28-security-audit.md) —— 全系統安全審查，**40+ 項發現**。
>    其中一項有明確 RCE 路徑（附件上傳 path traversal + 容器以 root 執行），另有兩項推翻了
>    CLAUDE.md 原本的結論（EL 白名單、稽核不可篡改性實際上都不成立）。

| 文件 | 內容 | 狀態 | 剩餘人日 |
|---|---|---|---|
| [`2026-09-28-handover.md`](2026-09-28-handover.md) | 重構交接：commit 清單、待決策事項、審查發現 | ✅ 已驗證並併入 main（2026-09-28～29） | — |
| [`2026-09-28-security-audit.md`](2026-09-28-security-audit.md) | 全系統安全與正確性審查（4 個 reviewer，40+ 項） | P0～P2 已修（第八節 #93～#95、#100）；文末「其他功能缺失」殘餘待估 | 待估 |
| [`2026-09-28-springboot4-upgrade.md`](2026-09-28-springboot4-upgrade.md) | Spring Boot 3.5 → 4.1.1 + Flowable 6.8.1 → 8.0.x 分階段升級 | Stage 0–4 ✅／Stage 5 起待開工 | 8 |
| [`2026-09-28-adr-001-form-service-consolidation.md`](2026-09-28-adr-001-form-service-consolidation.md) | form-service 併入 bpm-core 的決策紀錄 | ✅ 已完成（＝升級 Stage 3，2026-09-28） | 0 |
| [`2026-09-28-remediation-backlog.md`](2026-09-28-remediation-backlog.md) | 工程品質與安全性改進項（R-01 ~ R-25） | R-01／02／03／05／06／09／10／11／12／13／18／19／20／23 ✅；R-04 步驟 1 ✅（剩 0.5）；R-07／08／14～17／21／22／24／25 待開工 | 10.5 |

剩餘約 **19.5 人日**（升級 Stage 5–6 共 8 ＋ remediation 未完成項約 11.5；不含 security-audit 文末殘餘待估與 `docs/backend-development-backlog.md` 的功能待辦 —— 後者 2026-10-02 統計為 94 項／剩餘估時上限 ~96 人天）。ADR-001 的 3 人日已含在升級計畫的 22 人日內，且已完成，勿重複計算。

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

**下一個開工點：升級 Stage 5（Boot 4.1.1 + Flowable 8.0.x）。Boot 與 Flowable 必須同步跳，不要只升其中一個。**

## 三個必須知道的結論

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
