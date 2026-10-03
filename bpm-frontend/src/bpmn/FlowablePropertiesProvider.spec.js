import { describe, expect, it, vi } from 'vitest'
import BpmnModdle from 'bpmn-moddle'
import flowableModdle from '../composables/flowableModdle'
import FlowablePropertiesProvider from './FlowablePropertiesProvider'

/**
 * ⚠️ Provider 本身不需要 DI hook，但它 import 的 WebhookProps.js 會從
 * 'bpmn-js-properties-panel' 匯入 useService。真實套件會把 bpmn-js 的 ESM
 * 拉進來，而 bpmn-js 的 lib 使用無副檔名相對匯入，Vitest 外部化後 Node 解不了
 * （ERR_MODULE_NOT_FOUND: bpmn-js/lib/util/LabelUtil）。這裡只驗 getGroups 的
 * 群組組成，不執行任何 hook，所以把該模組換成空殼是等價的。
 */
vi.mock('bpmn-js-properties-panel', () => ({ useService: vi.fn() }))

/**
 * Provider 的面板掛載（#67）。
 *
 * 這裡釘住的是一個「產品上看不到」的缺陷：後端 ProcessCompletedListener 會讀
 * <process> 的 flowable:webhooks（spec §11.4），但設計器只對 bpmn:UserTask
 * 掛 webhook 面板 —— 流程層設定沒有任何入口，畫面上完全不存在。
 *
 * 為什麼用真的 bpmn-moddle 而不是手刻假物件：`is()` 是透過
 * businessObject.$instanceOf() 判斷型別的。假物件很容易「照著實作再寫一份
 * $instanceOf」，測起來全綠但真正的 bpmn-js 元素卻不是那個形狀。
 */
function moddle() {
  return new BpmnModdle({ flowable: flowableModdle })
}

const XML = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:flowable="http://flowable.org/bpmn"
             targetNamespace="http://bpm.com/test">
  <process id="p1" isExecutable="true">
    <startEvent id="start"/>
    <userTask id="t1"/>
    <serviceTask id="s1"/>
    <callActivity id="ca1"/>
  </process>
</definitions>`

async function fixtures() {
  const { rootElement } = await moddle().fromXML(XML, 'bpmn:Definitions')
  const process = rootElement.rootElements.find(p => p.id === 'p1')
  const byId = id => process.flowElements.find(f => f.id === id)
  // bpmn-js 傳進 provider 的是 diagram-js 的 element（shape／root），
  // businessObject 才是 moddle 元素。包成同樣的形狀。
  return {
    process: { businessObject: process },
    userTask: { businessObject: byId('t1') },
    startEvent: { businessObject: byId('start') },
    serviceTask: { businessObject: byId('s1') },
    callActivity: { businessObject: byId('ca1') }
  }
}

function provider() {
  return new FlowablePropertiesProvider({ registerProvider: vi.fn() })
}

function groupIds(p, element) {
  return p.getGroups(element)([]).map(g => g.id)
}

describe('FlowablePropertiesProvider 的面板掛載', () => {
  it('bpmn:Process 必須掛上 webhook 面板 —— 後端讀 <process> 的設定，設計器就得看得到', async () => {
    const p = provider()
    const { process } = await fixtures()
    expect(groupIds(p, process)).toEqual(['flowable-webhooks'])
  })

  it('bpmn:UserTask 的既有面板不得改變', async () => {
    const p = provider()
    const { userTask } = await fixtures()
    expect(groupIds(p, userTask)).toEqual(['flowable-assignee', 'flowable-form', 'flowable-webhooks'])
  })

  it('其他元素不得長出 webhook 面板', async () => {
    const p = provider()
    const { startEvent, serviceTask } = await fixtures()
    // StartEvent 只有表單面板（既有行為），ServiceTask 完全沒有自訂面板。
    expect(groupIds(p, startEvent)).toEqual(['flowable-form'])
    expect(groupIds(p, serviceTask)).toEqual([])
  })

  it('bpmn:CallActivity 必須掛上呼叫子流程面板（#4）—— 業務人員才選得到預定義加簽子流程', async () => {
    const p = provider()
    const { callActivity } = await fixtures()
    expect(groupIds(p, callActivity)).toEqual(['flowable-call-activity'])
  })
})
