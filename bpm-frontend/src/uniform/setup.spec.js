import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { UfRenderer } from '@nanshan/uniform-vue'
import { installUniformPlugin, manifests } from './setup.js'

/**
 * plugin 安裝的接線證明。
 *
 * setup.js 的三份資源（manifests／value schemas／widgets）任一沒接好，
 * createUniform 都**不會**拋錯 —— 症狀會延後到 renderer：
 * manifests 缺 → typeUnsupported；validation 缺 → validationMissing；
 * valueSchemaResources 缺 → ajv 解析不到 $ref。所以這裡不驗「app.use 沒拋」，
 * 而是真的掛一個 UfRenderer 並跑 validate()。
 */

const envelope = (data, states = {}) => ({
  uniform: '1.0',
  mode: 'fill',
  frame: 'form',
  schemaRef: { key: 'smoke', version: 1, hash: 'test' },
  schema: {
    $uniform: '1.0',
    key: 'smoke',
    requires: { core: '^1.0' },
    root: [
      { id: 'n_name', type: 'text', key: 'name', label: '名稱', required: true },
      { id: 'n_kind', type: 'select', key: 'kind', label: '類型', props: { options: [{ value: 'a', label: 'A' }] } },
      { id: 'n_day', type: 'date', key: 'day', label: '日期' },
      { id: 'n_tags', type: 'checkbox', key: 'tags', label: '標籤', props: { options: [{ value: 'x', label: 'X' }] } },
    ],
  },
  data,
  meta: {
    locale: 'zh-TW',
    states: { name: 'editable', kind: 'editable', day: 'editable', tags: 'editable', ...states },
    required: ['name'],
  },
})

function mountRenderer(env, modelValue) {
  return mount(UfRenderer, {
    props: { envelope: env, modelValue },
    global: { plugins: [installUniformPlugin] },
  })
}

describe('installUniformPlugin', () => {
  it('manifests 只載入 core 頂層（v2 的 opt-in 檔案不得混入）', () => {
    const richtext = manifests.filter((manifest) => manifest.type === 'richtext')
    expect(richtext).toHaveLength(1)
    expect(richtext[0].version).toBe('1.0.0')
    for (const type of ['text', 'textarea', 'number', 'boolean', 'select', 'radio', 'checkbox', 'date', 'dateRange', 'divider', 'freeText', 'grid', 'section']) {
      expect(manifests.some((manifest) => manifest.type === type), `缺 ${type} manifest`).toBe(true)
    }
  })

  it('掛上 renderer 後 validate() 可跑：合法資料 0 error（widgets／validation 都到位）', async () => {
    const wrapper = mountRenderer(
      envelope({ name: 'n', kind: 'a', day: '2026-10-01', tags: ['x'] }),
      { name: 'n', kind: 'a', day: '2026-10-01', tags: ['x'] },
    )
    const errors = wrapper.vm.validate()
    expect(errors).toEqual([])
    expect(wrapper.text()).not.toContain('尚未安裝')
    expect(wrapper.text()).not.toContain('尚未設定驗證')
    expect(wrapper.text()).not.toContain('尚未支援')
  })

  it('缺必填會被 createCoreValidation 抓到（errors 以 path 定位）', async () => {
    const wrapper = mountRenderer(envelope({}), {})
    const errors = wrapper.vm.validate()
    expect(errors.some((error) => error.path === '/name' && error.code === 'required')).toBe(true)
  })

  it('value schemas 已註冊：date 值不符格式會報錯（ajv 解析得到 $ref）', async () => {
    const data = { name: 'n', day: '2026/10/01' }
    const wrapper = mountRenderer(envelope(data), data)
    const errors = wrapper.vm.validate()
    expect(errors.length).toBeGreaterThan(0)
    expect(errors.some((error) => error.path === '/day')).toBe(true)
  })
})
