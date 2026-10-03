import { describe, expect, it, vi } from 'vitest'
import BpmnModdle from 'bpmn-moddle'
import { useService } from 'bpmn-js-properties-panel'
import flowableModdle from '../composables/flowableModdle'
import WebhookProps, {
  NODE_EVENT_OPTIONS,
  PROCESS_EVENT_OPTIONS,
  saveWebhooks,
  webhookConfigFor
} from './WebhookProps'
import { LEGACY_DOC_PREFIX, readWebhooks } from './webhookStorage'

/**
 * 面板元件的存檔路徑（#67）。
 *
 * 先前的測試缺口：webhookStorage.spec.js 只測純函式（buildExtensionElements／
 * readWebhooks…），元件實際呼叫的 save() 從來沒被測過。save() 是「設計器按下
 * 任何一欄之後真正發生的事」，也是與後端之間契約成形的地方 —— 它寫錯的後果
 * 不在畫面（存檔成功、看起來正常），而在事件發生時沒有投遞、沒有錯誤。
 *
 * 因此這裡用真的 bpmn-moddle（BpmnModdle + flowableModdle）與假的 modeling
 * （記錄呼叫、比照真的 modeling 把屬性寫回 businessObject），直接驅動元件
 * 內部使用的那個 saveWebhooks，而不是另寫一份複製品。
 */
vi.mock('bpmn-js-properties-panel', () => ({ useService: vi.fn() }))

function moddle() {
  return new BpmnModdle({ flowable: flowableModdle })
}

function shell(processInner = '', taskInner = '') {
  return `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:flowable="http://flowable.org/bpmn"
             targetNamespace="http://bpm.com/test">
  <process id="p1" isExecutable="true">
    ${processInner}
    <startEvent id="start"/>
    <userTask id="t1" name="審核">${taskInner}</userTask>
  </process>
</definitions>`
}

async function parse(processInner = '', taskInner = '') {
  const m = moddle()
  const { rootElement } = await m.fromXML(shell(processInner, taskInner), 'bpmn:Definitions')
  const process = rootElement.rootElements.find(p => p.id === 'p1')
  const task = process.flowElements.find(f => f.id === 't1')
  return { m, rootElement, process, task }
}

/**
 * 假的 modeling：記錄呼叫，並比照真的 modeling.updateProperties 把屬性
 * 寫回 businessObject —— 這樣存檔結果才能真的序列化成 XML 再 round-trip 讀回。
 */
function fakeModeling() {
  const updateProperties = vi.fn((element, props) => {
    const bo = element.businessObject
    for (const key of Object.keys(props)) bo[key] = props[key]
  })
  return { updateProperties }
}

function webhookContainerOf(props) {
  return props.extensionElements.values.find(v => v.$instanceOf('flowable:Webhooks'))
}

function stubModeling(modeling) {
  useService.mockImplementation(name => (name === 'modeling' ? modeling : undefined))
}

