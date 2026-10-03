/**
 * webhook 設定的<b>存放格式</b> —— 設計器與 bpm-core 之間唯一的介面（#67）。
 *
 * 節點層（{@code <userTask>}）與流程層（{@code <process>}）共用同一份格式與解析
 * 邏輯（spec §11.4：後端 {@code WebhookConfigResolver.resolveForProcess} 與節點層
 * 完全對稱）；兩者唯一的分歧是「未填 event 時的預設值」—— 節點層是
 * {@code create}、流程層是 {@code process.completed}。因此讀寫函式都接受一個
 * 選配的 defaultEvent，由呼叫端（見 WebhookProps.js 的 webhookConfigFor）
 * 依元素型別決定。不給參數時維持節點層的舊行為。
 *
 * 為什麼抽成獨立模組：與 {@link ./assigneeExpressions} 同一個理由 ——
 * 這裡的每一個字串／tag 名都是與 Flowable 引擎之間的契約。寫錯的後果
 * 不在設計器（畫面完全正常、存檔成功），而是在<b>事件發生、投遞該發生的時候</b>，
 * 而且失敗型態是「沒有投遞、沒有錯誤、沒有稽核」。
 * 抽出來之後可以在沒有 preact／properties-panel 環境的情況下直接測。
 */

/**
 * 舊格式的文件前綴。
 *
 * 2026-09-30 之前的 {@code WebhookProps.js} 把整組設定以 JSON 塞進 BPMN 的
 * {@code <documentation>}，因為當時 {@code extensionElements} 沒有任何東西讀它
 * （spec §11.4 要求的格式從未被實作）。已部署的流程裡確實有這種寫法，
 * 所以必須讀得到。
 */
export const LEGACY_DOC_PREFIX = '__webhooks__:'

/** 型別名 —— tag 名由它決定（見 flowableModdle.js 的 tagAlias 註解）。 */
export const WEBHOOKS_TYPE = 'flowable:Webhooks'
export const WEBHOOK_TYPE = 'flowable:Webhook'

export const DEFAULT_EVENT = 'create'

/**
 * 流程層省略 event 時的預設值。與後端
 * {@code WebhookConfig.DEFAULT_PROCESS_EVENT} 是同一份契約（spec §11.4）。
 * ⚠️ 不可拿 DEFAULT_EVENT 代替：create 在流程層「不命中」，寫錯等於設定
 * 成功但永不投遞，而且沒有任何錯誤訊息。
 */
export const DEFAULT_PROCESS_EVENT = 'process.completed'

const DEFAULT_METHOD = 'POST'

function isContainer(el) {
  return Boolean(el?.$instanceOf?.(WEBHOOKS_TYPE))
}

function containerOf(bo) {
  const ee = bo?.get?.('extensionElements')
  const values = ee?.get?.('values') || []
  return values.find(isContainer) || null
}

function normalize(wh, defaultEvent = DEFAULT_EVENT) {
  return {
    event: wh?.event || defaultEvent,
    url: wh?.url || '',
    method: wh?.method || DEFAULT_METHOD,
    // #28：沒有這個欄位時回空字串（不是 undefined）—— 面板與測試都靠
    // 「空字串＝未設定」這個單一形狀，undefined 會讓 `wh.payloadTemplate`
    // 在部分路徑變成非預期的 falsy 差異。
    payloadTemplate: wh?.payloadTemplate || ''
  }
}

/**
 * 讀出某個節點的 webhook 設定。
 *
 * ⚠️ 優先順序與後端 {@code WebhookConfigResolver.resolve()} 必須一致：
 *   1. extensionElements 裡<b>存在</b> {@code <flowable:webhooks>} 元素 → 它是權威，
 *      裡面是空的就算「使用者清掉了全部」。
 *   2. 只有在完全沒有該元素時，才回頭讀舊的 {@code <documentation>__webhooks__:}。
 *
 * 為什麼「有元素就是權威」而不是「元素非空才是權威」：使用者把最後一筆 webhook
 * 刪掉之後，這裡仍會寫出一個空的 {@code <flowable:webhooks/>}。若讀取端改成
 * 「非空才優先」，剛刪掉的設定會立刻從舊 documentation 裡復活 ——
 * 使用者看到的是「刪了沒用」。兩邊都必須是「有元素就是權威」。
 *
 * @returns {Array<{event: string, url: string, method: string, payloadTemplate: string}>}
 */
