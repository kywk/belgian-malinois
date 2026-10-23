# Greyhound／UniForm 申請流程整合檢視

狀態：申請主表與鎖版已交付；OptionsProvider 第一批已實作，驗收證據與範圍見文末。

## 需求與已確認決策

使用者於 2026-10-24 明確指出：BPMN 申請流程必須對應表單；流程設計者同時設計申請表，發布後申請人才能填寫送件。

同日確認版本政策：**流程不必重新發布；新申請自動使用最新已發布表單，既有案件維持原表單版本。**

整合單位應是「可供申請的流程＋主申請表」，提供連續的設計、預覽與發布操作。申請表是流程的必要內容，不是設計完 BPMN 後再手動輸入一個外部資源代碼。

- 每個申請流程以穩定的 `formKey` 指向一份主申請表；一般表單改版不要求重新部署 BPMN。
- 案件鎖定送件時實際通過驗證的表單版本，後續審核與補件沿用該版本。
- 審核與補件使用同一張主表，由節點 Access Profile 決定唯讀／可編輯欄位，不另做 review 表單。
- 技術子流程、外部系統入口是否需要主表，應依各入口用途處理，不將「申請流程必須有表單」直接套到所有 BPMN。
- 一份表單是否允許跨流程共用尚未另定；本批不新增全域一對一限制。

## 查核基準與證據

| 來源 | 基準 |
|---|---|
| Greyhound | `316598c`；目前 SDK gitlink 為 `89708ef` |
| UniForm 最新工作區 | `67f6047`；規格權威為該提交的 `spec/`，搭配 `agent-kit/` 與實作核對 |
| UniForm 已記錄內部交付 | `team-submodule-20261022` 的來源為 `7caa6b3`，不等同最新 HEAD；未把交付紀錄當成最新 HEAD 全量驗收證據 |

本次為兩個獨立 worktree 的原生 subagent 只讀查核與主代理交叉核對。沒有更新 SDK pin、應用程式、資料庫或測試；既有修改保留。文件驗收只檢查來源、連結與差異，不沿用上一批測試數宣稱本方案已驗收。

## 現有實作與缺口

| 項目 | 已查證事實 | 影響與調整 |
|---|---|---|
| 設計入口 | [FormProps](../../bpm-frontend/src/bpmn/FormProps.js) 要求手輸已發布 formKey；[FlowablePropertiesProvider](../../bpm-frontend/src/bpmn/FlowablePropertiesProvider.js) 已有 StartEvent 設定入口 | 缺的是選取／建立／編輯申請表與返回流程的操作，不是缺少 StartEvent 面板 |
| 草稿與發布 | [BpmnEditor](../../bpm-frontend/src/views/BpmnEditor.vue) 草稿存在單一 localStorage key；[FormEditor](../../bpm-frontend/src/views/FormEditor.vue) 分開儲存與發布，`handlePublish` 不先處理 dirty 草稿 | 使用者可能發布上一份已儲存內容；必須辨識尚未保存及 SDK pending／invalid 狀態 |
| 部署驗證 | [BpmnLintService](../../bpm-core/src/main/java/com/bpm/core/lint/BpmnLintService.java) 353–410 行只在 UserTask 分支核對表單 | 申請流程還需檢查起始表單存在、可用，以及節點表單／欄位引用符合主表 |
| 送件契約 | [ProcessController](../../bpm-core/src/main/java/com/bpm/core/controller/ProcessController.java) 313–332 行容許省略 formData，亦由 request id/key 解析定義；371–397 行未核對起始 formKey，按流程 key 啟動後再鎖版 | 申請入口必須以伺服器解析出的流程／主表綁定驗證，不接受任意表單替代；UUID 查詢還須核對 published |
| 版本政策 | [FormVersionLocker](../../bpm-core/src/main/java/com/bpm/core/service/FormVersionLocker.java) 在啟動後再查最新；[FormContextResolver](../../bpm-core/src/main/java/com/bpm/core/uniform/FormContextResolver.java) 缺鎖版資料時會查最新 | 方向符合「新案用最新」，但填寫、驗證、鎖版間有競爭窗口，必須鎖同一個已驗證版本 |
| 可沿用能力 | [FormService](../../bpm-core/src/main/java/com/bpm/core/form/service/FormService.java) 已禁止改寫 published，提供新版 draft；[請假流程](../../bpmn-definitions/leave-approval.bpmn20.xml) 已共用申請表並區分審核與補件權限 | 保留現有表單版本、Envelope、Access Resolver 與案件資料，不重做通用表單平台 |

