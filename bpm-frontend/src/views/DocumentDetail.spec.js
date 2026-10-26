import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, enableAutoUnmount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { UfRenderer } from '@nanshan/uniform-vue'
import { installUniformPlugin } from '../uniform/setup.js'

/**
 * DocumentDetail 的表單區（uni-form 重構）。
 *
 * 凍結契約（docs/plan/2026-10-12-uniform-form-refactor.md §五 A.1／A.5）：
 *   - 表單由 GET /api/tasks/{id}/form 的 Envelope 驅動，mode 決定按鈕列：
 *     fill → 只有「補件重送」；view → 審核按鈕（同意／退件／拒絕／轉發／加簽）。
 *   - 補件重送去 internal validate，通過才 PUT /api/tasks/{id}，
 *     body 是 {action:'complete', formData:{dataJson}}，**不再送 variables**。
 *   - 載入失敗（404／網路）顯示明確錯誤，不得留空白。
 *
 * http.js 是唯一 API 入口（CLAUDE.md 慣例），因此 mock 它就能同時
 * 控制 flowableApi 的 getTasks／getSubtasks 與 uniform/api.js 的表單。
 */

const { pushMock } = vi.hoisted(() => ({ pushMock: vi.fn() }))

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { taskId: 't-1' } }),
  useRouter: () => ({ push: pushMock, back: vi.fn() }),
}))

vi.mock('../services/http', () => ({
  default: { get: vi.fn(), put: vi.fn(), post: vi.fn() },
}))

enableAutoUnmount(afterEach)

const http = (await import('../services/http')).default
const DocumentDetail = (await import('./DocumentDetail.vue')).default

const flush = () => new Promise((resolve) => setTimeout(resolve, 0))

const TASK = {
  taskId: 't-1',
  taskName: '主管審核',
  assignee: 'mgr001',
  processInstanceId: 'pid-1',
  processDefinitionKey: 'leave-approval',
  formKey: 'leave-request',
  onBehalfOf: null,
}

const TASK_REVISION = { ...TASK, taskName: '補件' }

const SCHEMA = {
  $uniform: '1.0',
  key: 'leave-request',
  requires: { core: '^1.0' },
  root: [
    { id: 'n_reason', type: 'textarea', key: 'reason', label: '事由', required: true },
  ],
}

function envelope(mode, data) {
  return {
    uniform: '1.0',
    mode,
    frame: 'form',
    schemaRef: { key: 'leave-request', version: 1, hash: 'h' },
    schema: SCHEMA,
    data,
    meta: {
      locale: 'zh-TW',
      states: { reason: mode === 'fill' ? 'editable' : 'readonly' },
      required: ['reason'],
    },
  }
}

function mockLoad({ task = TASK_REVISION, form } = {}) {
  http.get.mockImplementation((url) => {
    if (url === '/api/tasks') return Promise.resolve({ data: [task] })
    if (url === '/api/tasks/t-1/form') {
      if (form instanceof Error) return Promise.reject(form)
      return Promise.resolve({ data: form })
    }
    // countersign 子任務
    if (url.startsWith('/api/countersign/')) return Promise.resolve({ data: [] })
    return Promise.resolve({ data: [] })
  })
}

function mountPage() {
  const pinia = createPinia()
  setActivePinia(pinia)
  return mount(DocumentDetail, {
    global: {
      plugins: [ElementPlus, pinia, installUniformPlugin],
      stubs: {
        ProcessDiagram: true,
        ApprovalTimeline: true,
        CommentPanel: true,
        ActionDialog: true,
        CountersignDialog: true,
      },
    },
  })
}

const buttonWithText = (wrapper, text) =>
  wrapper.findAll('button').find((button) => button.text().includes(text))

beforeEach(() => {
  http.get.mockReset()
  http.put.mockReset()
  http.post.mockReset()
  pushMock.mockReset()
  http.put.mockResolvedValue({ data: { formDataId: 'fd-1' } })
})

