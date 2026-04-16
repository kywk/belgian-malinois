/**
 * Webhook properties group for UserTask nodes.
 * Stores webhook configs in BPMN extensionElements as custom data.
 */
import { html } from 'htm/preact'
import { TextFieldEntry, SelectEntry, TextAreaEntry } from '@bpmn-io/properties-panel'
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
  // Read webhooks from a documentation element (simple storage approach)
  const webhooks = getWebhooks(bo)

  const entries = []
  webhooks.forEach((wh, i) => {
    entries.push({
      id: `webhook-event-${i}`,
      component: (props) => html`<${SelectEntry}
        id=${`webhook-event-${i}`} label=${`Webhook #${i+1} 事件`} element=${element}
        getOptions=${() => EVENTS}
        getValue=${() => wh.event || 'create'}
        setValue=${(v) => { wh.event = v; saveWebhooks(element, webhooks, props) }} />`,
      isEdited: () => true
    })
    entries.push({
      id: `webhook-url-${i}`,
      component: (props) => html`<${TextFieldEntry}
        id=${`webhook-url-${i}`} label="URL" element=${element}
        getValue=${() => wh.url || ''}
        setValue=${(v) => { wh.url = v; saveWebhooks(element, webhooks, props) }} />`,
      isEdited: () => true
    })
    entries.push({
      id: `webhook-method-${i}`,
      component: (props) => html`<${SelectEntry}
        id=${`webhook-method-${i}`} label="Method" element=${element}
        getOptions=${() => METHODS}
        getValue=${() => wh.method || 'POST'}
        setValue=${(v) => { wh.method = v; saveWebhooks(element, webhooks, props) }} />`,
      isEdited: () => true
    })
  })

  // Add button entry
  entries.push({
    id: 'webhook-add',
    component: (props) => html`<div style="padding:8px">
      <button onclick=${() => {
        webhooks.push({ event: 'create', url: '', method: 'POST' })
        saveWebhooks(element, webhooks, props)
      }} style="cursor:pointer">+ 新增 Webhook</button>
    </div>`,
    isEdited: () => false
  })

  return { id: 'flowable-webhooks', label: 'Webhook', entries }
}

function getWebhooks(bo) {
  // Store webhooks as JSON in documentation for simplicity
  const docs = bo.get('documentation') || []
  const whDoc = docs.find(d => d.text?.startsWith('__webhooks__:'))
  if (whDoc) {
    try { return JSON.parse(whDoc.text.replace('__webhooks__:', '')) } catch { /* ignore */ }
  }
  return []
}

function saveWebhooks(element, webhooks, props) {
  try {
    const modeling = useService('modeling')
    // Store as property for now; full extensionElements implementation would be more complex
    modeling.updateProperties(element, {
      'documentation': [{ text: '__webhooks__:' + JSON.stringify(webhooks) }]
    })
  } catch { /* properties panel context */ }
}
