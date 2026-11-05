# BPM 內勤與 agent BFF 契約 v1

實作進度（2026-11-05）：內勤 SSO／session／組織與 AuthZ 消費端已接線，見 [內勤整合指南](../../../api/staff-sso-integration.md)。本目錄的整體 PLANNED 標記仍涵蓋尚未實作的 external v1／agent BFF 案件路由；正式來源設定與身分橋接服務仍需補齊。
2026-11-05 第 1 項固定欄位、身分與授權規則、錯誤碼和驗收案例；第 2 項的內勤實作進度以上述指南為準。external v1 新路由仍不可呼叫；尚未建立正式帳號、配置正式 grant 或切換正式環境。

上位邊界見 [架構規劃](../../2026-11-05-staff-console-agent-bff-integration.md)：BPM 後台只供內勤；外勤經 agent BFF；業務服務保留案件、快照與 outbox。本文取代上位文件中未定的欄位名稱與權限映射，但不改變服務責任。

| 文件 | 用途 |
|---|---|
| [身分與 AuthZ](identity-authz.md) | 內勤 canonical ID、有效資格、組織主體、權限與來源故障策略 |
| [案件 API](case-api.md) | 公開查詢、私有投遞、BPM v1 啟動／對帳、DTO 與防重 |
| [錯誤與驗收](errors-acceptance.md) | HTTP／錯誤碼／重試決策，以及後續必跑驗收 |
| [JSON Schema](schemas.json) | 封閉 DTO 形狀與型別，draft-07 |
| [範例集合](examples.json) | 合成正反例；不含真實憑證或個資 |
| [權限與錯誤目錄](catalog.json) | 可供測試引用的固定代碼與映射 |

## 固定決定

1. 內勤 BPM userId 使用權威人員資料的原樣 USERID；AuthZ 個人主體為 `staff-` 加 USERID 的小寫。外勤 agentCode、服務 systemId 不會成為內勤 principal。
2. BPM 功能授權 system_code 固定 `bpm`，四段權限碼在 catalog 中逐筆定義。權限管理、表單設計、流程部署、稽核和人工核決分離；不建立全能角色。
3. agent→BPM 使用 `systemId=agent-bff` 的專用 API key。個人 actor 只供追溯和業務授權脈絡，不用來繞過 BPM 任務持有規則。
4. 新 BPM wire 路由使用 `/api/external/v1/`；現有 `/api/external/process-instances` 不修改契約，也不作新功能的降級入口。
5. 同 systemId＋requestId 的相同請求只建立一次；同案件／submission／workflowRole 再換 requestId 也不能重複建案。外部啟動明確鎖定流程與表單版本。
6. agent 不提供通用人工簽核 proxy，也不接受瀏覽器指定 processInstanceId、受理人、候選群組或 actor。業務送件沿用 owning service 的專屬 API。

## 相容性與首批範圍

首批合成驗收流程固定為 `bff-contract-review`，workflowCode=`contract.staff-review`，domain=`contract-fixture`，actor type=`AGENT`。這是測試定義，**不得部署為正式業務流程**。正式流程用相同契約綁定經核准的流程／表單版本。

既有 recruitment 的 AGENT 管理入口、TALENT 本人入口與 prepare 語意保持不變。v1 的外勤 actor 只接受 AGENT；TALENT 不得轉成 AGENT。recruitment 的本人正式 BPM adapter 仍待獨立的 TALENT 契約擴充，不因本文件完成便啟用。recruitment 可先接「AGENT 有權閱讀的審核摘要」；未被業務授權的代本人送件繼續拒絕。

既有後台 API 的成功 DTO 保持原樣。新增 `GET /api/me/session` 供正式內勤 session 初始化；原 `GET /api/me/permissions` 保留舊 DTO，正式模式的資料來源改為已驗證 AuthZ 結果，不再讀 JWT roles 授權。細節見身分文件。

## 尚待環境綁定

以下是部署資料，不是未定的 API 語意：SAML issuer/audience/kid 與公開金鑰、BPM callback origin、有效內勤來源、AuthZ URL／讀取服務帳號、agent 私有入口服務憑證、正式流程／表單 ID、業務 actor 資格來源。缺任何必填綁定時相關功能不可啟用，回明確錯誤；不猜預設值、不回退 mock。

## 驗證方式

在 repository 根目錄執行：

```sh
node scripts/validate-integration-contract.cjs
git diff --check
```

驗證器使用現有 `bpm-frontend/node_modules/ajv`，不新增套件。它檢查 schema、正反例與代碼引用；**不執行 HTTP、不驗證授權實作或交易原子性**。行為驗收見案例表，必須在後續實作時另外完成。尚未安裝前端依賴時，先依專案 README 準備 UniForm 並執行 npm ci。

schema 的 `utc-date-time` 限定真實 UTC 日期時間，格式為 `YYYY-MM-DDTHH:mm:ssZ` 或三位毫秒 `.SSSZ`；`json-object-string` 僅驗證可 parse 成 JSON object。重複 JSON key、來源真實性、跨欄位時效、防重 hash 與 UTF-8 body 位元組上限須在服務驗收另測，不能只靠 schema。

2026-11-05 本輪結果：26 個 schema 編譯、47 組有效與 32 組無效範例、8 個權限碼、6 個角色樣板、25 個錯誤碼、15 個相對連結及 8 個文件 JSON 區塊通過檢查。43 項行為案例尚未執行；新增路由仍待實作。未修改服務程式、權限資料或部署設定，未 commit／push。
