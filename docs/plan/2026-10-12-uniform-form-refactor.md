# uni-form SDK 表單重構計畫（凍結版）

- 狀態：**進行中**（2026-10-12 開工；介面已凍結，見 §五）
- 動機：本專案表單 UI 與資料全面改用 uni-form SDK（使用者 2026-10-12 指示）。**專案尚在開發、無 legacy 資料**，可直接以 uni-form 的 DSL／Access Profile／Envelope 模型重建。
- 依據：uni-form 設計文件 §14.1（greyhound 試點）、ADR-23（BPMN `uniform:access`）；SDK spec 為唯一權威。
- 相關：SDK 以 git submodule 引入（`vendor/uni-form`），pin `89708ef`（uni-form main，2026-10-12）。

## 一、改造範圍

| 層 | 現在 | 目標 |
|---|---|---|
| 表單定義 | 自訂扁平 `fields[]` JSON（`schemaJson`） | uni-form DSL v1（`$uniform:"1.0"`）；review 表單整併回同一份 DSL，權限改由 Access Profile 表達 |
| 表單渲染 | `DynamicForm.vue`（`v-if` 鏈） | `@nanshan/uniform-vue` `UfRenderer`（Envelope 驅動）＋ Element Plus 本地 widgets |
| 表單設計器 | 自製 FormEditor（扁平欄位） | `UfDesigner`（v-model DSL）＋宿主存檔／發布（沿用 `/api/forms`） |
| 後端驗證 | `FormSchemaValidator`（薄驗證） | `uniform-core` `compileStatic` ＋ `SubmissionValidation.validateV1`（純驗證） |
| 回應格式 | `{formKey, schemaJson}`＋`variables` 裸資料 | 完整 v1 Envelope（`EnvelopeProjection.projectV1`；hidden 裁剪、`meta.states`、`schemaRef.hash`） |
| 欄位權限 | `mode`＋欄位 `readonly`／`editableOnRevision`＋任務名稱判斷 | Access Profile：BPMN `uniform:access` extensionElements（ADR-23），`hidden < readonly < editable` |
| 值格式 | `dateRange` 存 `"起~訖"` 字串 | canonical：`dateRange` = `{start,end}`；amount 用 `number`（無幣別需求） |
| SDK 導入 | — | git submodule `vendor/uni-form`（pin 89708ef）；npm tarball 於 `vendor/uni-form/dist/npm/`；Java jar 由該 SHA `mvn install` |

## 二、已查證的事實

- greyhound 已是 **Boot 4.1.1 + Flowable 8.0.0 + Jackson 3**（`bpm-core/pom.xml`；升級 Stage 0–6 完成，2026-10-03）→ 取 `uniform-jackson3` + `uniform-spring-boot4-starter`，非 boot3 線。
- SDK 產物：`com.nanshan.uniform:*:0.1.0-SNAPSHOT` 已由 pinned submodule `mvn install -DskipTests` 進 `~/.m2`（2026-10-12）；npm tarball 4 份已於 `vendor/uni-form/dist/npm/`（`pack-npm.sh` 由 pinned SHA 產出）。
- 前端：vue 3.4.27、element-plus 2.7.3，符合 SDK peer（`vue ^3.4`、`element-plus ^2.7`）。npm 以 `file:../vendor/uni-form/dist/npm/*.tgz` ＋ overrides 安裝完成。
- SDK 入口：boot4 starter 自動配置 `UniformSpecResources`／`TypeRegistry`／`JsonSchemaValidator`／`UniformBffService`（`com.nanshan.uniform.boot4`）。`projectV1(input)` 輸入須過 `spec/envelope/full-v1.schema.json`；`fieldTypes: {}` 可接受（無條件時）。
- **uni-form main 迴歸（已修）**：`uniform-jackson3` 的 `FullV1FixtureSmokeTest` 遞迴載入 `types/core`，誤收 opt-in 的 `core/v2/richtext.manifest.json`（type 同為 `richtext`）→ `mvn install` 失敗。已於 uni-form 分支 `fix/jackson3-fullv1-smoke-v2` 修正（commit `43bf983`，全 reactor verify PASS）；**submodule pin 維持 main 89708ef，待 uni-form 接受後更新**。
- 種子 DSL 已以 SDK TS core 實測：`compileStatic`／`compileEffective` 通過、合法值 0 error、缺必填與未知欄位正確報錯。

