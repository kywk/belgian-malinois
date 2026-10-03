import AssigneeProps from './AssigneeProps.js'
import CallActivityProps from './CallActivityProps.js'
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
      // 已知限制（2026-10-02 使用者裁決，暫不支援）：pool／collaboration 圖上
      // 選取的是 bpmn:Participant，不是 bpmn:Process，所以下方判斷不會命中，
      // 流程層 webhook 面板在 pool 上不會出現。要支援得先處理 participant→
      // processRef 的對應（設定寫在 participant 還是它引用的 process？畫面顯示
      // 哪一層？），這超出本輪範圍；且種子 BPMN 沒有 pool、spec §11.4 也未定義
      // pool 情境，因此現在不做。日後若要支援，從這裡開始補。
      //
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
      // Call Activity（#4、spec §4.4.2）：業務人員在這裡選預定義加簽子流程。
      // 掛在 bpmn:CallActivity 上，與 webhook 掛在 UserTask／Process 同一種
      // 形狀；provider 本身是 FlowablePropertiesProvider，由 bpmn/index.js
      // 註冊進模型器（useBpmnModeler 的 additionalModules）。
      if (is(element, 'bpmn:CallActivity')) {
        groups.push(CallActivityProps(element))
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
