import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, enableAutoUnmount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { UfRenderer } from '@nanshan/uniform-vue'
import { installUniformPlugin } from '../uniform/setup.js'

/**
 * StartProcess 的流程清單與送單 payload（uni-form 重構）。
 *
 * 凍結契約（docs/plan/2026-10-12-uniform-form-refactor.md §五 A.2／A.3／A.6）：
 *   - 清單來自 GET /api/process-definitions?latestVersion=true，只列
 *     有 startFormKey 的流程；選了之後 GET /api/process-definitions/{key}/start-form。
 *   - 送出 POST /api/process-instances：
 *     {processDefinitionKey, formData:{formDefinitionId: Envelope.schemaRef.key, dataJson}}。
 *     **不得**送 variables（與 formData 重疊會 400）或 initiator（#66）。
 */

const { pushMock } = vi.hoisted(() => ({ pushMock: vi.fn() }))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: pushMock }),
}))

vi.mock('../services/http', () => ({
  default: { get: vi.fn(), post: vi.fn() },
}))

enableAutoUnmount(afterEach)

const http = (await import('../services/http')).default
const StartProcess = (await import('./StartProcess.vue')).default

const flush = () => new Promise((resolve) => setTimeout(resolve, 0))

const DEFINITIONS = [
  { key: 'leave-approval', name: '請假申請', startFormKey: 'leave-request' },
  { key: 'purchase-approval', name: '採購申請', startFormKey: 'purchase-request' },
  { key: 'no-form', name: '沒有起始表單的流程', startFormKey: null },
]

const START_ENVELOPE = {
  uniform: '1.0',
  mode: 'fill',
  frame: 'form',
  schemaRef: { key: 'leave-request', version: 1, hash: 'h' },
  schema: {
    $uniform: '1.0',
    key: 'leave-request',
    requires: { core: '^1.0' },
    root: [
      { id: 'n_leaveType', type: 'select', key: 'leaveType', label: '假別', props: { options: [{ value: 'annual', label: '特休' }] } },
      { id: 'n_reason', type: 'textarea', key: 'reason', label: '事由', required: true },
    ],
  },
  data: {},
  meta: { locale: 'zh-TW', states: { leaveType: 'editable', reason: 'editable' }, required: ['reason'] },
}

function mountPage() {
  return mount(StartProcess, {
    global: { plugins: [ElementPlus, installUniformPlugin] },
  })
}

beforeEach(() => {
  http.get.mockReset()
  http.post.mockReset()
  pushMock.mockReset()
  http.get.mockImplementation((url) => {
    if (url === '/api/process-definitions') return Promise.resolve({ data: DEFINITIONS })
    if (url === '/api/process-definitions/leave-approval/start-form') {
      return Promise.resolve({ data: START_ENVELOPE })
    }
    return Promise.resolve({ data: [] })
  })
  http.post.mockResolvedValue({
    data: { processInstanceId: 'pid-60', currentTask: { assignee: 'mgr001' } },
  })
})

async function loadAndSelect(wrapper, key = 'leave-approval') {
  await flush()
  wrapper.vm.processKey = key
  await flush()
  await flush()
}

