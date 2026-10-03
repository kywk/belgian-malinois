import { describe, expect, it, vi } from 'vitest'
import BpmnModdle from 'bpmn-moddle'
import { CheckboxEntry, SelectEntry, TextFieldEntry } from '@bpmn-io/properties-panel'
import flowableModdle from '../composables/flowableModdle'
import CallActivityProps, {
  calledElementOptions,
  calledElementVNode,
  ensureProcessDefinitions,
  inheritVariablesVNode,
  loadProcessDefinitions,
  processDefinitionsState
} from './CallActivityProps'
import { getProcessDefinitions } from '../services/flowableApi'

/**
 * Call Activity 面板的存檔與降級契約（#4、spec §4.4.2）。
 *
 * <p>為什麼直接驅動 calledElementVNode／inheritVariablesVNode 而不是呼叫
 * entry.component：那兩個無 hook 函式就是元件本體實際回傳 vnode 的地方
 * （元件只是接 service 與 hook 的薄殼），與 WebhookProps.spec.js 直接驅動
 * saveWebhooks 同一個理由 —— 測到的必須是產品真的跑的那條路徑。
 * 面板元件的 hook 接線（useEffect → ensureProcessDefinitions）由
 * 「流程清單載入狀態」那組測試覆蓋載入器本身。
 *
 * ⚠️ 這裡用真的 bpmn-moddle（BpmnModdle + flowableModdle）與假的 modeling
 * （記錄呼叫、比照真的 modeling 把屬性 set 回 businessObject），因為本面板
 * 最重要的風險是「設計器存檔把 extensionElements 裡的 flowable:in／out
 * 刪掉」—— 那只有真的解析與序列化才驗得到。
 */
vi.mock('bpmn-js-properties-panel', () => ({ useService: vi.fn() }))
vi.mock('../services/flowableApi', () => ({ getProcessDefinitions: vi.fn() }))

const XML = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:flowable="http://flowable.org/bpmn"
             targetNamespace="http://bpm.com/test">
  <process id="p1" isExecutable="true">
    <startEvent id="start"/>
    <callActivity id="ca1" name="法務加簽" calledElement="countersign-review"
                  flowable:inheritVariables="false">
      <extensionElements>
        <flowable:in source="legalReviewer" target="countersignAssignee"/>
        <flowable:out source="approved" target="countersignApproved"/>
      </extensionElements>
    </callActivity>
  </process>
