import { h } from 'preact'
import { TextFieldEntry, SelectEntry } from '@bpmn-io/properties-panel'
import { useService } from 'bpmn-js-properties-panel'

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
  const webhooks = getWebhooks(bo)
  const entries = []

  webhooks.forEach((wh, i) => {
    entries.push({ id: `webhook-event-${i}`, component: makeWhEvent(element, webhooks, wh, i), isEdited: () => true })
    entries.push({ id: `webhook-url-${i}`, component: makeWhUrl(element, webhooks, wh, i), isEdited: () => true })
    entries.push({ id: `webhook-method-${i}`, component: makeWhMethod(element, webhooks, wh, i), isEdited: () => true })
  })

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

function getWebhooks(bo) {
  const docs = bo.get('documentation') || []
  const whDoc = docs.find(d => d.text?.startsWith('__webhooks__:'))
  if (whDoc) { try { return JSON.parse(whDoc.text.replace('__webhooks__:', '')) } catch { } }
  return []
}
function save(modeling, element, webhooks) {
  modeling.updateProperties(element, { documentation: [{ text: '__webhooks__:' + JSON.stringify(webhooks) }] })
}
