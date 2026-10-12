import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, enableAutoUnmount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { UfDesigner, UfRenderer } from '@nanshan/uniform-vue'
import { installUniformPlugin } from '../uniform/setup.js'

/**
 * FormEditor 的 DSL 存檔／預覽契約。
 *
 *   - GET /api/forms/{id} 的 schemaJson 是 DSL 字串 → JSON.parse 後
 *     交給 UfDesigner（v-model）。
 *   - 存檔（PUT /api/forms/{id}）的 schemaJson 必須是 **JSON 字串**
 *     （後端 compileStatic gate 吃字串），不是物件。
 *   - 預覽打 POST /api/forms/preview，body 同樣是 {schemaJson: "<字串>"}，
 *     回應的 Envelope 用 UfRenderer 畫在 dialog 裡。
 */

const { pushMock } = vi.hoisted(() => ({ pushMock: vi.fn() }))

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: 'leave-request' } }),
  useRouter: () => ({ push: pushMock }),
}))

vi.mock('../services/http', () => ({
  default: { get: vi.fn(), put: vi.fn(), post: vi.fn() },
}))

enableAutoUnmount(afterEach)

const http = (await import('../services/http')).default
const FormEditor = (await import('./FormEditor.vue')).default

const flush = () => new Promise((resolve) => setTimeout(resolve, 0))

const DSL = {
  $uniform: '1.0',
  key: 'leave-request',
  title: { 'zh-TW': '請假申請表' },
  requires: { core: '^1.0' },
  root: [
    { id: 'n_leaveType', type: 'select', key: 'leaveType', label: '假別', required: true, props: { options: [{ value: 'annual', label: '特休' }] } },
    { id: 'n_reason', type: 'textarea', key: 'reason', label: '事由', required: true },
  ],
}

const PREVIEW_ENVELOPE = {
  uniform: '1.0',
  mode: 'fill',
  frame: 'form',
  schemaRef: { key: 'leave-request', version: 1, hash: 'h' },
  schema: DSL,
  data: {},
  meta: { locale: 'zh-TW', states: { leaveType: 'editable', reason: 'editable' }, required: ['reason'] },
}

function mountPage() {
  return mount(FormEditor, {
    global: { plugins: [ElementPlus, installUniformPlugin] },
  })
}

beforeEach(() => {
  document.querySelectorAll('.el-message').forEach((node) => node.remove())
  http.get.mockReset()
  http.put.mockReset()
  http.post.mockReset()
  http.get.mockResolvedValue({
    data: { id: 7, name: '請假申請表', formKey: 'leave-request', schemaJson: JSON.stringify(DSL) },
  })
  http.put.mockResolvedValue({ data: { id: 7 } })
  http.post.mockResolvedValue({ data: PREVIEW_ENVELOPE })
})

describe('FormEditor：載入既有表單', () => {
  it('schemaJson 字串 parse 成 DSL 交給 UfDesigner', async () => {
    const wrapper = mountPage()
    await flush()
    await flush()

    expect(http.get).toHaveBeenCalledWith('/api/forms/leave-request', { params: {} })
    const designer = wrapper.findComponent(UfDesigner)
    expect(designer.exists()).toBe(true)
    const model = designer.props('modelValue')
    expect(model.$uniform).toBe('1.0')
    expect(model.key).toBe('leave-request')
    expect(model.root).toHaveLength(2)
    expect(designer.props('palette')).toEqual(
      ['text', 'textarea', 'number', 'select', 'radio', 'checkbox', 'boolean', 'date', 'dateRange', 'divider', 'freeText'],
    )
  })
})

describe('FormEditor：儲存', () => {
  it('PUT /api/forms/{id} 的 schemaJson 是 JSON 字串（含完整 DSL）', async () => {
    const wrapper = mountPage()
    await flush()
    await flush()

    await wrapper.findAll('button').find((button) => button.text().includes('儲存草稿')).trigger('click')
    await flush()

    expect(http.put).toHaveBeenCalledTimes(1)
    const [url, payload] = http.put.mock.calls[0]
    expect(url).toBe('/api/forms/7')
    expect(payload.name).toBe('請假申請表')
    expect(payload.formKey).toBe('leave-request')
    expect(typeof payload.schemaJson).toBe('string')
    const parsed = JSON.parse(payload.schemaJson)
    expect(parsed.$uniform).toBe('1.0')
    expect(parsed.key).toBe('leave-request')
    expect(parsed.root).toHaveLength(2)
  })

  it('後端以 400 回 DSL 不合法時顯示後端訊息', async () => {
    http.put.mockRejectedValue(Object.assign(new Error('bad'), {
      response: { status: 400, data: { message: '表單定義不合法：未知欄位型別' } },
    }))
    const wrapper = mountPage()
    await flush()
    await flush()

    await wrapper.findAll('button').find((button) => button.text().includes('儲存草稿')).trigger('click')
    await flush()

    expect(document.querySelector('.el-message')?.textContent ?? '').toContain('儲存失敗')
  })
})

describe('FormEditor：預覽', () => {
  it('POST /api/forms/preview 帶 schemaJson 字串，回應 Envelope 以 UfRenderer 顯示', async () => {
    const wrapper = mountPage()
    await flush()
    await flush()

    await wrapper.findAll('button').find((button) => button.text().includes('預覽')).trigger('click')
    await flush()
    await flush()

    expect(http.post).toHaveBeenCalledTimes(1)
    const [url, payload] = http.post.mock.calls[0]
    expect(url).toBe('/api/forms/preview')
    expect(typeof payload.schemaJson).toBe('string')
    expect(JSON.parse(payload.schemaJson).key).toBe('leave-request')

    const renderer = wrapper.findComponent(UfRenderer)
    expect(renderer.exists()).toBe(true)
    expect(renderer.props('envelope')).toEqual(PREVIEW_ENVELOPE)
  })

  it('預覽 400 時顯示後端訊息，不留空白 dialog', async () => {
    http.post.mockRejectedValue(Object.assign(new Error('bad'), {
      response: { status: 400, data: { message: 'DSL 不合法' } },
    }))
    const wrapper = mountPage()
    await flush()
    await flush()

    await wrapper.findAll('button').find((button) => button.text().includes('預覽')).trigger('click')
    await flush()
    await flush()

    expect(wrapper.findComponent(UfRenderer).exists()).toBe(false)
    expect(wrapper.text()).toContain('預覽失敗')
    expect(wrapper.text()).toContain('DSL 不合法')
  })
})