## 三、決策

1. **不做 legacy 匯入**：無舊資料；seed 直接以 DSL 撰寫，設計器輸出即 DSL。舊格式 schemaJson 不再被任何路徑接受。
2. **review／補件表單合併**：`leave-review`／`purchase-review` 廢除；BPMN 一律指向 request formKey，權限以 `uniform:access` 表達（已落地，見 §四）。
3. **Access 缺省 fail-closed**：userTask 無 `uniform:access` → `mode=view, default=readonly`；startEvent 預設 `fill`／`editable`。
4. **補件提交走表單資料**：`PUT /api/tasks/{id}` complete 接受選用 `formData{dataJson}`；validateV1 通過後存 FormData 新列（版本化）＋推導流程變數，再完成任務（沿用「驗證零副作用、寫入最後、主交易回滾補償刪除」模式）。
5. **設計器 palette 收斂**：text／textarea／number／select／radio／checkbox／boolean／date／dateRange／divider／freeText；`file`／`richtext`／`orgSelector`／`amount`／`optionsSource` 延後（P5）。`GET /api/forms/options`（`optionsUrl` 代理）暫留、設計器不提供入口。
6. **前端驗證**用 `createCoreValidation`（僅提示）；後端永遠以 validateV1 重驗。
7. **FormData／FormDefinition 表保留**（宿主儲存責任不變）；`schemaJson` 內容換為 DSL；建立／更新／發布時以 `compileStatic` gate。
8. **`uniform:field` 空列的語意**（BPMN 面板與後端對齊）：缺 `key`、或只有 key 而無任何覆寫屬性的列（面板「新增欄位」的編輯中狀態）→ **略過該列** —— 它不是授權語句，略過不授予任何權限；但「有 key 又有屬性、值卻非法」（state 非三態／required 非布林）→ **整份 profile fail-closed**，不得猜測授權意圖。
9. **DSL `key` 必須等於 `formKey`**：`FormDefinitionValidator` 在建立／更新／發布以 400 擋下（Envelope 的 `schemaRef.key` 依賴這個等式；不一致會直到案件投影時才以 500 爆出，肇因與現場相隔很遠）。`/api/forms/preview` 不受此限（尚未落地為定義）。

## 四、已完成（P0 基底，2026-10-12）

- [x] submodule `vendor/uni-form`（pin 89708ef）＋ `.gitmodules`。
- [x] npm tarball 建置（pinned SHA）＋ `bpm-frontend/package.json`／lock（file deps ＋ overrides）。
- [x] V7 migration：兩張 DSL seed（leave-request／purchase-request），刪除 review 表與其 FormData。
- [x] BPMN `uniform:access`：`leave-approval`／`purchase-approval`／`countersign-review`（兩份副本 byte-identical；review=view/readonly，補件=fill/readonly＋reason editable）。
- [x] 本計畫文件（介面凍結）。
- [x] uni-form main 迴歸修正（fix 分支 `43bf983`，待回報／接受）。
- [x] 合併 BE／FE／BPMN 三分支；PM 收斂修正（`9b79c5c` 及後續）：DocumentDetail 任務動作 fail-closed、FormList→FormEditor 帶 `formKey+version`、`uniform:field` 空列語意（決策 8）、`key==formKey` gate（決策 9）、前端 dev proxy `VITE_API_PROXY`、formApi 移除已無端點的函式。
- [x] **端到端驗收 PASS（2026-10-13）**：localenv `greyhound`（:18110）——E2E 13/13（preview→啟流程→view 審核→退回→fill 補件→竄改 400→重送→版本列→核准結案）、`acceptance-test.sh` 7/0。過程中修掉 `FormDefinitionValidator` 未注入 value schema 的真 bug（`b21c138`：含 `dateRange` 的表單無法存檔）。
- [x] **本機開發改以 localenv 為準（使用者指示）**：新增 `scripts/localdev.sh`（偵測 `~/nanshan/scripts/localenv` 即調用；否則 compose＋主機 jar fallback）；修正 `greyhound.env` 的 JWT dev secret（原 22 bytes 不足 HS256 下限，greyhound 從未能在 localenv 啟動）；CLAUDE.md 開發流程改寫。

