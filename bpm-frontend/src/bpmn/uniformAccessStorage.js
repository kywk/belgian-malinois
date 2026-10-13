/**
 * {@code uniform:access} 的<b>存放格式</b> —— 設計器與 bpm-core 之間唯一的介面
 * （ADR-23、uni-form 重構 plan §五 B）。
 *
 * 格式（凍結，不得自行變更）：
 *   <bpmn:extensionElements>
 *     <uniform:access mode="fill" default="readonly">
 *       <uniform:field key="reason" state="editable"/>
 *     </uniform:access>
 *   </bpmn:extensionElements>
 *
 * 為什麼抽成獨立模組：與 {@link ./webhookStorage}、{@link ./assigneeExpressions}
 * 同一個理由 —— 這裡的每一個字串（tag／屬性名、mode／state 值域）都是與
 * bpm-core 的契約。寫錯的後果不在設計器（畫面正常、存檔成功），而是在
 * 使用者開表單的那一刻：欄位權限錯誤，或後端解析不到、靜默 fail-closed 成
 * view＋readonly（整個任務變成唯讀、沒有任何錯誤訊息）。
 * 抽出來後可以在沒有 preact／properties-panel 環境的情況下直接測。
 *
 * 讀寫的兩條原則（與 webhookStorage 相同）：
 *   1. <b>保留 extensionElements 裡其他子元素</b>（flowable:taskListener、
 *      flowable:webhooks…）—— 只留下自己那一個容器再塞回去，等於把節點上
 *      其他擴充點清空。
 *   2. <b>未設定就是沒有屬性</b> —— mode／default／key／state／required 空白時
 *      不寫進 XML。後端對「屬性不存在」與「空屬性」的解讀相同，但空屬性只會
 *      讓 diff 與人工檢查多一個噪音。
 */

/** 型別名 —— tag 名由它決定（見 flowableModdle.js 的 tagAlias 註解）。 */
export const ACCESS_TYPE = 'uniform:Access'
export const FIELD_TYPE = 'uniform:Field'

/** namespace uri —— 與 seed BPMN 的 xmlns:uniform 相同。 */
export const UNIFORM_URI = 'http://bpm.com/uniform'

function isAccess(el) {
  return Boolean(el?.$instanceOf?.(ACCESS_TYPE))
}

function accessOf(bo) {
  const ee = bo?.get?.('extensionElements')
  const values = ee?.get?.('values') || []
  return values.find(isAccess) || null
}

function normalizeField(f) {
  return {
    key: f?.key || '',
    state: f?.state || '',
    // 三態：''（未設定）／'true'／'false'。刻意保留字串而不是布林 ——
    // 「未設定」與「false（強制不必填）」在 Access Profile 是不同的意思
    //（fieldEntry 的 required: false 是有效的覆寫），不能混成同一個形狀。
    required: f?.required || ''
  }
}

/**
 * 讀出節點的 {@code uniform:access}。
 *
 * 沒有 access 元素時回傳<b>空 profile</b>（而不是 null）：面板與呼叫端都靠
 * 「空字串＝未設定」這個單一形狀，null 會讓每一處都要多一個判斷。
 * 後端的 fail-closed（view＋readonly）是執行期語意，設計器不代為補值 ——
 * 讀寫必須忠實反映檔案內容。
 *
 * @returns {{mode: string, default: string, fields: Array<{key: string, state: string, required: string}>}}
 */
export function readAccess(bo) {
  const container = accessOf(bo)
  if (!container) return { mode: '', default: '', fields: [] }
  return {
    mode: container.mode || '',
    default: container.default || '',
    fields: (container.field || []).map(normalizeField)
  }
}

/** profile 是否「什麼都沒設定」（用來決定要不要保留 uniform:access 元素）。 */
export function isEmptyAccess(access) {
  return !access?.mode && !access?.default && (access?.fields || []).length === 0
}

/**
 * 產生要寫回 businessObject 的 extensionElements。
 *
 * - access 是空的（mode／default 都未設定且沒有任何欄位列）→ 不寫
 *   {@code uniform:access}。連帶地，若 extensionElements 裡沒有其他子元素，
 *   回傳 {@code undefined}，讓 updateProperties 把整個容器移除 ——
 *   不留 {@code <bpmn:extensionElements/>} 空殼。
 * - 否則在保留其他子元素的前提下，重建一個 {@code uniform:access}
 *   （與 webhookStorage 的重建策略相同：讀進來的 moddle 元素不直接改，
 *   產生一份乾淨的）。
 *
 * ⚠️ 欄位列照使用者當下的狀態原樣寫出（含空 key 的新增列）。不偷偷過濾 ——
 * 過濾會讓「按了新增、還沒填」的列在面板重繪後消失（properties panel 每次
 * 存檔都重新 getGroups），使用者看到的是「新增無效」。後端 mapper 對沒有
 * key 的 field 直接略過即可（見 plan §五 B：field 具 key／state）。
 *
 * @param bo     目標 businessObject（提供 {@code $model} 與現有 extensionElements）
 * @param access 使用者編輯後的 profile（readAccess 的形狀）
 * @returns {object|undefined} {@code bpmn:ExtensionElements} 或 undefined（整段移除）
 */
export function buildExtensionElements(bo, access) {
  const model = bo.$model
  const existing = bo.get('extensionElements')
  const kept = (existing?.get?.('values') || []).filter(el => !isAccess(el))

  const container = createAccess(model, access)
  if (container) kept.push(container)

  if (kept.length === 0) return undefined

  // 沿用既有的 ExtensionElements 實例（它可能帶著別人已經加的東西），
  // 沒有才新建。刻意不每次新建 —— 換掉實例會讓 bpmn-js 的 diff 認不出
  //「只是改了 access」，匯出時整段重寫。
  const ee = existing || model.create('bpmn:ExtensionElements')
  ee.values = kept
  return ee
}

function createAccess(model, access) {
  if (isEmptyAccess(access)) return null

  const props = {}
  if (access.mode) props.mode = access.mode
  if (access.default) props.default = access.default

  const container = model.create(ACCESS_TYPE, props)
  container.field = (access.fields || []).map(f => {
    const fieldProps = {}
    if (f.key) fieldProps.key = f.key
    if (f.state) fieldProps.state = f.state
    if (f.required) fieldProps.required = f.required
    return model.create(FIELD_TYPE, fieldProps)
  })
  return container
}

/**
 * 寫回 businessObject 的路徑（面板用）。
 *
 * 一次 {@code updateProperties}，讓這是 undo 堆疊裡的一步；拆成兩次的話，
 * 使用者按一次 ctrl+Z 只會走一半。
 */
export function saveAccess(modeling, element, access) {
  modeling.updateProperties(element, {
    extensionElements: buildExtensionElements(element.businessObject, access)
  })
}
