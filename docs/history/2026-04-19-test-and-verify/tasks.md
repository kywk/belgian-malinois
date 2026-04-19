# 驗收測試任務追蹤

> 建立時間：2026-04-19

## 任務狀態

| # | 任務 | 狀態 | 說明 |
|---|------|------|------|
| 1 | 強化 MockOrgController | ⬜ 待執行 | 加入 user001~user005, mgr001, mgr002, dir001 真實資料 |
| 2 | 強化 MockPermController | ⬜ 待執行 | 依 permCode 回傳正確用戶清單 |
| 3 | 建立 docker-compose.dev.yml | ⬜ 待執行 | 加入 MailHog email mock |
| 4 | 建立 seed-data.sh | ⬜ 待執行 | 部署 BPMN、建立表單 mock data |
| 5 | 建立 acceptance-test.sh | ⬜ 待執行 | 完整簽核流程自動化測試 |
| 6 | 建立 README-testing.md | ⬜ 待執行 | 驗收測試操作說明 |

## 修改檔案清單

| 檔案 | 類型 | 說明 |
|------|------|------|
| `bpm-core/src/main/java/com/bpm/core/controller/MockOrgController.java` | 修改 | 強化測試用戶資料 |
| `bpm-core/src/main/java/com/bpm/core/controller/MockPermController.java` | 修改 | 強化權限資料 |
| `docker-compose.dev.yml` | 新增 | 含 MailHog 的開發環境 |
| `scripts/seed-data.sh` | 新增 | 初始化測試資料 |
| `scripts/acceptance-test.sh` | 新增 | 自動化驗收測試 |
| `docs/README-testing.md` | 新增 | 測試操作說明 |

## 測試案例清單

### 請假流程 (leave-approval)
- [x] TC-L01: user001 申請請假 → mgr001 同意 → 流程結案
- [x] TC-L02: user001 申請請假 → mgr001 退件 → 流程退回
- [x] TC-L03: user001 申請請假 → mgr001 拒絕 → 流程終止
- [x] TC-L04: user001 申請請假 → mgr001 加批註 → 同意

### 採購流程 (purchase-approval)
- [x] TC-P01: user001 申請採購 → mgr001 同意 → mgr001 財務認領同意 → 結案
- [x] TC-P02: user001 申請採購 → mgr001 退件 → 流程退回
- [x] TC-P03: user001 申請採購 → mgr001 同意 → dir001 財務拒絕 → 終止

### 進階功能
- [ ] TC-A01: 加簽功能（mgr001 加簽 dir001）
- [ ] TC-A02: 批註功能（多人批註）
- [x] TC-A03: 稽核 log 完整性驗證
- [ ] TC-A04: 外部系統 API 發起流程
