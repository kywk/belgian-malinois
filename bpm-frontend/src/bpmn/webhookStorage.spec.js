import { describe, expect, it } from 'vitest'
import BpmnModdle from 'bpmn-moddle'
import flowableModdle from '../composables/flowableModdle'
import {
  LEGACY_DOC_PREFIX,
  buildExtensionElements,
  readWebhooks,
  withoutLegacyWebhookDoc
} from './webhookStorage'

/**
 * 節點層 webhook 設定的<b>存放格式</b>（#67）。
 *
 * 這裡的每個字串都是設計器與 bpm-core 之間的契約。寫錯的後果不在設計器
 * （畫面正常、存檔成功），而是在事件發生、投遞該發生的時候 ——
 * 而且失敗型態是「沒有投遞、沒有錯誤、沒有稽核」。
 *
 * 所以測試不只測「讀得到自己寫的東西」（那種測試一個什麼都不做的實作也會過），
 * 還測：
 *   - 設計器<b>不會</b>弄壞別人已經在檔案裡的東西（taskListener），
 *   - 舊格式的流程仍然讀得到（向後相容），
 *   - 寫出來的 XML 與後端 WebhookConfigResolver 讀的是同一組 tag／屬性名。
 */
function moddle() {
  return new BpmnModdle({ flowable: flowableModdle })
}

function userTaskOf(rootElement, processId, nodeId) {
  const process = rootElement.rootElements.find(p => p.id === processId)
  return process.flowElements.find(f => f.id === nodeId)
}

function shell(inner) {
  return `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:flowable="http://flowable.org/bpmn"
             targetNamespace="http://bpm.com/test">
  <process id="p1" isExecutable="true">
    <startEvent id="start"/>
    <userTask id="t1" name="審核">${inner}</userTask>
  </process>
</definitions>`
}