## 目標操作流程

```text
建立／編輯申請流程
  → 在同一工作區設計主申請表（或選取已有表單）
  → 設計 BPMN、指定審核人與各節點欄位權限
  → 預覽填寫／審核／補件，儲存並檢查可發布性
  → 首次發布表單及部署流程，開放申請
  → 之後表單獨立改版發布，新申請採用最新版
```

宿主提供「流程、申請表、節點權限、預覽／發布」分頁及狀態；表單內容繼續由 `UfDesigner` 編輯。設計者不必記憶 formKey，也不必在彼此無關的管理頁間手動配對。

首次發布時，表單發布成功但流程部署失敗，應明確保留「流程尚未開放申請」狀態並可重試；不刪除已發布表單、不宣稱多資料庫操作天然原子。優先沿用現有資料與部署入口，不先新增通用發布框架。

### 最新表單與送件版本

以下為依使用者版本政策推導的施工建議：

1. 開啟申請：伺服器解析可用流程與穩定 formKey，載入最新 published，回傳確切流程定義 ID、表單定義 ID／version 與 Envelope。
2. 送件：重新授權、解析相同流程綁定，確認 request 版本確實是該 formKey 的最新 published，而非接受任意 definitionId。
3. 填寫途中改版：回明確的版本衝突（建議 409），保留本機填寫內容並提示重載／比對；不得靜默套用新版 schema，也不得自動丟棄不再存在的答案。
4. 驗證成功：以同一次解析的流程定義 ID 啟動，將同一份已驗證 form version 寫入案件；不可再查一次 latest 決定鎖版。
5. 既有案件：一律載入鎖定版本。新增受此契約管理的案件若缺鎖定資料，應拒絕處理而非靜默跟隨 latest；歷史無鎖版案件另列修復策略。

一般表單改版可獨立發布；但移除／變更 BPMN 或 `uniform:access` 明確引用的欄位，仍須先修正依賴，否則可能讓既有部署無法受理新案。發布檢查須列清楚可檢查的依賴範圍，不能聲稱只比對 formKey 就已證明所有運算式相容。最新版本檢查與送件的線性化時點、交易／鎖策略於後端批次落實。

## UniForm 最新契約對整合的影響

以下路徑均指 **UniForm@67f6047**，不能用 Greyhound 舊 vendor checkout 的同名文件代替。

| 最新契約／實作 | Greyhound 的責任 |
|---|---|
| `docs/architecture/designer-studio.md`：SDK 提供 DSL 編輯，宿主負責草稿 revision、正式版本、發布及業務交易 | 綁定、設計工作區與發布狀態放在 Greyhound；不期待 SDK 自動提供流程管理 |
| `packages/vue/src/designer.ts`：emit modelValue／pending／invalid；沒有公開 selection event 或 slot | 保存／發布必須處理 pending／invalid；不能假設已有動態來源擴充面板 API |
| `packages/vue/src/types.ts`：沒有 options.load adapter；native 選項 widget 遇 optionsSource 不會自行讀網路 | 動態選項讀取需要宿主 widget／讀取 adapter；舊派工 prompt 所寫 options.load 不可視為已存在 API |
| `spec/validate/service-execution.md`：`ServiceValidation.validateWithServicesV1` 執行真正 resolve；`validateV1` 僅純驗證 | 接受選項值前必須由後端重載可信定義、provider registry 與授權 context，不能採 client 自稱有效或傳入 labels |
| 同一規格：dependsOn 不自動產生 params，list 為宿主讀取接點；privateLabels 不直接公開 | 來源設定、查詢及送件驗證分工；成功保存後才保存授權的標籤快照，歷史顯示不依賴即時 list |
| `spec/types/choice-editing.md`：select 僅單選，multiple=true 已移除；複選用 checkbox | 升版前盤點舊 DSL 與自訂 widgets，不可截取舊陣列首項或只換套件就宣稱相容 |
| `spec/richtext/markdown/semantics.md`：新 authoring 為 RAW Markdown，core3／richtext3、content2 | 舊 P5「直接接 uniform-tiptap」需重寫；AST v2 相容與新表單 authoring 分開處理 |