describe('saveWebhooks 的存檔契約', () => {
  it('節點層：恰好一次 updateProperties，一次帶齊 extensionElements 與清理後的 documentation，且不弄壞 taskListener', async () => {
    const { m, rootElement, task } = await parse('', `
      <documentation>${LEGACY_DOC_PREFIX}[{"event":"create","url":"https://legacy.example/x","method":"POST"}]</documentation>
      <documentation>本關卡需主管同意後才會進入下一關</documentation>
      <extensionElements>
        <flowable:taskListener event="create" delegateExpression="\${notifyTaskListener}"/>
      </extensionElements>`)
    const element = { businessObject: task }
    const modeling = fakeModeling()

    saveWebhooks(modeling, element, [{ event: 'all', url: 'https://erp.example/all', method: 'PUT' }])

    // 一次呼叫 = undo 堆疊裡的一步。拆成兩次的話 ctrl+Z 只會走一半。
    expect(modeling.updateProperties).toHaveBeenCalledTimes(1)
    expect(modeling.updateProperties).toHaveBeenCalledWith(element, expect.any(Object))

    const props = modeling.updateProperties.mock.calls[0][1]
    // 兩個屬性必須同一批送出；少了 documentation 舊格式就永遠清不掉。
    expect(Object.keys(props).sort()).toEqual(['documentation', 'extensionElements'])
    expect(props.documentation.map(d => d.text)).toEqual(['本關卡需主管同意後才會進入下一關'])

    const values = props.extensionElements.values
    const listener = values.find(v => v.$instanceOf('flowable:TaskListener'))
    expect(listener).toBeTruthy()
    expect(listener.delegateExpression).toBe('${notifyTaskListener}')

    const container = webhookContainerOf(props)
    expect(container.values).toHaveLength(1)
    expect(container.values[0].event).toBe('all')
    expect(container.values[0].url).toBe('https://erp.example/all')
    expect(container.values[0].method).toBe('PUT')

    // 序列化一次，釘住後端 WebhookConfigResolver 讀的 tag／屬性名。
    const { xml } = await m.toXML(rootElement, { format: true })
    expect(xml).toContain('<flowable:webhooks>')
    expect(xml).toContain('<flowable:webhook ')
    expect(xml).toContain('event="all"')
    expect(xml).toContain('url="https://erp.example/all"')
    expect(xml).toContain('method="PUT"')
    expect(xml).toContain('delegateExpression="${notifyTaskListener}"')
    expect(xml).not.toContain(LEGACY_DOC_PREFIX)
  })

  it('流程層：Process element 走同一條存檔路徑，寫出 process.completed 且 XML round-trip 讀得回來', async () => {
    const { m, rootElement, process } = await parse(`
      <documentation>${LEGACY_DOC_PREFIX}[{"event":"complete","url":"https://legacy.example/old","method":"POST"}]</documentation>
      <documentation>本流程的用途說明</documentation>`)
    const element = { businessObject: process }
    const modeling = fakeModeling()

    saveWebhooks(modeling, element, [
      { event: 'process.completed', url: 'https://erp.example/done', method: 'POST' }
    ])

    expect(modeling.updateProperties).toHaveBeenCalledTimes(1)
    const props = modeling.updateProperties.mock.calls[0][1]
    expect(props.documentation.map(d => d.text)).toEqual(['本流程的用途說明'])

    const { xml } = await m.toXML(rootElement, { format: true })
    expect(xml).toContain('<flowable:webhooks>')
    expect(xml).toContain('event="process.completed"')

    // 從 XML 重新解析再讀回 —— 這是後端 resolver 會看到的同一份內容。
    const back = await m.fromXML(xml, 'bpmn:Definitions')
    const backProcess = back.rootElement.rootElements.find(p => p.id === 'p1')
    expect(readWebhooks(backProcess, 'process.completed')).toEqual([
      { event: 'process.completed', url: 'https://erp.example/done', method: 'POST', payloadTemplate: '' }
    ])
  })

  it('未填 event 的預設值依元素型別：節點 create、流程 process.completed', async () => {
    const { process, task } = await parse(
      '<extensionElements><flowable:webhooks><flowable:webhook url="https://proc.example/x" method="POST"/></flowable:webhooks></extensionElements>',
      '<extensionElements><flowable:webhooks><flowable:webhook url="https://task.example/x" method="POST"/></flowable:webhooks></extensionElements>')

    // 讀：後端對「省略」的定義在兩層不同（spec §11.4）。
    expect(readWebhooks(task)[0].event).toBe('create')
    expect(readWebhooks(process, 'process.completed')[0].event).toBe('process.completed')

    // 寫：由元素型別決定，呼叫端無從傳錯。
    const processModeling = fakeModeling()
    saveWebhooks(processModeling, { businessObject: process }, [{ url: 'https://proc.example/y', method: 'PUT' }])
    expect(webhookContainerOf(processModeling.updateProperties.mock.calls[0][1]).values[0].event)
      .toBe('process.completed')

    const taskModeling = fakeModeling()
    saveWebhooks(taskModeling, { businessObject: task }, [{ url: 'https://task.example/y', method: 'PUT' }])
    expect(webhookContainerOf(taskModeling.updateProperties.mock.calls[0][1]).values[0].event)
      .toBe('create')
  })
})

describe('事件選項分層', () => {
  it('節點層補上 all；流程層只提供流程層事件，不得混入 node-only 事件', () => {
    expect(NODE_EVENT_OPTIONS.map(o => o.value)).toEqual(['create', 'complete', 'reject', 'all'])
    expect(PROCESS_EVENT_OPTIONS.map(o => o.value)).toEqual(['process.completed', 'complete', 'all'])

    // 後端對流程層的 create／reject 直接不命中（spec §11.4）——
    // 面板提供它們等於讓使用者存下永不投遞的設定。
    const processValues = PROCESS_EVENT_OPTIONS.map(o => o.value)
    for (const nodeOnly of ['create', 'reject']) {
      expect(processValues).not.toContain(nodeOnly)
    }

    // timeout：Flowable 7.2.0 不發此事件，設計器 2026-10-02 起兩層都不提供
    //（後端保留相容，見 spec §11.4）。
    expect(NODE_EVENT_OPTIONS.map(o => o.value)).not.toContain('timeout')
    expect(processValues).not.toContain('timeout')
  })

  it('webhookConfigFor 依元素型別給對應的事件清單與預設事件', async () => {
    const { process, task } = await parse()
    expect(webhookConfigFor({ businessObject: process })).toMatchObject({
      events: PROCESS_EVENT_OPTIONS,
      defaultEvent: 'process.completed'
    })
    expect(webhookConfigFor({ businessObject: task })).toMatchObject({
      events: NODE_EVENT_OPTIONS,
      defaultEvent: 'create'
    })
  })
})

