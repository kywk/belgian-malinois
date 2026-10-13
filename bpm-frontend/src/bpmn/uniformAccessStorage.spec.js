import { describe, expect, it } from 'vitest'
import BpmnModdle from 'bpmn-moddle'
import flowableModdle, { uniformModdle } from '../composables/flowableModdle'
import { buildExtensionElements, isEmptyAccess, readAccess, UNIFORM_URI } from './uniformAccessStorage'
import { readWebhooks } from './webhookStorage'

/**
 * {@code uniform:access} 的存放格式（ADR-23、plan §五 B）。
 *
 * 這裡的每個字串都是設計器與 bpm-core 之間的契約。寫錯的後果不在設計器
 * （畫面正常、存檔成功），而是在使用者開表單的那一刻 —— 欄位權限錯誤，
 * 或後端解析不到、靜默 fail-closed 成 view＋readonly。
 *
 * 所以測試不只測「讀得到自己寫的東西」，還測：
 *   - 設計器<b>不會</b>弄壞別人已經在檔案裡的東西（taskListener、webhooks），
 *   - 出廠 seed BPMN 開啟 → 存檔後 uniform:access／field 原樣存活，
 *   - 寫出來的 tag／屬性名與後端 FormAccessResolver 讀的是同一組。
 */
function moddle() {
  return new BpmnModdle({ flowable: flowableModdle, uniform: uniformModdle })
}

/** 帶 xmlns:uniform 的圖（seed BPMN 的形狀）。 */
function shell(taskInner = '') {
  return `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:flowable="http://flowable.org/bpmn"
             xmlns:uniform="http://bpm.com/uniform"
             targetNamespace="http://bpm.com/test">
  <process id="p1" isExecutable="true">
    <startEvent id="start"/>
    <userTask id="t1" name="審核">${taskInner}</userTask>
  </process>
</definitions>`
}

/** 沒有 xmlns:uniform 的舊圖：寫入時必須自己宣告 namespace。 */
function bareShell(taskInner = '') {
  return `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:flowable="http://flowable.org/bpmn"
             targetNamespace="http://bpm.com/test">
  <process id="p1" isExecutable="true">
    <startEvent id="start"/>
    <userTask id="t1" name="審核">${taskInner}</userTask>
  </process>
</definitions>`
}

async function parse(xml) {
  const m = moddle()
  const { rootElement, warnings } = await m.fromXML(xml, 'bpmn:Definitions')
  const process = rootElement.rootElements.find(p => p.id === 'p1')
  const task = process.flowElements.find(f => f.id === 't1')
  return { m, rootElement, task, warnings }
}

function count(xml, needle) {
  return xml.split(needle).length - 1
}

