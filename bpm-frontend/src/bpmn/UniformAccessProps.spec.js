import { describe, expect, it, vi } from 'vitest'
import BpmnModdle from 'bpmn-moddle'
import { useService } from 'bpmn-js-properties-panel'
import flowableModdle, { uniformModdle } from '../composables/flowableModdle'
import UniformAccessProps, {
  FIELD_REQUIRED_OPTIONS,
  MODE_OPTIONS,
  STATE_OPTIONS
} from './UniformAccessProps'

/**
 * 表單權限面板（ADR-23）的存檔路徑。
 *
 * 這裡用真的 bpmn-moddle（BpmnModdle + flowableModdle + uniformModdle）與
 * 假的 modeling（記錄呼叫、比照真的 modeling.updateProperties 把屬性寫回
 * businessObject），直接驅動元件內部使用的那個 saveAccess，而不是另寫一份
 * 複製品 —— 與 WebhookProps.spec.js 同一個模式。
 *
 * 重點在「設計器按下任一欄之後真正發生的事」：
 *   - 每次改動恰好一次 updateProperties（undo 堆疊一步），
 *   - 寫出來的 XML 是後端 FormAccessResolver 讀的那組 tag／屬性，
 *   - 清空 access 只移除 uniform:access，不弄壞 taskListener，
 *   - 開啟面板本身不會動存檔、不會偷改檔案裡的值。
 */
vi.mock('bpmn-js-properties-panel', () => ({ useService: vi.fn() }))

function moddle() {
  return new BpmnModdle({ flowable: flowableModdle, uniform: uniformModdle })
}

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

async function parse(taskInner = '') {
  const m = moddle()
  const { rootElement } = await m.fromXML(shell(taskInner), 'bpmn:Definitions')
  const process = rootElement.rootElements.find(p => p.id === 'p1')
  const task = process.flowElements.find(f => f.id === 't1')
  return { m, rootElement, task }
}

/** 假的 modeling：比照真的 updateProperties（undefined → 移除屬性）。 */
function fakeModeling() {
  const updateProperties = vi.fn((element, props) => {
    const bo = element.businessObject
    for (const key of Object.keys(props)) {
      if (props[key] === undefined) delete bo[key]
      else bo[key] = props[key]
    }
  })
  return { updateProperties }
}

function stubModeling(modeling) {
  useService.mockImplementation(name => (name === 'modeling' ? modeling : undefined))
}

function group(element) {
  return UniformAccessProps(element)
}

function entry(element, id) {
  return group(element).entries.find(e => e.id === id)
}

function vnode(element, id) {
  return entry(element, id).component({ element })
}

async function xmlOf({ m, rootElement }) {
  return (await m.toXML(rootElement, { format: true })).xml
}