describe('面板實際掛給元件的設定', () => {
  it('流程面板的事件選單是流程層清單，未設定時顯示 process.completed，存檔只呼叫一次 updateProperties', async () => {
    const { process } = await parse(
      '<extensionElements><flowable:webhooks><flowable:webhook url="https://erp.example/x" method="POST"/></flowable:webhooks></extensionElements>')
    const element = { businessObject: process }
    const modeling = fakeModeling()
    stubModeling(modeling)

    const group = WebhookProps(element)
    expect(group.id).toBe('flowable-webhooks')
    const entry = group.entries.find(e => e.id === 'webhook-event-0')
    const vnode = entry.component({ element })

    expect(vnode.props.getOptions().map(o => o.value)).toEqual(['process.completed', 'complete', 'all'])
    // XML 省略 event（後端視為 process.completed）→ 畫面必須顯示 process.completed。
    expect(vnode.props.getValue()).toBe('process.completed')

    vnode.props.setValue('all')
    expect(modeling.updateProperties).toHaveBeenCalledTimes(1)
    expect(webhookContainerOf(modeling.updateProperties.mock.calls[0][1]).values[0].event).toBe('all')

    // 新增按鈕的預設事件也是 process.completed，不是節點的 create。
    const addVnode = group.entries.find(e => e.id === 'webhook-add').component({ element })
    addVnode.props.children.props.onclick()
    expect(modeling.updateProperties).toHaveBeenCalledTimes(2)
    const added = webhookContainerOf(modeling.updateProperties.mock.calls[1][1]).values
    expect(added.map(w => w.event)).toEqual(['all', 'process.completed'])
  })

  it('節點面板的事件選單含 all；新增按鈕的預設事件維持 create', async () => {
    const { task } = await parse('',
      '<extensionElements><flowable:webhooks><flowable:webhook event="create" url="https://erp.example/x" method="POST"/></flowable:webhooks></extensionElements>')
    const element = { businessObject: task }
    const modeling = fakeModeling()
    stubModeling(modeling)

    const group = WebhookProps(element)
    const vnode = group.entries.find(e => e.id === 'webhook-event-0').component({ element })
    expect(vnode.props.getOptions().map(o => o.value)).toEqual(['create', 'complete', 'reject', 'all'])

    const addVnode = group.entries.find(e => e.id === 'webhook-add').component({ element })
    addVnode.props.children.props.onclick()
    expect(modeling.updateProperties).toHaveBeenCalledTimes(1)
    const added = webhookContainerOf(modeling.updateProperties.mock.calls[0][1]).values
    expect(added.map(w => w.event)).toEqual(['create', 'create'])
  })

  it('payload 模板欄位（#28）：顯示現值、修改走同一次存檔、清空後 XML 不再有屬性', async () => {
    const { m, rootElement, task } = await parse('',
      '<extensionElements><flowable:webhooks>' +
      '<flowable:webhook event="create" url="https://erp.example/x" method="POST"' +
      ' payloadTemplate="{&quot;t&quot;:&quot;{{taskName}}&quot;}"/>' +
      '</flowable:webhooks></extensionElements>')
    const element = { businessObject: task }
    const modeling = fakeModeling()
    stubModeling(modeling)

    const group = WebhookProps(element)
    const entry = group.entries.find(e => e.id === 'webhook-payload-template-0')
    expect(entry, '每個 webhook entry 都必須有模板欄位').toBeTruthy()

    const vnode = entry.component({ element })
    // 讀：XML 的實體引用被解回原始模板字串。
    expect(vnode.props.getValue()).toBe('{"t":"{{taskName}}"}')

    // 寫：與其他欄位同一條路徑（一次 updateProperties），值原樣進 moddle。
    vnode.props.setValue('{"x":"{{taskId}}"}')
    expect(modeling.updateProperties).toHaveBeenCalledTimes(1)
    expect(webhookContainerOf(modeling.updateProperties.mock.calls[0][1]).values[0].payloadTemplate)
      .toBe('{"x":"{{taskId}}"}')

    // 清空：不得留下 payloadTemplate="" —— 空白模板等於未設定。
    vnode.props.setValue('')
    const { xml } = await m.toXML(rootElement, { format: true })
    expect(xml).not.toContain('payloadTemplate')
  })
})

/**
 * 非法 event 的提示選項（#67，2026-10-02 使用者裁決）。
 *
 * 情境：面板讀到清單以外的 event —— 舊檔留下的 timeout、或手改／舊版寫錯層的
 * 值（節點存了 process.completed、流程存了 create）。需求是「原值照實顯示並
 * 標示無效」，不是自動改值、也不是顯示空白：設定成功卻永不投遞，正是最需要
 * 讓使用者看見的時候。
 */