describe('uniform:access 的存放格式', () => {
  it('沒有 access 時回傳空 profile（不是 null）—— 面板與呼叫端只有「空字串＝未設定」這一種形狀', async () => {
    const { task } = await parse(shell(''))
    expect(readAccess(task)).toEqual({ mode: '', default: '', fields: [] })
    expect(isEmptyAccess(readAccess(task))).toBe(true)
  })

  it('access 與 field 照檔案原樣讀出（mode／default／key／state／required；required 保留三態）', async () => {
    const { task } = await parse(shell(
      '<extensionElements>' +
      '<uniform:access mode="fill" default="readonly">' +
      '<uniform:field key="reason" state="editable" required="true"/>' +
      '<uniform:field key="note" state="hidden" required="false"/>' +
      '<uniform:field key="x" state="readonly"/>' +
      '</uniform:access></extensionElements>'))
    expect(readAccess(task)).toEqual({
      mode: 'fill',
      default: 'readonly',
      fields: [
        { key: 'reason', state: 'editable', required: 'true' },
        { key: 'note', state: 'hidden', required: 'false' },
        { key: 'x', state: 'readonly', required: '' }
      ]
    })
  })

  it('寫入後 XML 的 tag／屬性名與 seed 相同；舊圖沒有 xmlns:uniform 時自動宣告', async () => {
    const { m, rootElement, task } = await parse(bareShell(
      '<extensionElements><flowable:taskListener event="create" delegateExpression="${notifyTaskListener}"/></extensionElements>'))
    task.extensionElements = buildExtensionElements(task, {
      mode: 'fill',
      default: 'readonly',
      fields: [{ key: 'reason', state: 'editable', required: '' }]
    })

    const { xml } = await m.toXML(rootElement, { format: true })
    // 契約：xmlns:uniform="http://bpm.com/uniform"、uniform:access／uniform:field
    // 與屬性名 mode／default／key／state／required，
    // 全部是後端 FormAccessResolver 讀的那組。
    expect(xml).toContain(`xmlns:uniform="${UNIFORM_URI}"`)
    expect(xml).toContain('<uniform:access mode="fill" default="readonly">')
    expect(xml).toContain('<uniform:field key="reason" state="editable"')
    // 未設定就是沒有屬性：空 required 不得寫成 required=""。
    expect(xml).not.toContain('required=')
    expect(xml).toContain('delegateExpression="${notifyTaskListener}"')

    // 往返一次：讀得回一模一樣的 profile。
    const back = await m.fromXML(xml, 'bpmn:Definitions')
    const backTask = back.rootElement.rootElements.find(p => p.id === 'p1').flowElements.find(f => f.id === 't1')
    expect(readAccess(backTask)).toEqual({
      mode: 'fill',
      default: 'readonly',
      fields: [{ key: 'reason', state: 'editable', required: '' }]
    })
  })

  it('設定器不得弄壞同一個節點上已有的 flowable:taskListener 與 webhooks（三種 extensionElements 並存）', async () => {
    const { m, rootElement, task } = await parse(shell(
      '<extensionElements>' +
      '<uniform:access mode="view" default="readonly"/>' +
      '<flowable:taskListener event="create" delegateExpression="${notifyTaskListener}"/>' +
      '<flowable:taskListener event="all" delegateExpression="${webhookTaskListener}"/>' +
      '<flowable:webhooks><flowable:webhook event="create" url="https://erp.example/hook" method="POST"/></flowable:webhooks>' +
      '</extensionElements>'))

    task.extensionElements = buildExtensionElements(task, {
      mode: 'fill',
      default: 'readonly',
      fields: [{ key: 'reason', state: 'editable', required: 'true' }]
    })

    const { xml } = await m.toXML(rootElement, { format: true })
    expect(xml).toContain('<uniform:access mode="fill" default="readonly">')
    expect(xml).toContain('<uniform:field key="reason" state="editable" required="true"')
    expect(xml).toContain('delegateExpression="${notifyTaskListener}"')
    expect(xml).toContain('delegateExpression="${webhookTaskListener}"')
    expect(xml).toContain('<flowable:webhooks>')
    expect(xml).toContain('url="https://erp.example/hook"')

    // 讀回時三種設定都還在，且 webhook 格式沒有被動過。
    const back = await m.fromXML(xml, 'bpmn:Definitions')
    const backTask = back.rootElement.rootElements.find(p => p.id === 'p1').flowElements.find(f => f.id === 't1')
    expect(readAccess(backTask).mode).toBe('fill')
    expect(readWebhooks(backTask)).toEqual([
      { event: 'create', url: 'https://erp.example/hook', method: 'POST', payloadTemplate: '' }
    ])
  })

  it('清空 access：只移除 uniform:access，extensionElements 的其他子元素保留', async () => {
    const { m, rootElement, task } = await parse(shell(
      '<extensionElements>' +
      '<uniform:access mode="view" default="readonly"/>' +
      '<flowable:taskListener event="create" delegateExpression="${notifyTaskListener}"/>' +
      '</extensionElements>'))

    const ee = buildExtensionElements(task, { mode: '', default: '', fields: [] })
    expect(ee).toBeTruthy()
    expect(ee.values.map(v => v.$type)).toEqual(['flowable:TaskListener'])
    task.extensionElements = ee

    const { xml } = await m.toXML(rootElement, { format: true })
    expect(xml).not.toContain('uniform:access')
    expect(xml).toContain('delegateExpression="${notifyTaskListener}"')
  })

  it('清空 access 且沒有其他擴充點：整個 extensionElements 一併移除（不留空殼）', async () => {
    const { m, rootElement, task } = await parse(shell(
      '<extensionElements><uniform:access mode="view" default="readonly"/></extensionElements>'))

    expect(buildExtensionElements(task, { mode: '', default: '', fields: [] })).toBeUndefined()

    task.extensionElements = buildExtensionElements(task, { mode: '', default: '', fields: [] })
    const { xml } = await m.toXML(rootElement, { format: true })
    expect(xml).not.toContain('uniform:access')
    expect(xml).not.toContain('extensionElements')
  })

  it('seed BPMN 開啟 → 存檔：uniform:access／field 與 taskListener 必須全部存活', async () => {
    const { readFileSync, existsSync } = await import('node:fs')
    const { resolve } = await import('node:path')
    const dir = resolve(process.cwd(), '../bpmn-definitions')
    if (!existsSync(dir)) return // 單獨跑前端測試時沒有定義檔，靜默略過

    for (const name of ['leave-approval', 'purchase-approval', 'countersign-review']) {
      const xml = readFileSync(resolve(dir, `${name}.bpmn20.xml`), 'utf8')
      const { rootElement, warnings } = await moddle().fromXML(xml, 'bpmn:Definitions')
      expect(warnings.map(w => w.message), `${name} 開啟時有解析警告`).toEqual([])

      const roundTrip = (await moddle().toXML(rootElement, { format: true })).xml
      // 每個 seed 的 access 都至少一個（countersign 一個、其他兩支各兩個）；
      // 用原文的出現次數對比存檔後，少一個都逃不過。
      for (const tag of ['<uniform:access', '<uniform:field', '<flowable:taskListener ']) {
        expect(count(roundTrip, tag), `${name} 存檔後 ${tag} 的數量必須不變`)
          .toBe(count(xml, tag))
      }
      expect(count(roundTrip, '<uniform:access'), `${name} 必須有 uniform:access`).toBeGreaterThan(0)
      expect(count(roundTrip, '<flowable:taskListener '), `${name} 必須有 taskListener`).toBeGreaterThan(0)
      // countersign-review 是全唯讀（只有 access、沒有 field）；另外兩支的
      // 補件關卡有 reason 的覆寫。
      if (name !== 'countersign-review') {
        expect(count(roundTrip, '<uniform:field'), `${name} 必須有 uniform:field`).toBeGreaterThan(0)
      }
      expect(roundTrip).toContain('mode="view"')
    }
  })
})
