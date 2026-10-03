import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'

/**
 * #60：StartProcess.vue 的送單 payload。
 *
 * <h3>改動前後的契約</h3>
 *
 * <p>改動前只送 {@code variables}（原始 map），後端不會落地任何表單資料；
 * 改動後送 {@code formData: { formDefinitionId, dataJson }}，由後端先驗
 * schema、再把欄位推導成流程變數並落地 {@code bpm_form_data}。
 *
 * <p>這個測試只釘住「前端送出什麼」，不重驗後端行為（後端由
 * {@code StartProcessWithFormDataTest} 覆蓋）。三件事必須成立：
 *
 * <ol>
 *   <li>payload 有 {@code formData}，且 {@code formDefinitionId} 是 BPMN 的
 *       formKey（{@code leave-request}／{@code purchase-request}）。</li>
 *   <li><b>不得</b>再送 {@code variables} —— 兩處都給會被後端以「重疊」
 *       回 400，或（若欄位不同）讓兩條來源靜默分歧。</li>
 *   <li>{@code dateRange} 維持既有的 {@code "起~訖"} 字串格式
 *       （FormSchemaValidator 同時接受它與 {@code ["起","訖"]} 陣列）。</li>
 * </ol>
 *
 * <p>⚠️ 用完整 mount 而非 shallow：按鈕在 {@code v-if="processKey"} 之下，
 * stub 掉 el-button 之後「按鈕存在但沒接線」與「按鈕不存在」會塌成同一件事。
 */

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
}))

// view 只透過共用的 axios instance 呼叫 API（CLAUDE.md 慣例），
// 因此 mock 掉 http.js 的 default export 即可，不需要 axios 本身。
vi.mock('../services/http', () => ({
  default: { post: vi.fn() },
}))

const StartProcess = (await import('./StartProcess.vue')).default
const http = (await import('../services/http')).default

const flush = () => new Promise((resolve) => setTimeout(resolve, 0))

function mountPage() {
  const pinia = createPinia()
  setActivePinia(pinia)
  return mount(StartProcess, { global: { plugins: [ElementPlus, pinia] } })
}

async function clickSubmit(wrapper) {
  const button = wrapper.findAll('button').find((b) => b.text().includes('送出申請'))
  expect(button, '畫面上必須有送出申請按鈕（否則後面的斷言是空的）').toBeTruthy()
  await button.trigger('click')
  await flush()
}

beforeEach(() => {
  http.post.mockReset()
  http.post.mockResolvedValue({
    data: { processInstanceId: 'pid-60', currentTask: { assignee: 'mgr001' } },
  })
})

describe('#60 StartProcess 以 formData 送出', () => {
  it('請假：payload 是 formData + leave-request，不含 variables，dateRange 是 "起~訖"', async () => {
    const wrapper = mountPage()
    wrapper.vm.processKey = 'leave-approval'
    await wrapper.vm.$nextTick()
    wrapper.vm.leaveForm.leaveType = 'personal'
    wrapper.vm.leaveForm.dateRange = [new Date(2026, 9, 1), new Date(2026, 9, 3)]
    wrapper.vm.leaveForm.reason = '家庭因素'

    await clickSubmit(wrapper)

    expect(http.post).toHaveBeenCalledTimes(1)
    const [url, payload] = http.post.mock.calls[0]
    expect(url).toBe('/api/process-instances')
    expect(payload.processDefinitionKey).toBe('leave-approval')
    // 兩條來源不得並存：variables 必須整個消失。
    expect(payload.variables).toBeUndefined()
    // #66：initiator 一律由後端從登入身分決定。
    expect(payload.initiator).toBeUndefined()
    expect(payload.formData.formDefinitionId).toBe('leave-request')
    expect(JSON.parse(payload.formData.dataJson)).toEqual({
      leaveType: 'personal',
      dateRange: '2026-10-01~2026-10-03',
      reason: '家庭因素',
    })
  })

  it('採購：formDefinitionId 是 purchase-request，欄位與 schema 對齊', async () => {
    const wrapper = mountPage()
    wrapper.vm.processKey = 'purchase-approval'
    await wrapper.vm.$nextTick()
    wrapper.vm.purchaseForm.itemName = '筆電'
    wrapper.vm.purchaseForm.quantity = 2
    wrapper.vm.purchaseForm.amount = 42000
    wrapper.vm.purchaseForm.reason = '汰換'

    await clickSubmit(wrapper)

    const [, payload] = http.post.mock.calls[0]
    expect(payload.variables).toBeUndefined()
    expect(payload.formData.formDefinitionId).toBe('purchase-request')
    expect(JSON.parse(payload.formData.dataJson)).toEqual({
      itemName: '筆電',
      quantity: 2,
      amount: 42000,
      reason: '汰換',
    })
  })
})