describe('非法 event 的提示選項', () => {
  function eventVnode(element) {
    const group = WebhookProps(element)
    return group.entries.find(e => e.id === 'webhook-event-0').component({ element })
  }

  it('節點面板帶流程層值 process.completed：附加無效選項、原值照實顯示、開啟面板不動存檔', async () => {
    const { task } = await parse('',
      '<extensionElements><flowable:webhooks><flowable:webhook event="process.completed" url="https://erp.example/x" method="POST"/></flowable:webhooks></extensionElements>')
    const element = { businessObject: task }
    const modeling = fakeModeling()
    stubModeling(modeling)

    const vnode = eventVnode(element)
    expect(vnode.props.getOptions()).toEqual([
      { value: 'create', label: 'create' },
      { value: 'complete', label: 'complete' },
      { value: 'reject', label: 'reject' },
      { value: 'all', label: 'all' },
      { value: 'process.completed', label: 'process.completed（無效，後端不投遞）' }
    ])
    // 原值不被偷改：顯示的就是檔案裡的值；只是打開面板不會觸發存檔。
    expect(vnode.props.getValue()).toBe('process.completed')
    expect(modeling.updateProperties).not.toHaveBeenCalled()

    // 使用者自己選回合法值 → 仍是一次 updateProperties，事件真的被改掉。
    vnode.props.setValue('complete')
    expect(modeling.updateProperties).toHaveBeenCalledTimes(1)
    expect(webhookContainerOf(modeling.updateProperties.mock.calls[0][1]).values[0].event).toBe('complete')
  })

  it('流程面板帶節點層值 create：附加無效選項，節點清單不會因此混進流程清單', async () => {
    const { process } = await parse(
      '<extensionElements><flowable:webhooks><flowable:webhook event="create" url="https://erp.example/x" method="POST"/></flowable:webhooks></extensionElements>')
    const element = { businessObject: process }
    const modeling = fakeModeling()
    stubModeling(modeling)

    const vnode = eventVnode(element)
    const options = vnode.props.getOptions()
    expect(options.map(o => o.value)).toEqual(['process.completed', 'complete', 'all', 'create'])
    expect(options[options.length - 1].label).toBe('create（無效，後端不投遞）')
    expect(vnode.props.getValue()).toBe('create')
    expect(modeling.updateProperties).not.toHaveBeenCalled()
  })

  it('合法值 all 不受影響：不附加任何選項，切換事件照常存檔', async () => {
    const { task } = await parse('',
      '<extensionElements><flowable:webhooks><flowable:webhook event="all" url="https://erp.example/x" method="POST"/></flowable:webhooks></extensionElements>')
    const element = { businessObject: task }
    const modeling = fakeModeling()
    stubModeling(modeling)

    const vnode = eventVnode(element)
    expect(vnode.props.getOptions().map(o => o.value)).toEqual(['create', 'complete', 'reject', 'all'])
    expect(vnode.props.getValue()).toBe('all')

    vnode.props.setValue('reject')
    expect(modeling.updateProperties).toHaveBeenCalledTimes(1)
    expect(webhookContainerOf(modeling.updateProperties.mock.calls[0][1]).values[0].event).toBe('reject')
  })

  it('未填 event：顯示預設值，不會被當成非法值附加', async () => {
    const { task } = await parse('',
      '<extensionElements><flowable:webhooks><flowable:webhook url="https://erp.example/x" method="POST"/></flowable:webhooks></extensionElements>')
    const element = { businessObject: task }
    const modeling = fakeModeling()
    stubModeling(modeling)

    const vnode = eventVnode(element)
    expect(vnode.props.getOptions().map(o => o.value)).toEqual(['create', 'complete', 'reject', 'all'])
    expect(vnode.props.getValue()).toBe('create')
  })

  it('舊檔留下的 timeout（已移除的選項）：仍照實顯示並標示無效，不自動改值', async () => {
    const { task } = await parse('',
      '<extensionElements><flowable:webhooks><flowable:webhook event="timeout" url="https://erp.example/x" method="POST"/></flowable:webhooks></extensionElements>')
    const element = { businessObject: task }
    const modeling = fakeModeling()
    stubModeling(modeling)

    const vnode = eventVnode(element)
    const options = vnode.props.getOptions()
    expect(options.map(o => o.value)).toEqual(['create', 'complete', 'reject', 'all', 'timeout'])
    expect(options[options.length - 1].label).toBe('timeout（無效，後端不投遞）')
    expect(vnode.props.getValue()).toBe('timeout')
    expect(modeling.updateProperties).not.toHaveBeenCalled()
  })
})