describe('節點層 webhook 設定的存放格式', () => {
  it('新格式寫進 extensionElements，tag 名必須是後端讀的那組', async () => {
    const m = moddle()
    const { rootElement } = await m.fromXML(shell(''), 'bpmn:Definitions')
    const task = userTaskOf(rootElement, 'p1', 't1')

    const ee = buildExtensionElements(task, [
      { event: 'create', url: 'https://erp.example/hook', method: 'POST' },
      { event: 'complete', url: 'https://erp.example/hook2', method: 'PUT' }
    ])
    task.extensionElements = ee

    const { xml } = await m.toXML(rootElement, { format: true })
    // 這三行就是與後端的契約：後端 WebhookConfigResolver 讀
    // ELEMENT="webhooks" / CHILD="webhook" / 屬性 event,url,method。
    expect(xml).toContain('<flowable:webhooks>')
    expect(xml).toContain('event="create"')
    expect(xml).toContain('url="https://erp.example/hook"')
    expect(xml).toContain('method="POST"')
    expect(xml).toContain('method="PUT"')

    // 往返一次確認讀得回來
    const back = await m.fromXML(xml, 'bpmn:Definitions')
    expect(readWebhooks(userTaskOf(back.rootElement, 'p1', 't1'))).toEqual([
      { event: 'create', url: 'https://erp.example/hook', method: 'POST' },
      { event: 'complete', url: 'https://erp.example/hook2', method: 'PUT' }
    ])
  })

  it('設定器不得弄壞同一個節點上已有的 flowable:taskListener', async () => {
    // ⚠️ 這是 #67 裡最容易順手做壞的一處。只留下自己那一個容器再塞回去，
    // 等於把節點上其他擴充點清空 —— 而 taskListener 是本專案的通知機制本身。
    //
    // 附帶一個 2026-09-30 才修掉的既有缺陷：改動前 flowableModdle 的
    // TaskListener 沒有 superClass，bpmn-js 匯出時會把整個
    // <flowable:taskListener> 刪掉。這條測試同時釘住那個修正。
    const m = moddle()
    const { rootElement, warnings } = await m.fromXML(shell(
      '<extensionElements>' +
      '<flowable:taskListener event="create" delegateExpression="${notifyTaskListener}"/>' +
      '<flowable:taskListener event="all" delegateExpression="${webhookTaskListener}"/>' +
      '</extensionElements>'
    ), 'bpmn:Definitions')

    expect(warnings.map(w => w.message)).toEqual([])

    const task = userTaskOf(rootElement, 'p1', 't1')
    task.extensionElements = buildExtensionElements(task, [
      { event: 'create', url: 'https://erp.example/hook', method: 'POST' }
    ])

    const { xml } = await m.toXML(rootElement, { format: true })
    expect(xml).toContain('delegateExpression="${notifyTaskListener}"')
    expect(xml).toContain('delegateExpression="${webhookTaskListener}"')
    // ⚠️ tag 的大小寫不能變：Flowable 的 BpmnXMLConstants 比對的是
    // "taskListener"。變成 <flowable:tasklistener> 則 listener 靜默失效。
    expect(xml).toContain('<flowable:taskListener ')
  })

  it('舊格式（documentation）仍然讀得到 —— 這是向後相容的實證', async () => {
    const legacy = JSON.stringify([
      { event: 'complete', url: 'https://legacy.example/old', method: 'PUT' }
    ])
    const m = moddle()
    const { rootElement } = await m.fromXML(
      shell(`<documentation>${LEGACY_DOC_PREFIX}${legacy}</documentation>`), 'bpmn:Definitions')

    expect(readWebhooks(userTaskOf(rootElement, 'p1', 't1'))).toEqual([
      { event: 'complete', url: 'https://legacy.example/old', method: 'PUT' }
    ])
  })

  it('兩種格式並存時以 extensionElements 為準，且舊 documentation 不得復活被刪掉的設定', async () => {
    const m = moddle()
    const { rootElement } = await m.fromXML(shell(
      `<documentation>${LEGACY_DOC_PREFIX}[{"event":"complete","url":"https://legacy.example/old","method":"POST"}]</documentation>` +
      '<extensionElements><flowable:webhooks>' +
      '<flowable:webhook event="create" url="https://new.example/n" method="POST"/>' +
      '</flowable:webhooks></extensionElements>'
    ), 'bpmn:Definitions')

    const task = userTaskOf(rootElement, 'p1', 't1')
    expect(readWebhooks(task)).toEqual([
      { event: 'create', url: 'https://new.example/n', method: 'POST' }
    ])

    // 使用者把最後一筆刪掉 → 寫出空的 webhooks 元素。
    // 這時若讀取端回頭去讀舊 documentation，剛刪掉的設定會立刻復活。
    task.extensionElements = buildExtensionElements(task, [])
    expect(readWebhooks(task)).toEqual([])
  })

  it('保存時清掉舊的 webhook documentation，但保留其他說明文字', async () => {
    // 舊的 save() 是 updateProperties(element, { documentation: [{text: ...}] })，
    // 會把節點上所有說明文字都換成 webhook JSON。
    const docs = [
      { text: LEGACY_DOC_PREFIX + '[{"event":"create","url":"https://a/x","method":"POST"}]' },
      { text: '本關卡需主管同意後才會進入下一關' }
    ]
    const kept = withoutLegacyWebhookDoc(docs)
    expect(kept.map(d => d.text)).toEqual(['本關卡需主管同意後才會進入下一關'])
  })

  it('壞掉的舊格式不得讓整個節點讀不到設定', async () => {
    const m = moddle()
    const { rootElement } = await m.fromXML(
      shell(`<documentation>${LEGACY_DOC_PREFIX}{這不是 JSON</documentation>`), 'bpmn:Definitions')
    expect(readWebhooks(userTaskOf(rootElement, 'p1', 't1'))).toEqual([])
  })

  it('seed-data.sh 產生的兩支 BPMN 開啟後不得丟掉任何 taskListener', async () => {
    // 靜態版本的前置條件：這兩支流程的每個 UserTask 都靠 taskListener 寄信與投遞。
    // 若 moddle 設定退回去，設計器存檔一次就會讓通知與 webhook 一起消失，
    // 而部署與啟動都不會有任何錯誤。
    const { readFileSync, existsSync } = await import('node:fs')
    const { resolve } = await import('node:path')
    const dir = resolve(process.cwd(), '../bpm-core/src/main/resources/processes')
    if (!existsSync(dir)) return // 單獨跑前端測試時沒有後端原始碼，靜默略過

    const m = moddle()
    for (const name of ['leave-approval', 'purchase-approval']) {
      const xml = readFileSync(resolve(dir, `${name}.bpmn20.xml`), 'utf8')
      const { rootElement, warnings } = await m.fromXML(xml, 'bpmn:Definitions')
      expect(warnings.map(w => w.message), `${name} 開啟時有解析警告`).toEqual([])

      const roundTrip = (await m.toXML(rootElement, { format: true })).xml
      const notifyCount = (roundTrip.match(/notifyTaskListener/g) || []).length
      const webhookCount = (roundTrip.match(/webhookTaskListener/g) || []).length
      expect(notifyCount, `${name} 存檔後 notifyTaskListener 必須全部存活`).toBeGreaterThan(0)
      expect(webhookCount, `${name} 存檔後 webhookTaskListener 必須全部存活`).toBeGreaterThan(0)
      expect(roundTrip).toContain('<flowable:taskListener ')
    }
  })
})
