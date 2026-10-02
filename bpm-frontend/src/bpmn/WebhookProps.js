import { h } from 'preact'
import { TextFieldEntry, SelectEntry } from '@bpmn-io/properties-panel'
import { useService } from 'bpmn-js-properties-panel'
import {
  DEFAULT_EVENT,
  DEFAULT_PROCESS_EVENT,
  buildExtensionElements,
  isLegacyWebhookDoc,
  readWebhooks,
  withoutLegacyWebhookDoc
} from './webhookStorage'

/**
 * 兩層 webhook 面板的事件選項（#67、spec §11.4）。
 *
 * ⚠️ 刻意分成兩份獨立清單，不是共用一份再過濾：兩層的合法集合本來就不同 ——
 * 流程層只認 process.completed／complete／all／省略，create／timeout／reject
 * 在後端「不命中」（投遞永不發生）。共用清單的話，未來新增節點事件時很容易
 * 連流程層一起拿到，而使用者會設定成功、部署成功、然後永遠收不到。
 */
export const NODE_EVENT_OPTIONS = [
  { value: 'create', label: 'create' },
  { value: 'complete', label: 'complete' },
  { value: 'timeout', label: 'timeout' },
  { value: 'reject', label: 'reject' },
  { value: 'all', label: 'all' }
]

/** 流程層只有「流程結案」：正式名稱、節點層同名值（方便遷移）、與全收的 all。 */
export const PROCESS_EVENT_OPTIONS = [
  { value: 'process.completed', label: 'process.completed（流程結案）' },
  { value: 'complete', label: 'complete' },
  { value: 'all', label: 'all' }
]

const METHODS = [
  { value: 'POST', label: 'POST' },
  { value: 'PUT', label: 'PUT' }
]

const NODE_CONFIG = { label: 'Webhook', events: NODE_EVENT_OPTIONS, defaultEvent: DEFAULT_EVENT }
const PROCESS_CONFIG = { label: '流程 Webhook', events: PROCESS_EVENT_OPTIONS, defaultEvent: DEFAULT_PROCESS_EVENT }

/**
 * 依元素型別挑選面板設定 —— 兩層面板唯一的分歧點。
 *
 * 元件、存檔格式、舊格式提示全部共用；只有事件清單與預設事件不同。
 * 刻意由元素型別推導，而不是讓呼叫端把設定傳進來：呼叫端傳錯（例如流程被掛上
 * 節點清單）不會有任何錯誤畫面，只會讓使用者存下一個流程層永不投遞的事件。
 * 由型別推導就沒有「傳錯」這個入口。
 */
export function webhookConfigFor(element) {
  const bo = element?.businessObject || element
  return isProcessBo(bo) ? PROCESS_CONFIG : NODE_CONFIG
}

export default function WebhookProps(element) {
  return buildGroup(element, webhookConfigFor(element))
}

function buildGroup(element, config) {
  const bo = element.businessObject
  const webhooks = readWebhooks(bo, config.defaultEvent)
  const entries = []

  webhooks.forEach((wh, i) => {
    entries.push({ id: `webhook-event-${i}`, component: makeWhEvent(element, webhooks, wh, i, config), isEdited: () => true })
    entries.push({ id: `webhook-url-${i}`, component: makeWhUrl(element, webhooks, wh, i), isEdited: () => true })
    entries.push({ id: `webhook-method-${i}`, component: makeWhMethod(element, webhooks, wh, i), isEdited: () => true })
  })

  // 舊格式的設定還在 documentation 裡時，明講出來。
  // 沒有這行的話，使用者只會看到「我設的 webhook 不見了」——
  // 而實際上是它被讀出來顯示在這裡了（相容性是生效的）。
  if (isLegacyWebhookDoc(bo.get('documentation'))) {
    entries.push({
      id: 'webhook-legacy-notice',
      component: makeLegacyNotice(),
      isEdited: () => true
    })
  }

  entries.push({ id: 'webhook-add', component: makeWhAdd(element, webhooks, config), isEdited: () => false })
  return { id: 'flowable-webhooks', label: config.label, entries }
}