</definitions>`

async function parse() {
  const m = new BpmnModdle({ flowable: flowableModdle })
  const { rootElement, warnings } = await m.fromXML(XML, 'bpmn:Definitions')
  const process = rootElement.rootElements.find(p => p.id === 'p1')
  const callActivity = process.flowElements.find(f => f.id === 'ca1')
  return { m, rootElement, callActivity, warnings }
}

/** 假的 modeling：記錄呼叫，並比照真的 updateProperties 用 bo.set 寫回。 */
function fakeModeling() {
  const updateProperties = vi.fn((element, props) => {
    const bo = element.businessObject
    for (const key of Object.keys(props)) bo.set(key, props[key])
  })
  return { updateProperties }
}

const DEFINITIONS = [
  { key: 'countersign-review', name: '加簽複核子流程', version: 1 },
  { key: 'leave-approval', name: '請假審核流程', version: 2 }
]

describe('calledElement 的存檔契約', () => {
  it('載入成功後選值：恰好一次 updateProperties、只寫 calledElement，extensionElements 與其他屬性不動', async () => {
    getProcessDefinitions.mockResolvedValue(DEFINITIONS)
    const state = await loadProcessDefinitions()
    expect(state.status).toBe('loaded')

    const { m, rootElement, callActivity } = await parse()
    const element = { businessObject: callActivity }
    const modeling = fakeModeling()

    const vnode = calledElementVNode({
      element, modeling, debounce: vi.fn(), state,
      currentValue: callActivity.get('calledElement')
    })
    expect(vnode.type).toBe(SelectEntry)

    vnode.props.setValue('leave-approval')

    // 一次呼叫 = undo 堆疊裡的一步；拆成兩次的話 ctrl+Z 只會走一半。
    expect(modeling.updateProperties).toHaveBeenCalledTimes(1)
    expect(modeling.updateProperties).toHaveBeenCalledWith(element, expect.any(Object))

    const props = modeling.updateProperties.mock.calls[0][1]
    // 只寫自己那一格 —— in／out 在 extensionElements 裡，絕不能被覆蓋。
    expect(Object.keys(props)).toEqual(['calledElement'])
    expect(props.calledElement).toBe('leave-approval')

    // 序列化再讀回：extensionElements 的 in／out 與其他屬性必須原封不動。
    const { xml } = await m.toXML(rootElement, { format: true })
    expect(xml).toContain('calledElement="leave-approval"')
    expect(xml).toContain('name="法務加簽"')
    expect(xml).toContain('<flowable:in source="legalReviewer" target="countersignAssignee"')
    expect(xml).toContain('<flowable:out source="approved" target="countersignApproved"')
    expect(xml).toContain('flowable:inheritVariables="false"')

    const back = await m.fromXML(xml, 'bpmn:Definitions')
    const backCa = back.rootElement.rootElements.find(p => p.id === 'p1')
      .flowElements.find(f => f.id === 'ca1')
    expect(backCa.get('calledElement')).toBe('leave-approval')
    expect(backCa.extensionElements.values.map(v => v.$type)).toEqual(['flowable:In', 'flowable:Out'])
  })

  it('下拉選項：已部署流程用 key 當值、名稱＋key 當標籤；目前值不在清單時附加標示且不偷改', async () => {
    getProcessDefinitions.mockResolvedValue(DEFINITIONS)
    await loadProcessDefinitions()
    const { callActivity } = await parse()
    const element = { businessObject: callActivity }
    const modeling = fakeModeling()

    expect(calledElementOptions(DEFINITIONS, 'countersign-review').map(o => o.label)).toEqual([
      '加簽複核子流程（countersign-review）',
      '請假審核流程（leave-approval）'
    ])

    const vnode = calledElementVNode({
      element, modeling, debounce: vi.fn(), state: processDefinitionsState(),
      currentValue: 'removed-flow'
    })
    const options = vnode.props.getOptions()
    expect(options.map(o => o.value)).toEqual(['countersign-review', 'leave-approval', 'removed-flow'])
    expect(options[options.length - 1].label).toBe('removed-flow（未在已部署清單中）')

    // 開啟面板不觸發存檔：原值照實顯示，只有使用者自己選才改。
    expect(vnode.props.getValue()).toBe('removed-flow')
    expect(modeling.updateProperties).not.toHaveBeenCalled()
  })

  it('API 失敗時降級成手動輸入：描述帶錯誤訊息，輸入後仍是一次 updateProperties', async () => {
    getProcessDefinitions.mockRejectedValue({ response: { data: { message: '伺服器錯誤 (500)' } } })
    const state = await loadProcessDefinitions()
    expect(state).toEqual({ status: 'error', message: '伺服器錯誤 (500)' })

    const { callActivity } = await parse()
    const element = { businessObject: callActivity }
    const modeling = fakeModeling()

    const vnode = calledElementVNode({
      element, modeling, debounce: vi.fn(), state,
      currentValue: callActivity.get('calledElement')
    })
    // 降級不是「畫面空白」：手動輸入欄位仍在，且原值可見。
    expect(vnode.type).toBe(TextFieldEntry)
    expect(vnode.props.getValue()).toBe('countersign-review')
    expect(vnode.props.description).toContain('伺服器錯誤 (500)')
    expect(vnode.props.description).toContain('手動輸入')

    vnode.props.setValue('manually-typed-flow')
    expect(modeling.updateProperties).toHaveBeenCalledTimes(1)
    expect(modeling.updateProperties.mock.calls[0][1]).toEqual({ calledElement: 'manually-typed-flow' })
  })

  it('載入中：下拉仍顯示目前值與載入提示，且不觸發存檔', async () => {
    // 永遠不 resolve 的 promise：把狀態停在 loading。
    getProcessDefinitions.mockReturnValue(new Promise(() => {}))
    loadProcessDefinitions()
    expect(processDefinitionsState().status).toBe('loading')

    const { callActivity } = await parse()
    const modeling = fakeModeling()
    const vnode = calledElementVNode({
      element: { businessObject: callActivity }, modeling, debounce: vi.fn(),
      state: processDefinitionsState(), currentValue: callActivity.get('calledElement')
    })

    expect(vnode.type).toBe(SelectEntry)
    expect(vnode.props.getOptions().map(o => o.value)).toEqual(['countersign-review'])
    expect(vnode.props.description).toBe('流程清單載入中…')
    expect(modeling.updateProperties).not.toHaveBeenCalled()
  })

  it('ensureProcessDefinitions 共用同一個載入 promise，不重複打 API', async () => {
    getProcessDefinitions.mockResolvedValue(DEFINITIONS)
    await loadProcessDefinitions()
    await ensureProcessDefinitions()
    expect(getProcessDefinitions).toHaveBeenCalledTimes(1)
    expect(getProcessDefinitions).toHaveBeenCalledWith({ latestVersion: true })
  })
})

describe('inheritVariables 的存檔契約', () => {
  it('切換勾選：CheckboxEntry、恰好一次 updateProperties、XML round-trip 讀得回 true', async () => {
    const { m, rootElement, callActivity } = await parse()
    const element = { businessObject: callActivity }
    const modeling = fakeModeling()

    const vnode = inheritVariablesVNode({ element, modeling })
    expect(vnode.type).toBe(CheckboxEntry)
    // XML 寫的是 false → 畫面必須是未勾選。
    expect(vnode.props.getValue()).toBe(false)

    vnode.props.setValue(true)

    expect(modeling.updateProperties).toHaveBeenCalledTimes(1)
    expect(modeling.updateProperties).toHaveBeenCalledWith(element, { 'flowable:inheritVariables': true })

    const { xml } = await m.toXML(rootElement, { format: true })
    expect(xml).toContain('flowable:inheritVariables="true"')
    // 勾選 inheritVariables 不該動到 extensionElements 的映射。
    expect(xml).toContain('<flowable:in source="legalReviewer" target="countersignAssignee"')
    expect(xml).toContain('calledElement="countersign-review"')

    const back = await m.fromXML(xml, 'bpmn:Definitions')
    const backCa = back.rootElement.rootElements.find(p => p.id === 'p1')
      .flowElements.find(f => f.id === 'ca1')
    expect(backCa.get('flowable:inheritVariables')).toBe(true)
  })

  it('未設定 inheritVariables（Flowable 預設 false）時畫面是未勾選', async () => {
    const m = new BpmnModdle({ flowable: flowableModdle })
    const { rootElement } = await m.fromXML(
      XML.replace(' flowable:inheritVariables="false"', ''), 'bpmn:Definitions')
    const callActivity = rootElement.rootElements.find(p => p.id === 'p1')
      .flowElements.find(f => f.id === 'ca1')

    const vnode = inheritVariablesVNode({ element: { businessObject: callActivity }, modeling: fakeModeling() })
    expect(vnode.props.getValue()).toBe(false)
  })
})

describe('面板掛載的群組', () => {
  it('CallActivityProps 回傳固定群組與兩個 entry', async () => {
    const { callActivity } = await parse()
    const group = CallActivityProps({ businessObject: callActivity })
    expect(group.id).toBe('flowable-call-activity')
    expect(group.label).toBe('呼叫子流程')
    expect(group.entries.map(e => e.id)).toEqual(['calledElement', 'inheritVariables'])
  })
})