## 五、凍結介面（施工不得自行變更；有問題回報 PM）

### A. REST 契約

1. `GET /api/tasks/{taskId}/form` → **200：Envelope JSON 本體**（`{uniform,mode,frame,schemaRef,schema,data,meta,errors}`；`includeSchema=true`、`locale="zh-TW"`、`frame="form"`）。
   - 授權：`ProcessAccessGuard.requireReadAccess(processInstanceId, callerId)`；未登入 401；任務不存在／無權 404；`formKey` 空白或 `external:` 404（訊息指名「此任務不是動態表單任務」）；找不到 published 定義 404。
   - `mode` 來自該 BPMN 節點的 Access Profile；`data` 為該案件最新 FormData 列（canonical JSON）或 `{}`。
2. `GET /api/process-definitions?latestVersion=`：列表項新增 `startFormKey`（String｜null）。
3. `GET /api/process-definitions/{key}/start-form` → 200 Envelope（`mode=fill`、`default=editable`（可由 startEvent access 覆寫）、data `{}`）；key 不存在或無 start formKey → 404。
4. `POST /api/forms/preview` → body `{"schemaJson":"<DSL 字串>"}`；200 Envelope（fill／default=editable、data `{}`、`includeSchema=true`、`schemaRef.version=1`）；DSL 不合法 → 400 `{message}`。
5. `PUT /api/tasks/{id}`（action=complete）— body 新增選用 `formData:{"dataJson":"<JSON 物件字串>"}`：
   - 任務無 formKey 或 profile 非 `fill` → 400；定義找不到 → 404；validateV1 失敗 → 400（訊息指名欄位、零副作用）。
   - 通過：存 FormData 新列（`processInstanceId`／`formDefinitionId`／`submittedBy=caller`／`taskId=現任任務`；有前一列時依既有版本化語意＋`FORM_UPDATE` 稽核含 `formDataId`／`supersededFormDataId`，無前一列則 `FORM_SUBMIT`）；由 submitted 資料推導流程變數（受保護變數拒絕、與 body `variables` 重疊 400）；完成任務。回應新增 `formDataId`。
   - 跨 DB 補償：主交易 rollback → `afterCompletion` best-effort 刪除新列（同 `ProcessController` 模式）。
6. `POST /api/process-instances`：請求不變；`formData.formDefinitionId` **必須可解析**（否則 400，不再有略過驗證缺口）；validateV1 以 startEvent profile 驗證；推導變數＋存 FormData＋補償（既有結構保留）。
7. `POST /api/forms`／`PUT /api/forms/{id}`：`schemaJson` 必須是合法 DSL（schema＋`compileStatic`；錯誤 → 400 message 彙整）。
8. `POST /api/form-data` 與 `PUT /api/form-data/{id}` **移除**（無剩餘呼叫端）；`GET /api/form-data/{pid}` 保留（稽核／API 讀取）。
9. `GET /api/forms`／`GET /api/forms/{formKey}` 不變（回傳定義，`schemaJson` 內容為 DSL）。

### B. BPMN `uniform:access`（已依此落地於 seed BPMN）

- namespace `xmlns:uniform="http://bpm.com/uniform"`；掛在 `flowable:extensionElements`：
  - `<uniform:access mode="view" default="readonly"/>`
  - `<uniform:access mode="fill" default="readonly"><uniform:field key="reason" state="editable"/></uniform:access>`
- 解析規則：`mode` ∈ fill／view；`default` ∈ hidden／readonly／editable；`field` 具 `key`／`state`（必要時 `required`）。無 access／無法解析／mode 非法 → fail-closed（view＋readonly）。startEvent：無 access → `{fill, editable}`。

### C. 前端模組介面（`bpm-frontend/src/uniform/`）

- `setup.js`：`installUniformPlugin(app)` — 註冊 manifests（core pack）＋widgets（Element Plus pack＋本地覆寫）＋`createCoreValidation`（含 valueSchemas／valueSchemaResources）；`main.js` 呼叫。
- `api.js`：`fetchTaskForm(taskId)`、`fetchStartForm(processKey)`、`previewForm(schemaJson)`、`fetchProcessDefinitions(params)`（經 `services/http.js`）。
- `widgets.js`：`greyhoundWidgets` — Element Plus：date／dateRange／select（含 multiple）／radio／checkbox；值語意沿 SDK（scalar／array／`{start,end}`、`maxSelect`）。