function makeWhEvent(element, webhooks, wh, i, config) {
  return function (props) {
    const modeling = useService('modeling')
    const debounce = useService('debounceInput')
    return h(SelectEntry, { id: `webhook-event-${i}`, label: `Webhook #${i + 1} 事件`, element, debounce, getOptions: () => config.events, getValue: () => wh.event || config.defaultEvent, setValue: (v) => { wh.event = v; saveWebhooks(modeling, element, webhooks) } })
  }
}
function makeWhUrl(element, webhooks, wh, i) {
  return function (props) {
    const modeling = useService('modeling')
    const debounce = useService('debounceInput')
    return h(TextFieldEntry, { id: `webhook-url-${i}`, label: 'URL', element, debounce, getValue: () => wh.url || '', setValue: (v) => { wh.url = v; saveWebhooks(modeling, element, webhooks) } })
  }
}
function makeWhMethod(element, webhooks, wh, i) {
  return function (props) {
    const modeling = useService('modeling')
    const debounce = useService('debounceInput')
    return h(SelectEntry, { id: `webhook-method-${i}`, label: 'Method', element, debounce, getOptions: () => METHODS, getValue: () => wh.method || 'POST', setValue: (v) => { wh.method = v; saveWebhooks(modeling, element, webhooks) } })
  }
}
function makeWhAdd(element, webhooks, config) {
  return function () {
    const modeling = useService('modeling')
    return h('div', { style: 'padding:8px' }, h('button', { onclick: () => { webhooks.push({ event: config.defaultEvent, url: '', method: 'POST' }); saveWebhooks(modeling, element, webhooks) }, style: 'cursor:pointer' }, '+ 新增 Webhook'))
  }
}

function makeLegacyNotice() {
  return function () {
    return h('div', { style: 'padding:4px 8px;font-size:11px;opacity:0.75' },
      '這組設定目前存在於舊格式（BPMN 的 documentation）。修改任一欄位後會自動改寫成 extensionElements。')
  }
}

/**
 * 寫回 businessObject。
 *
 * ⚠️ 這裡的形狀是與 bpm-core 之間的契約（#67），不是樣式問題：
 *
 * <ul>
 *   <li><b>寫進 {@code extensionElements}</b> —— spec §11.4 要求的格式，
 *       也是後端 {@code WebhookConfigResolver} 唯一會優先讀的地方。
 *       舊版寫進 {@code documentation}，而後端<b>零讀取</b>，
 *       整條投遞鏈因此從未接上。</li>
 *   <li><b>保留 extensionElements 裡其他子元素</b> ——
 *       見 {@link buildExtensionElements}。</li>
 *   <li><b>順手清掉舊的 {@code __webhooks__:} documentation</b>，但保留其他說明文字
 *       —— 見 {@link withoutLegacyWebhookDoc}。</li>
 *   <li><b>一次 updateProperties</b>，讓這是 undo 堆疊裡的一步。
 *       拆成兩次的話，使用者按一次 ctrl+Z 只會走一半。</li>
 *   <li><b>預設事件由元素型別決定</b>（節點 create／流程 process.completed）——
 *       與 {@link webhookConfigFor} 同一個判斷。使用者沒選事件時，寫出來的
 *       必須是後端在該層真的會投遞的值。</li>
 * </ul>
 */
export function saveWebhooks(modeling, element, webhooks) {
  const bo = element.businessObject
  const { defaultEvent } = webhookConfigFor(element)
  modeling.updateProperties(element, {
    extensionElements: buildExtensionElements(bo, webhooks, defaultEvent),
    documentation: withoutLegacyWebhookDoc(bo.get('documentation'))
  })
}

function isProcessBo(bo) {
  try { return bo?.$instanceOf?.('bpmn:Process') ?? false } catch { return false }
}