服務驗證契約在舊 pin 已存在，不能把 Greyhound 尚未接入的能力說成 SDK 最新才增加。是否升至最新 HEAD 與選項接線分開驗收；採固定 SHA，TS／Java／spec／manifest 一致，不追浮動 main。SDK 全量 persistence builder 是可選組裝，不作為本批前置。

## P5 OptionsProvider 修訂範圍

1. **來源清冊**：宿主註冊可用 provider 與參數規格；設計者選來源名稱，DSL 僅保存 optionsSource 引用，不接受任意 URL／script／SQL。
2. **設計器接線**：短期可由宿主另設來源面板，依穩定 node.id 選欄位、修改候選 DSL；套用前處理 pending／invalid 與未送出的局部草稿。若要與 SDK 選題同步，需先在 UniForm 明訂 selection／slot 契約，不讀取私有狀態。
3. **填寫接線**：宿主 widget 透過已授權的 form／field 讀取入口載入選項；伺服器由可信定義決定 provider。輸入相依值依欄位型別及權限核對；認證身分與業務 context 不從瀏覽器宣稱值取得。處理取消、過期回應與相依變更，不讓舊回應覆蓋新選項。
4. **送件接線**：以同一份可信綁定執行 resolve，確認送來的值仍被允許；候選 list 與前端驗證都不是授權證據。既有 `FormSubmissionService` 的 submitted/context 混用與空 behavior registry 必須一起收斂。
5. **標籤與失敗**：伺服器核准並成功保存後保留顯示快照。公開 `$labels` 只涵蓋契約允許的頂層 scalar；多選／repeater 標籤保存先明訂宿主格式，不自行發明 SDK 格式。來源失敗顯示可重試錯誤，不默默改用靜態清單放行非法值。
6. **舊端點**：`GET /api/forms/options` 的 URL 代理不作新版 DSL 的整合介面；確認剩餘呼叫者後再決定移除或相容包裝，不能只更名就視為 provider resolve 已完成。

## 實作順序與驗收

| 批次 | 可驗收成果 | 併行安排 |
|---|---|---|
| A | 對齊 SDK 支援矩陣；固定申請主表、最新版本與版本衝突契約 | 主代理先凍結接口，避免兩端各自推定 |
| B | 申請入口必填／一致性檢查、同一版本驗證與鎖定；流程工作區可建立或選取並編輯主表 | 後端版本／送件與前端工作區分別獨立 worktree |
| C | OptionsProvider：來源設定→載入→後端 resolve→保存標籤 | 接口固定後，後端 registry／resolve 與前端 panel／widget 併行 |
| D | 真實操作與必要回歸、增量交接、commit；不 push | 主代理整合驗收，build lease 串行 |

驗收至少涵蓋：缺主表不能開放申請；不能省略／偷換／提交 draft 表單；流程不重發即可取得新表單；填寫中改版明確衝突；舊案件仍用原版；審核唯讀與補件範圍不變；動態選項成功／失敗／過期回應／非法值拒絕；標籤快照授權與保存一致。沿用既有檢查及手動驗收，不因任務卡列案例就新增未授權測試。

本文件取代 2026-10-13 P5 prompt 中與此處衝突的整合假設；歷史交接保留，實作及驗收狀態以文末增量紀錄為準。

## 2026-10-24 第一批實作與驗收