describe('DocumentDetail：Envelope.mode=fill（補件）', () => {
  it('顯示補件重送、不顯示審核按鈕，並渲染 UfRenderer', async () => {
    mockLoad({ form: envelope('fill', { reason: '家庭因素' }) })
    const wrapper = mountPage()
    await flush()
    await flush()

    expect(wrapper.findComponent(UfRenderer).exists()).toBe(true)
    expect(buttonWithText(wrapper, '補件重送')).toBeTruthy()
    expect(buttonWithText(wrapper, '同意')).toBeFalsy()
    expect(buttonWithText(wrapper, '退件')).toBeFalsy()
  })

  it('通過 internal validate 後 PUT /api/tasks/{id}，body 含 formData.dataJson 且不含 variables', async () => {
    mockLoad({ form: envelope('fill', { reason: '家庭因素' }) })
    const wrapper = mountPage()
    await flush()
    await flush()

    await buttonWithText(wrapper, '補件重送').trigger('click')
    await flush()

    expect(http.put).toHaveBeenCalledTimes(1)
    const [url, body] = http.put.mock.calls[0]
    expect(url).toBe('/api/tasks/t-1')
    expect(body.action).toBe('complete')
    expect(body.variables).toBeUndefined()
    expect(body.assignee).toBeUndefined()
    expect(JSON.parse(body.formData.dataJson)).toEqual({ reason: '家庭因素' })
    expect(pushMock).toHaveBeenCalledWith('/tasks')
  })

  it('internal validate 不通過（缺必填）就不送 PUT，並顯示錯誤', async () => {
    mockLoad({ form: envelope('fill', {}) })
    const wrapper = mountPage()
    await flush()
    await flush()

    await buttonWithText(wrapper, '補件重送').trigger('click')
    await flush()

    expect(http.put).not.toHaveBeenCalled()
    // renderer 同步把錯誤畫在畫面上（renderer 的錯誤清單）
    expect(wrapper.find('.uf-errors').exists()).toBe(true)
  })
})

describe('DocumentDetail：Envelope.mode=view（審核）', () => {
  it('顯示審核按鈕列、不顯示補件重送', async () => {
    mockLoad({ task: TASK, form: envelope('view', { reason: '家庭因素' }) })
    const wrapper = mountPage()
    await flush()
    await flush()

    expect(wrapper.findComponent(UfRenderer).exists()).toBe(true)
    for (const label of ['同意', '退件', '拒絕', '轉發', '加簽']) {
      expect(buttonWithText(wrapper, label), `缺按鈕：${label}`).toBeTruthy()
    }
    expect(buttonWithText(wrapper, '補件重送')).toBeFalsy()
  })
})

describe('DocumentDetail：表單載入失敗', () => {
  it('404 顯示後端訊息，不留空白', async () => {
    const notFound = Object.assign(new Error('Not Found'), {
      response: { status: 404, data: { message: '此任務不是動態表單任務' } },
    })
    mockLoad({ form: notFound })
    const wrapper = mountPage()
    await flush()
    await flush()

    expect(wrapper.text()).toContain('表單載入失敗')
    expect(wrapper.text()).toContain('此任務不是動態表單任務')
    expect(wrapper.findComponent(UfRenderer).exists()).toBe(false)
    // fail-closed：Envelope 不在時不得露出任何任務動作（尤其審核按鈕）。
    expect(buttonWithText(wrapper, '同意')).toBeFalsy()
    expect(buttonWithText(wrapper, '補件重送')).toBeFalsy()
    expect(wrapper.text()).toContain('表單載入完成後才能執行任務操作')
  })

  it('回應缺 envelope.data（格式不正確）也不破畫面', async () => {
    mockLoad({ form: {} })
    const wrapper = mountPage()
    await flush()
    await flush()

    expect(wrapper.text()).toContain('表單載入失敗')
    expect(wrapper.text()).toContain('表單回應格式不正確')
  })
})