export function readWebhooks(bo, defaultEvent = DEFAULT_EVENT) {
  const container = containerOf(bo)
  if (container) return (container.values || []).map(wh => normalize(wh, defaultEvent))
  return readLegacyWebhooks(bo, defaultEvent)
}

function readLegacyWebhooks(bo, defaultEvent = DEFAULT_EVENT) {
  const docs = bo?.get?.('documentation') || []
  const legacy = docs.find(d => String(d.text || '').startsWith(LEGACY_DOC_PREFIX))
  if (!legacy) return []
  try {
    const parsed = JSON.parse(String(legacy.text).slice(LEGACY_DOC_PREFIX.length))
    return Array.isArray(parsed) ? parsed.map(wh => normalize(wh, defaultEvent)) : []
  } catch {
    // 舊資料是手改或格式壞掉時回空陣列，而不是讓整個 properties panel 崩掉 ——
    // 崩掉會讓使用者在這個節點上完全無法編輯其他欄位。
    return []
  }
}

/**
 * 產生要寫回 businessObject 的 extensionElements。
 *
 * ⚠️ 保留 extensionElements 裡<b>其他</b>的子元素（例如 flowable:taskListener）。
 * 只留下自己那一個容器再塞回去，等於把節點上其他擴充點清空 ——
 * 而 taskListener 正是本專案的通知與 webhook 接線本身。
 *
 * @param bo           目標 businessObject（提供 {@code $model} 與現有 extensionElements）
 * @param webhooks     使用者編輯後的陣列
 * @param defaultEvent 未填 event 時要寫入的值（節點層 create／流程層 process.completed）
 * @returns {object} 一個 {@code bpmn:ExtensionElements}
 */
export function buildExtensionElements(bo, webhooks, defaultEvent = DEFAULT_EVENT) {
  const model = bo.$model
  const existing = bo.get('extensionElements')
  const kept = (existing?.get?.('values') || []).filter(el => !isContainer(el))

  const container = model.create(WEBHOOKS_TYPE)
  container.values = (webhooks || []).map(wh => {
    const props = {
      event: wh.event || defaultEvent,
      url: wh.url || '',
      method: wh.method || DEFAULT_METHOD
    }
    // #28：模板空白時不寫入屬性。後端 resolver 對「屬性不存在」與「空屬性」
    // 的處理相同（都視為未設定），但 XML 上留著 payloadTemplate="" 只會
    // 讓 diff 與人工檢查多一個噪音。「未設定就是沒有屬性」是清楚的契約。
    if (wh.payloadTemplate && wh.payloadTemplate.trim()) {
      props.payloadTemplate = wh.payloadTemplate
    }
    return model.create(WEBHOOK_TYPE, props)
  })
  kept.push(container)

  // 沿用既有的 ExtensionElements 實例（它可能帶著別人已經加的東西），
  // 沒有才新建。刻意不每次新建 —— 換掉實例會讓 bpmn-js 的 diff 認不出
  // 「只是改了 webhook」，匯出時整段重寫。
  const ee = existing || model.create('bpmn:ExtensionElements')
  ee.values = kept
  return ee
}

/**
 * 去掉舊格式的 {@code __webhooks__:} 文件，<b>保留其他 documentation</b>。
 *
 * ⚠️ 舊的 {@code save()} 是
 * {@code updateProperties(element, { documentation: [{ text: ... }] })} ——
 * 它會把節點上<b>所有</b>說明文字都換成 webhook JSON。任何人先寫一段
 * 「本關卡需主管同意」再點一下 webhook 的 URL，那段說明就沒了。
 *
 * 轉換行為：使用者下一次在設計器存檔時，舊的 webhook documentation 被移除、
 * 設定改寫進 extensionElements。這個時機是刻意選的 ——
 * 部署中的流程定義是稽核軌跡的一環，不該在部署或執行期被偷偷改寫。
 */
export function withoutLegacyWebhookDoc(documentation) {
  return (documentation || []).filter(d => !String(d.text || '').startsWith(LEGACY_DOC_PREFIX))
}

/** 文件是否為舊格式的 webhook 設定（供元件決定要不要顯示遷移提示）。 */
export function isLegacyWebhookDoc(documentation) {
  return (documentation || []).some(d => String(d.text || '').startsWith(LEGACY_DOC_PREFIX))
}