describe('表單權限面板的掛載與讀值', () => {
  it('群組 id／label；沒有 access 時只有 mode／default 與新增按鈕，值為空且不觸發存檔', async () => {
    const { task } = await parse('')
    const element = { businessObject: task }
    const modeling = fakeModeling()
    stubModeling(modeling)

    const g = group(element)
    expect(g.id).toBe('uniform-access')
    expect(g.label).toBe('表單權限')
    expect(g.entries.map(e => e.id)).toEqual(['uniform-access-mode', 'uniform-access-default', 'uniform-field-add'])

    const mode = vnode(element, 'uniform-access-mode')
    const fallback = vnode(element, 'uniform-access-default')
    expect(mode.props.getValue()).toBe('')
    expect(fallback.props.getValue()).toBe('')
    // 三態選單：空＝未設定，不代填後端 fail-closed 的值。
    expect(mode.props.getOptions()).toEqual(MODE_OPTIONS)
    expect(fallback.props.getOptions()).toEqual(STATE_OPTIONS)

    // 只是打開面板：不得改值、不得存檔。
    expect(modeling.updateProperties).not.toHaveBeenCalled()
  })

  it('access 與 field 照檔案內容顯示（含只有 key、沒有 state 的列）', async () => {
    const { task } = await parse(
      '<extensionElements><uniform:access mode="fill" default="readonly">' +
      '<uniform:field key="reason" state="editable" required="true"/>' +
      '<uniform:field key="note"/>' +
      '</uniform:access></extensionElements>')
    const element = { businessObject: task }
    const modeling = fakeModeling()
    stubModeling(modeling)

    const ids = group(element).entries.map(e => e.id)
    expect(ids).toEqual([
      'uniform-access-mode', 'uniform-access-default',
      'uniform-field-key-0', 'uniform-field-state-0', 'uniform-field-required-0', 'uniform-field-remove-0',
      'uniform-field-key-1', 'uniform-field-state-1', 'uniform-field-required-1', 'uniform-field-remove-1',
      'uniform-field-add'
    ])

    expect(vnode(element, 'uniform-access-mode').props.getValue()).toBe('fill')
    expect(vnode(element, 'uniform-access-default').props.getValue()).toBe('readonly')
    expect(vnode(element, 'uniform-field-key-0').props.getValue()).toBe('reason')
    expect(vnode(element, 'uniform-field-state-0').props.getValue()).toBe('editable')
    expect(vnode(element, 'uniform-field-required-0').props.getValue()).toBe('true')
    expect(vnode(element, 'uniform-field-key-1').props.getValue()).toBe('note')
    expect(vnode(element, 'uniform-field-state-1').props.getValue()).toBe('')
    expect(modeling.updateProperties).not.toHaveBeenCalled()
  })

  it('清單以外的值：附加標示無效的選項、原值照實顯示，不自動改值', async () => {
    const parsed = await parse('<extensionElements><uniform:access mode="design" default="weird"/></extensionElements>')
    const element = { businessObject: parsed.task }
    const modeling = fakeModeling()
    stubModeling(modeling)

    const mode = vnode(element, 'uniform-access-mode')
    const options = mode.props.getOptions()
    expect(options.slice(0, MODE_OPTIONS.length)).toEqual(MODE_OPTIONS)
    expect(options[options.length - 1]).toEqual({ value: 'design', label: 'design（無效，後端 fail-closed）' })
    expect(mode.props.getValue()).toBe('design')

    const fallback = vnode(element, 'uniform-access-default')
    const foptions = fallback.props.getOptions()
    expect(foptions[foptions.length - 1]).toEqual({ value: 'weird', label: 'weird（無效，後端 fail-closed）' })
    expect(fallback.props.getValue()).toBe('weird')
    expect(modeling.updateProperties).not.toHaveBeenCalled()

    // 使用者自己選回合法值：一次 updateProperties；未被動到的 default 原樣保留。
    mode.props.setValue('view')
    expect(modeling.updateProperties).toHaveBeenCalledTimes(1)
    const xml = await xmlOf(parsed)
    expect(xml).toContain('mode="view"')
    expect(xml).toContain('default="weird"')
  })
})