describe('DocumentDetail：轉發與加簽', () => {
  it('受轉發人只能完成交回，不能直接核准或補件', async () => {
    mockLoad({ task: { ...TASK, delegationState: 'PENDING', owner: 'mgr002' }, form: envelope('fill', { reason: '驗證' }) })
    const wrapper = mountPage()
    await flush()
    await flush()
    expect(buttonWithText(wrapper, '完成轉發並交回')).toBeTruthy()
    expect(buttonWithText(wrapper, '同意')).toBeFalsy()
    expect(buttonWithText(wrapper, '補件重送')).toBeFalsy()
  })

  it('加簽子任務使用專用完成 API，不載入流程評論與歷程', async () => {
    mockLoad({ task: { ...TASK, parentTaskId: 'parent-1', processInstanceId: null, formKey: null, description: '請協助確認' }, form: envelope('view', { reason: '驗證' }) })
    const wrapper = mountPage()
    await flush()
    await flush()
    expect(wrapper.text()).toContain('請協助確認')
    expect(http.get).toHaveBeenCalledWith('/api/tasks/t-1/form')
    expect(wrapper.findComponent(UfRenderer).exists()).toBe(true)
    expect(wrapper.find('comment-panel-stub').exists()).toBe(false)
    expect(wrapper.find('approval-timeline-stub').exists()).toBe(false)
    expect(buttonWithText(wrapper, '同意')).toBeFalsy()
    const complete = buttonWithText(wrapper, '完成加簽')
    expect(complete.attributes('disabled')).toBeDefined()
    await wrapper.find('textarea[aria-label="加簽意見"]').setValue('已確認')
    await complete.trigger('click')
    await flush()
    expect(http.put).toHaveBeenCalledWith('/api/countersign/parent-1/t-1/complete', { opinion: '已確認' })
    expect(http.get.mock.calls.some(([url]) => url === '/api/countersign/t-1')).toBe(false)
    expect(pushMock).toHaveBeenCalledWith('/tasks')
  })
})


describe('DocumentDetail：加簽等待與載入失敗', () => {
  it('父任務等待加簽期間禁止審核結案動作', async () => {
    mockLoad({ task: TASK, form: envelope('view', { reason: '驗證' }) })
    const get = http.get.getMockImplementation()
    http.get.mockImplementation((url, ...args) => url === '/api/countersign/t-1'
      ? Promise.resolve({ data: [{ taskId: 'sub-1', assignee: 'user002', name: '加簽' }] })
      : get(url, ...args))
    const wrapper = mountPage()
    await flush()
    await flush()
    for (const label of ['同意', '退件', '拒絕']) expect(buttonWithText(wrapper, label).attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('等待加簽 1 人完成')
  })

  it('加簽表單載入失敗即使填寫意見仍不可完成', async () => {
    mockLoad({ task: { ...TASK, parentTaskId: 'parent-1', formKey: null }, form: new Error('載入失敗') })
    const wrapper = mountPage()
    await flush()
    await flush()
    await wrapper.find('textarea[aria-label="加簽意見"]').setValue('已確認')
    expect(buttonWithText(wrapper, '完成加簽').attributes('disabled')).toBeDefined()
    expect(http.put).not.toHaveBeenCalled()
  })
})

describe('DocumentDetail：候選任務認領', () => {
  it('認領前禁止審核與批註，成功後依伺服器清單顯示審核動作', async () => {
    mockLoad({ task: { ...TASK, assignee: null }, form: envelope('view', { reason: '驗證' }) })
    const wrapper = mountPage()
    await flush()
    await flush()
    expect(buttonWithText(wrapper, '同意')).toBeFalsy()
    expect(wrapper.findComponent({ name: 'CommentPanel' }).props('readonly')).toBe(true)
    mockLoad({ task: TASK, form: envelope('view', { reason: '驗證' }) })
    await buttonWithText(wrapper, '認領任務').trigger('click')
    await flush()
    await flush()
    expect(http.put).toHaveBeenCalledWith('/api/tasks/t-1', { action: 'claim' })
    expect(buttonWithText(wrapper, '認領任務')).toBeFalsy()
    expect(buttonWithText(wrapper, '同意')).toBeTruthy()
  })

  it('409競爭認領後刷新清單，消失的任務不得顯示審核動作', async () => {
    mockLoad({ task: { ...TASK, assignee: null }, form: envelope('view', { reason: '驗證' }) })
    const wrapper = mountPage()
    await flush()
    await flush()
    http.put.mockRejectedValue(Object.assign(new Error('已被認領'), { response: { status: 409, data: { message: '已被認領' } } }))
    http.get.mockResolvedValue({ data: [] })
    await buttonWithText(wrapper, '認領任務').trigger('click')
    await flush()
    await flush()
    expect(buttonWithText(wrapper, '同意')).toBeFalsy()
    expect(buttonWithText(wrapper, '認領任務')).toBeFalsy()
    expect(pushMock).toHaveBeenCalledWith('/tasks')
  })
})
