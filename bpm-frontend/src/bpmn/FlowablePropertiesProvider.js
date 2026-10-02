import AssigneeProps from './AssigneeProps.js'
import FormProps from './FormProps.js'
import WebhookProps from './WebhookProps.js'

const LOW_PRIORITY = 500

export default class FlowablePropertiesProvider {
  constructor(propertiesPanel) {
    propertiesPanel.registerProvider(LOW_PRIORITY, this)
  }

  getGroups(element) {
    return (groups) => {
      if (is(element, 'bpmn:UserTask')) {
        groups.push(AssigneeProps(element))
        groups.push(FormProps(element))
        groups.push(WebhookProps(element))
      }
      // 流程層（#67）：後端 ProcessCompletedListener 讀 <process> 的
      // flowable:webhooks，但先前這裡只掛 UserTask，流程層設定在產品上
      // 完全看不到（連設定入口都沒有）。事件清單與預設事件由
      // WebhookProps 依元素型別決定（process.completed 而非 create）。
      if (is(element, 'bpmn:Process')) {
        groups.push(WebhookProps(element))
      }
      if (is(element, 'bpmn:StartEvent')) {
        groups.push(FormProps(element))
      }
      return groups
    }
  }
}

FlowablePropertiesProvider.$inject = ['propertiesPanel']

function is(element, type) {
  try {
    const bo = element?.businessObject || element
    return bo?.$instanceOf?.(type) ?? false
  } catch { return false }
}
