import { h } from 'preact'
import { TextFieldEntry, SelectEntry } from '@bpmn-io/properties-panel'
import { useService } from 'bpmn-js-properties-panel'
import { buildExtensionElements, isLegacyWebhookDoc, readWebhooks, withoutLegacyWebhookDoc } from './webhookStorage'

const EVENTS = [
  { value: 'create', label: 'create' },
  { value: 'complete', label: 'complete' },
  { value: 'timeout', label: 'timeout' },
  { value: 'reject', label: 'reject' }
]
const METHODS = [
  { value: 'POST', label: 'POST' },
  { value: 'PUT', label: 'PUT' }
]

export default function WebhookProps(element) {
  const bo = element.businessObject
  const webhooks = readWebhooks(bo)
  const entries = []

  webhooks.forEach((wh, i) => {
    entries.push({ id: `webhook-event-${i}`, component: makeWhEvent(element, webhooks, wh, i), isEdited: () => true })
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

  entries.push({ id: 'webhook-add', component: makeWhAdd(element, webhooks), isEdited: () => false })
  return { id: 'flowable-webhooks', label: 'Webhook', entries }
}

function makeWhEvent(element, webhooks, wh, i) {
  return function (props) {
    const modeling = useService('modeling')
    const debounce = useService('debounceInput')
    return h(SelectEntry, { id: `webhook-event-${i}`, label: `Webhook #${i + 1} 事件`, element, debounce, getOptions: () => EVENTS, getValue: () => wh.event || 'create', setValue: (v) => { wh.event = v; save(modeling, element, webhooks) } })
  }
}
function makeWhUrl(element, webhooks, wh, i) {
  return function (props) {
    const modeling = useService('modeling')
    const debounce = useService('debounceInput')
    return h(TextFieldEntry, { id: `webhook-url-${i}`, label: 'URL', element, debounce, getValue: () => wh.url || '', setValue: (v) => { wh.url = v; save(modeling, element, webhooks) } })
  }
}
function makeWhMethod(element, webhooks, wh, i) {
  return function (props) {
    const modeling = useService('modeling')
    const debounce = useService('debounceInput')
    return h(SelectEntry, { id: `webhook-method-${i}`, label: 'Method', element, debounce, getOptions: () => METHODS, getValue: () => wh.method || 'POST', setValue: (v) => { wh.method = v; save(modeling, element, webhooks) } })
  }
}
function makeWhAdd(element, webhooks) {
  return function () {
    const modeling = useService('modeling')
    return h('div', { style: 'padding:8px' }, h('button', { onclick: () => { webhooks.push({ event: 'create', url: '', method: 'POST' }); save(modeling, element, webhooks) }, style: 'cursor:pointer' }, '+ 新增 Webhook'))
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
 * </ul>
 */
function save(modeling, element, webhooks) {
  const bo = element.businessObject
  modeling.updateProperties(element, {
    extensionElements: buildExtensionElements(bo, webhooks),
    documentation: withoutLegacyWebhookDoc(bo.get('documentation'))
  })
}