- 起始表單回傳 `applicationContext: {processDefinitionId, formDefinitionId, formKey, formVersion}`；申請 POST 必須帶流程 key、確切 ID 與 `formData: {formDefinitionId, formVersion, dataJson}`。表單識別必須是綁定主表的已發布 UUID，舊版 formKey／variables-only 申請格式回 400；無主表的技術流程保留原入口。
- 單次最新已發布查詢是送件版本的線性化點；同一不可變定義用於驗證、資料列與啟動前 `_formVersions`。查詢後才完成的並行發布視為後續改版，不重新解析最新版。流程依解析出的 ID 啟動；新申請以 `_formVersionPolicy=application-v1` 標記，缺鎖拒絕。舊案件的相容回退不代表完成歷史資料補正。
- BPMN 設計頁內嵌主表編輯，發布前儲存、正式版唯讀並可建立新版草稿；發布表單與部署流程的狀態分開。409 保留逐次答案快照，重載僅帶入同 key／type 的答案並要求確認。
- 獨立審查：Standards 無確認缺失；Spec 發現事件觸發入口誤列與多次409答案保留問題，已修正。
- Maven 完整首輪 1,677 案例：1,675 通過，2 個舊契約 fixture 失敗；更新該兩組既有案例後 `mvn -Dtest=ProcessStartNotFoundTest,StartProcessWithFormDataTest verify` 12/12 通過。未新增測試案例。
- 前端 `npm test` 25 檔、259 案例通過；`npm run build` 通過，既有大 bundle 警告仍在。瀏覽器確認建立／發布主表並返回 BPMN 後綁定保留。
- 本機 API 驗收通過：缺表單／錯誤綁定拒絕、舊版本409、表單 v2 發布後新案使用 v2（不重部署）、既有案維持 v1。跨資料庫仍沿用補償，未宣稱 XA 或 crash-safe exactly-once。


## 2026-10-24 OptionsProvider 第一批

- 固定來源 `org.myDepartmentMembers`（本人部門成員），僅支援 scalar `select`／`radio`；`params`、`dependsOn` 只能省略或為空。來源以登入者查部門與成員，只回 `{value,label}`，超過 500 人或來源不可用回 503，不退回靜態清單。本機組織服務目前為 mock，正式組織服務接入另行驗收。
- `GET /api/forms/option-providers` 提供設計清冊；`POST /api/process-definitions/{key}/start-form/options` 以 `fieldId`＋確切 `applicationContext` 查詢，版本過期回 409；`POST /api/tasks/{taskId}/form/options` 沿用案件讀取授權及鎖版，僅提供可編輯的公開欄位。Provider 取自可信定義，不接受瀏覽器指定來源或任意 URL。
- `FormSubmissionService` 呼叫 SDK `validateWithServicesV1` 真正 resolve，重讀來源確認提交值仍有效；成功才合併可編輯輸入與既有 canonical，保存頂層 scalar `$labels`。拒絕 client `$labels`，不把 metadata 放進流程變數。唯讀動態欄位只能回傳原值，保留既有標籤、不以審核者部門重驗歷史選擇。
- 設計器新增依 node.id 選欄位的來源面板，遵守 SDK pending／invalid；填寫元件處理取消、過期回應及重試。`UniformForm` 僅在呈現副本中把支援的來源換成已授權選項供 SDK 驗證，正式 DSL 保留來源引用；未完成或失敗仍阻擋送件。唯讀／檢視使用保存的標籤。
- 本批沒有更新 SDK pin，也未移除舊 URL 代理端點。動態複選、repeater、相依參數、其他 provider，以及動態來源混用 file／media:image 尚未支援；定義入口會拒絕未支援組合。後續接可信檔案 verifier 再開放混用。
- 驗收：完整 `mvn verify` 1,677/1,677 通過；前端 `npm test` 25 檔、259/259 通過，正式建置通過（既有大 bundle 警告）。既有 acceptance 7/7 通過。沒有新增測試案例。
- 手動 API 通過：部門範圍、非法值與偽造標籤400、過期版本409、未知欄位及無權案件404、無可用組織來源503、editable 重送、跨部門 readonly 原值保留及竄改拒絕。瀏覽器通過來源切換與新版發布、選項載入、實際送件、唯讀姓名快照顯示。
- 獨立審查：Standards 0、Spec 0 個確認缺失。未更新 SDK pin、未 push；原有腳本與 submodule mode 變更保留。