describe('StartProcess：流程清單', () => {
  it('只列有 startFormKey 的流程（label=name、value=key）', async () => {
    const wrapper = mountPage()
    await flush()

    expect(http.get).toHaveBeenCalledWith('/api/process-definitions', { params: { latestVersion: true } })
    const options = wrapper.findAllComponents({ name: 'ElOption' })
    const labels = options.map((option) => option.props('label'))
    expect(labels).toContain('請假申請')
    expect(labels).toContain('採購申請')
    expect(labels).not.toContain('沒有起始表單的流程')
    const leave = options.find((option) => option.props('label') === '請假申請')
    expect(leave.props('value')).toBe('leave-approval')
  })

  it('清單都沒有 startFormKey 時顯示空狀態提示', async () => {
    http.get.mockImplementation((url) => {
      if (url === '/api/process-definitions') {
        return Promise.resolve({ data: [{ key: 'no-form', name: '無表單流程', startFormKey: null }] })
      }
      return Promise.resolve({ data: [] })
    })
    const wrapper = mountPage()
    await flush()

    expect(wrapper.text()).toContain('沒有可發起的流程')
  })

  it('清單載入失敗顯示明確錯誤', async () => {
    http.get.mockRejectedValue(Object.assign(new Error('boom'), {
      response: { status: 500, data: { message: '後端爆炸' } },
    }))
    const wrapper = mountPage()
    await flush()

    expect(wrapper.text()).toContain('流程清單載入失敗')
    expect(wrapper.text()).toContain('後端爆炸')
  })
})

describe('StartProcess：選流程 → Envelope 表單', () => {
  it('選了流程才打 start-form，並以 UfRenderer 渲染 Envelope', async () => {
    const wrapper = mountPage()
    await loadAndSelect(wrapper)

    expect(http.get).toHaveBeenCalledWith('/api/process-definitions/leave-approval/start-form')
    const renderer = wrapper.findComponent(UfRenderer)
    expect(renderer.exists()).toBe(true)
    expect(renderer.props('envelope')).toEqual(START_ENVELOPE)
    expect(wrapper.text()).toContain('假別')
    expect(wrapper.text()).toContain('事由')
  })

  it('start-form 失敗顯示錯誤，不留下空表單', async () => {
    http.get.mockImplementation((url) => {
      if (url === '/api/process-definitions') return Promise.resolve({ data: DEFINITIONS })
      return Promise.reject(Object.assign(new Error('nf'), {
        response: { status: 404, data: { message: '找不到起始表單' } },
      }))
    })
    const wrapper = mountPage()
    await loadAndSelect(wrapper)

    expect(wrapper.text()).toContain('表單載入失敗')
    expect(wrapper.text()).toContain('找不到起始表單')
    expect(wrapper.findComponent(UfRenderer).exists()).toBe(false)
  })
})

describe('StartProcess：送出 payload', () => {
  it('formData.formDefinitionId 是 Envelope.schemaRef.key，不含 variables／initiator', async () => {
    const wrapper = mountPage()
    await loadAndSelect(wrapper)
    wrapper.vm.formData = { leaveType: 'annual', reason: '家庭因素' }
    await wrapper.vm.$nextTick()

    const submit = wrapper.findAll('button').find((button) => button.text().includes('送出申請'))
    expect(submit).toBeTruthy()
    await submit.trigger('click')
    await flush()

    expect(http.post).toHaveBeenCalledTimes(1)
    const [url, payload] = http.post.mock.calls[0]
    expect(url).toBe('/api/process-instances')
    expect(Object.keys(payload).sort()).toEqual(['formData', 'processDefinitionKey'])
    expect(payload.processDefinitionKey).toBe('leave-approval')
    expect(payload.variables).toBeUndefined()
    expect(payload.initiator).toBeUndefined()
    expect(payload.formData.formDefinitionId).toBe('leave-request')
    expect(JSON.parse(payload.formData.dataJson)).toEqual({ leaveType: 'annual', reason: '家庭因素' })
    expect(wrapper.text()).toContain('申請已送出')
  })

  it('internal validate 不通過就不送 POST', async () => {
    const wrapper = mountPage()
    await loadAndSelect(wrapper)
    // required 是「key 必須存在」（JSON Schema：camelCase 鍵不在物件裡）；
    // 空字串仍算存在，widget 在清空時是把 key 拿掉（updateValue(undefined)）。
    wrapper.vm.formData = { leaveType: 'annual' }
    await wrapper.vm.$nextTick()

    await wrapper.findAll('button').find((button) => button.text().includes('送出申請')).trigger('click')
    await flush()

    expect(http.post).not.toHaveBeenCalled()
  })
})
