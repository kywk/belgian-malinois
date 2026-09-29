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
| [`2026-09-28-handover.md`](2026-09-28-handover.md) | 重構交接：commit 清單、待決策事項、審查發現 | 待驗證 | — |
| [`2026-09-28-security-audit.md`](2026-09-28-security-audit.md) | 全系統安全與正確性審查（4 個 reviewer，40+ 項） | 待處理 | 待估 |
| [`2026-09-28-springboot4-upgrade.md`](2026-09-28-springboot4-upgrade.md) | Spring Boot 3.5 → 4.1.1 + Flowable 6.8.1 → 8.0.x 分階段升級 | Stage 0–1 ✅／Stage 2 起待開工 | 21 |
| [`2026-09-28-adr-001-form-service-consolidation.md`](2026-09-28-adr-001-form-service-consolidation.md) | form-service 併入 bpm-core 的決策紀錄 | 提議中（＝升級 Stage 3） | 3 |
| [`2026-09-28-remediation-backlog.md`](2026-09-28-remediation-backlog.md) | 工程品質與安全性改進項（R-01 ~ R-17） | R-04 步驟 1 ✅／其餘待開工 | 25.5 |

剩餘約 **49.5 人日**（不含 `docs/backend-development-backlog.md` 的 65 項功能待辦 / 125.5 人日）。ADR-001 的 3 人日已含在升級計畫的 22 人日內，勿重複計算。

## 已完成（2026-09-28，1 人日）

- **升級 Stage 0**：`bpm-core` / `form-service` 的 Spring Boot 3.5.13 → **3.5.16**（3.5 線最後一個 OSS 版本）
- **升級 Stage 1**：刪除 `audit-log-service/` 死碼並清理 8 處殘留引用
- **R-04 步驟 1**：`infra/mssql/entrypoint.sh` 密碼改讀環境變數 —— 解掉 prod 首次部署必然失敗的問題

**下一個開工點：Stage 2（測試安全網）。在此之前不要動 Flowable 或 Boot 版本。**

## 三個必須知道的結論

### 1. Spring Boot 3.5 已 EOL，升級是 P0 安全性阻斷項

3.5 於 **2026-06-30 結束 OSS 支援**，最後 OSS 版本為 **3.5.16**。此後 CVE 只修給付費商業支援。

👉 **已升到 3.5.16（Stage 0，2026-09-28）。但這只是止血** —— 3.5.16 之後的新 CVE 仍不會有 OSS 修補，真正的解法是走完 Boot 4 升級。

### 2. 升級 Boot 4 會強制 Flowable 跳兩個主版本

Flowable 8 才支援 Spring Boot 4；Flowable 7.2.0 在 Boot 4 上無法運作。所以是 **6.8.1 → 7.2.x → 8.0.x**。

好消息：Flowable 6→7 的移除項目（async history、Form Engine、Content Engine）本專案一項都沒用到，且引擎層無 DB schema 變更。關鍵作法是**先在 Boot 3.5.16 上完成 Flowable 6→7**，再一起跳 Boot 4 + Flowable 8 —— 一次只換一個主版本。

### 3. 施工順序刻意讓每一步降低下一步的成本

```
刪死碼（縮小面積）→ 建測試網（安全前提）→ 整併 form-service（3 模組降 1）
→ Flowable 6→7（Boot 不動）→ Boot 4 + Flowable 8 → 認證授權（只做一次）
```

其中兩個順序決策值得說明：

- **測試網排在升級之前**。三個模組目前皆無 `src/test`，CI 的 `mvn verify` 是空門。沒有測試就升級流程引擎，等於閉著眼睛換飛機引擎。
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