describe('表單權限面板的存檔契約', () => {
  it('設定 mode=view → XML 出現 <uniform:access mode="view">；未動到的 default 不寫出空屬性', async () => {
    const parsed = await parse('')
    const element = { businessObject: parsed.task }
    const modeling = fakeModeling()
    stubModeling(modeling)

    vnode(element, 'uniform-access-mode').props.setValue('view')

    expect(modeling.updateProperties).toHaveBeenCalledTimes(1)
    expect(modeling.updateProperties).toHaveBeenCalledWith(element, expect.any(Object))

    const xml = await xmlOf(parsed)
    expect(xml).toContain('<uniform:access mode="view"')
    expect(xml).not.toMatch(/<uniform:access[^>]*default=/)
  })

  it('mode 與 default 一起設定 → 同一組屬性寫出；清除 mode → 屬性消失（access 仍在）', async () => {
    const parsed = await parse('')
    const element = { businessObject: parsed.task }
    const modeling = fakeModeling()
    stubModeling(modeling)

    vnode(element, 'uniform-access-mode').props.setValue('view')
    vnode(element, 'uniform-access-default').props.setValue('readonly')

    let xml = await xmlOf(parsed)
    expect(xml).toContain('<uniform:access mode="view" default="readonly"')

    vnode(element, 'uniform-access-mode').props.setValue('')
    xml = await xmlOf(parsed)
    expect(xml).not.toContain('mode="view"')
    expect(xml).toContain('default="readonly"')
  })

  it('新增欄位 → 空白列照樣落地（面板重繪後不會消失）；填 key／state／required 後寫出完整 field', async () => {
    const parsed = await parse('')
    const element = { businessObject: parsed.task }
    const modeling = fakeModeling()
    stubModeling(modeling)

    // 先設定一個 task 層級的 access，否則新欄位會是整份 profile 的唯一內容。
    vnode(element, 'uniform-access-mode').props.setValue('fill')
    vnode(element, 'uniform-access-default').props.setValue('readonly')

    const addVnode = vnode(element, 'uniform-field-add')
    addVnode.props.children.props.onclick()
    expect(modeling.updateProperties).toHaveBeenCalledTimes(3)

    let xml = await xmlOf(parsed)
    expect(xml).toContain('<uniform:field')

    // 重繪（重新讀 businessObject）後那一列還在，而且是空白的三個欄位。
    const ids = group(element).entries.map(e => e.id)
    expect(ids).toContain('uniform-field-key-0')
    expect(vnode(element, 'uniform-field-key-0').props.getValue()).toBe('')

    vnode(element, 'uniform-field-key-0').props.setValue('reason')
    vnode(element, 'uniform-field-state-0').props.setValue('editable')
    vnode(element, 'uniform-field-required-0').props.setValue('true')

    xml = await xmlOf(parsed)
    expect(xml).toContain('<uniform:field key="reason" state="editable" required="true"')

    // 移除該列 → field 消失，access 本體與其他設定不受影響。
    vnode(element, 'uniform-field-remove-0').props.children.props.onclick()
    xml = await xmlOf(parsed)
    expect(xml).not.toContain('uniform:field')
    expect(xml).toContain('<uniform:access mode="fill" default="readonly"')
  })

  it('清除全部設定 → uniform:access 移除；節點上的 taskListener 不得被清掉', async () => {
    const parsed = await parse(
      '<extensionElements><uniform:access mode="view" default="readonly"/>' +
      '<flowable:taskListener event="create" delegateExpression="${notifyTaskListener}"/></extensionElements>')
    const element = { businessObject: parsed.task }
    const modeling = fakeModeling()
    stubModeling(modeling)

    vnode(element, 'uniform-access-mode').props.setValue('')
    vnode(element, 'uniform-access-default').props.setValue('')

    const xml = await xmlOf(parsed)
    expect(xml).not.toContain('uniform:access')
    expect(xml).toContain('delegateExpression="${notifyTaskListener}"')
  })

  it('清除全部設定且節點上沒有其他擴充點 → extensionElements 整個移除', async () => {
    const parsed = await parse('<extensionElements><uniform:access mode="view" default="readonly"/></extensionElements>')
    const element = { businessObject: parsed.task }
    const modeling = fakeModeling()
    stubModeling(modeling)

    vnode(element, 'uniform-access-mode').props.setValue('')
    vnode(element, 'uniform-access-default').props.setValue('')

    const xml = await xmlOf(parsed)
    expect(xml).not.toContain('uniform:access')
    expect(xml).not.toContain('extensionElements')
  })

  it('required 選項為三態（未設定／必填／非必填），required="false" 原樣保留', async () => {
    expect(FIELD_REQUIRED_OPTIONS.map(o => o.value)).toEqual(['', 'true', 'false'])
    expect(STATE_OPTIONS.map(o => o.value)).toEqual(['', 'hidden', 'readonly', 'editable'])

    const parsed = await parse(
      '<extensionElements><uniform:access mode="fill">' +
      '<uniform:field key="reason" state="editable" required="false"/>' +
      '</uniform:access></extensionElements>')
    const element = { businessObject: parsed.task }
    const modeling = fakeModeling()
    stubModeling(modeling)

    expect(vnode(element, 'uniform-field-required-0').props.getValue()).toBe('false')
    expect(modeling.updateProperties).not.toHaveBeenCalled()

    const xml = await xmlOf(parsed)
    expect(xml).toContain('required="false"')
  })
})