## 六、施工清單（派工）

| 代號 | 範圍 | Worktree | 內容 |
|---|---|---|---|
| **BE** | `bpm-core`（Java） | `~/workspace/.worktrees/greyhound/uniform-be` | pom 4 依賴；`com.bpm.core.uniform`（FormAccessResolver／FormEnvelopeService／FormSubmissionService／FormDefinitionValidator）；控制器接線（§五 A）；移除舊驗證器；測試更新 |
| **FE** | `bpm-frontend` | `~/workspace/.worktrees/greyhound/uniform-fe` | `src/uniform/`（§五 C）；`DocumentDetail`／`StartProcess`／`FormEditor` 改 `UfRenderer`／`UfDesigner`；刪舊元件；測試 |
| **BPMN** | `bpm-frontend/src/bpmn` | `~/workspace/.worktrees/greyhound/uniform-bpmn` | `uniform:access` moddle extension＋property panel（view/fill、default、field 清單）＋測試（bpmn-js 存檔不得吃掉 extensionElements） |

PM 保留：整合／合併、`scripts/` 對齊（seed 檢查清單 4→2）、CLAUDE.md 更新、端到端驗收、uni-form 回報。

### 派工紀錄（2026-10-12）

| 代號 | Worktree | Branch | Base | Session |
|---|---|---|---|---|
| BE | `~/workspace/.worktrees/greyhound/uniform-be` | `feature/uniform-be` | `0d07772` | `ses_ed08ea9a4ffep5ra3PydZ0QGLm` |
| FE | `~/workspace/.worktrees/greyhound/uniform-fe` | `feature/uniform-fe` | `0d07772` | `ses_ed08ea9a2ffe8D6RgwkNGDesKP` |
| BPMN | `~/workspace/.worktrees/greyhound/uniform-bpmn` | `feature/uniform-bpmn` | `0d07772` | `ses_ed08ea9a1ffe70AE32D4tg6eMO` |

- uni-form 端：`~/workspace/.worktrees/uni-form/fix-jackson3-fullv1-smoke`（分支 `fix/jackson3-fullv1-smoke-v2`；`43bf983` 修正＋`9025fd7` 報告），待 uni-form 接受。
- PM 已完成：submodule pin、npm tarball、package.json/lock、V7 seed、BPMN access、`scripts/seed-data.sh`（4→2 keys＋DSL 檢查）、本計畫。

## 七、驗收條件

- `cd bpm-core && mvn verify` 全綠（Testcontainers）。
- `cd bpm-frontend && npm run test && npm run build` 全綠。
- 端到端：啟流程（fill）→ 審核（view、全唯讀）→ 退回 → 補件（`reason` 可編輯、其餘唯讀；readonly 值未變）→ 重送 → 核准；每步表單由 UfRenderer 呈現、後端 validateV1 驗證、`bpm_form_data` 有版本列、`_formVersions` 生效。
- 負向：缺必填 400、未知欄位 400、readonly 篡改 400、`dateRange` 非 `{start,end}` 400；檔表單 DSL 不合法 400；無 access 的 userTask fail-closed。
- 設計器：UfDesigner 新增／編輯→儲存（compileStatic gate）→發布→流程使用；BPMN 面板可編輯 access 且存檔不破壞原 XML。

## 八、開放問題

- CI 尚不能 clone submodule（本機 file transport；未 push）。部署階段前需指定可取得的 Git URL 或 CI 先行 build。
- `optionsSource`／OptionsProvider 遷移（P5）；`file`／FileVault（P5）；BPMN lint 檢查 access 引用的 field key 存在（P5）。
- uni-form `43bf983` 接受後更新 submodule pin（並重跑兩端）。
- **required 語意（SDK）**：必填以「鍵存在」判定；text/textarea 空字串在 API 直送時**不**報必填（舊 validator 視空白為缺漏）。前端 widgets 會把空輸入轉 `undefined`，UI 行為與舊版一致；差異僅限直接呼叫 API 的呼叫端。
