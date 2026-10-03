import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, enableAutoUnmount } from '@vue/test-utils'
import ElementPlus from 'element-plus'

/**
 * #56：{@code DynamicForm.vue} 的 select 動態選項。
 *
 * 規則：**遠端優先、靜態 options 為 fallback、失敗不阻擋表單**。
 * 這三條都在這裡釘住，因為它們的失效形狀都是「看起來正常」：
 * 遠端沒有優先 → 畫面照常有選項（只是舊的）；失敗沒有 fallback →
 * 下拉整個空的，而使用者只覺得「這張表沒得選」；失敗阻擋表單 →
 * 外部選項來源掛掉等於整個流程收不了件。
 *
 * <h3>為什麼要從 document.body 找選項</h3>
 *
 * <p>Element Plus 的 el-select 預設 teleport 到 body，且 persistent
 * （收合時仍在 DOM）。所以選項不在 wrapper 的子樹裡，而在 body。
 * 不開 dropdown 也能斷言 —— 這裡要驗的是「資料有沒有換成遠端的」，
 * 不是 dropdown 的開合行為。
 *
 * <h3>負向控制組（2026-10-03 實測）</h3>
 *
 * <p>把 {@code optionsFor()} 的靜態 fallback 拿掉（改成
 * {@code remoteOptions[field.id] || []}）後執行本組：<b>2 紅 2 綠</b> ——
 * 「遠端失敗回退靜態」與「沒有 optionsUrl 只渲染靜態」兩條紅，證明它們
 * 對 fallback 敏感；「遠端優先」與「遠端空陣列不算失敗」不受影響。
 * 恢復後 4 綠。
 */

vi.mock('../services/formApi.js', () => ({
  getFormSchema: vi.fn(),
  getFormOptions: vi.fn(),
}))

const DynamicForm = (await import('./DynamicForm.vue')).default
const { getFormSchema, getFormOptions } = await import('../services/formApi.js')

// teleported 到 body 的 dropdown 會跟著 wrapper 一起清掉，避免跨測試殘留。
enableAutoUnmount(afterEach)

const flush = () => new Promise((resolve) => setTimeout(resolve, 0))

const selectField = (over = {}) => ({
  id: 'leaveType',
  type: 'select',
  label: '假別',
  required: true,
  options: [{ label: '靜態特休', value: 'static-annual' }],
  ...over,
})

const schemaOf = (fields) => ({
  formKey: 'leave-request',
  version: 1,
  mode: 'edit',
  fields,
})

function mockSchema(fields) {
  getFormSchema.mockResolvedValue({ schemaJson: JSON.stringify(schemaOf(fields)) })
}

function mountForm() {
  return mount(DynamicForm, {
    props: { formKey: 'leave-request' },
    global: { plugins: [ElementPlus] },
  })
}

/** el-select 的選項文字（teleport 到 body）。 */
const dropdownLabels = () =>
  [...document.body.querySelectorAll('.el-select-dropdown__item')]
    .map((el) => el.textContent.trim())

beforeEach(() => {
  mockSchema([selectField({ optionsUrl: 'https://hr.example.com/options' })])
  getFormOptions.mockResolvedValue([{ label: '遠端特休', value: 'annual' }])
})

describe('DynamicForm 的動態選項（#56）', () => {
  it('select 有 optionsUrl → 遠端選項優先，靜態 options 被取代', async () => {
    const wrapper = mountForm()
    await flush()
    await flush()

    expect(getFormOptions).toHaveBeenCalledWith('https://hr.example.com/options')
    expect(dropdownLabels()).toContain('遠端特休')
    expect(dropdownLabels()).not.toContain('靜態特休')
    expect(wrapper.find('.options-hint-error').exists()).toBe(false)
  })

  it('遠端失敗 → 回退靜態 options、顯示提示，且表單照常可提交', async () => {
    getFormOptions.mockRejectedValue(new Error('502 Bad Gateway'))

    const wrapper = mountForm()
    await flush()
    await flush()

    expect(dropdownLabels()).toContain('靜態特休')
    expect(wrapper.find('.options-hint-error').exists()).toBe(true)
    // 不阻擋表單：提交按鈕還在、formData 仍已初始化。
    expect(wrapper.text()).toContain('提交')
    expect(wrapper.vm.formData).toHaveProperty('leaveType')
  })

  it('沒有 optionsUrl → 完全不呼叫代理端點，只渲染靜態 options', async () => {
    mockSchema([selectField()])

    const wrapper = mountForm()
    await flush()
    await flush()

    expect(getFormOptions).not.toHaveBeenCalled()
    expect(dropdownLabels()).toContain('靜態特休')
  })

  it('遠端成功但回空陣列 → 空就是答案，不回頭用靜態 options', async () => {
    // 「抓取失敗」與「上游說目前沒有選項」是兩件事：後者不該被 fallback 蓋掉，
    // 否則上游清空選項時，畫面會出現一份已經不存在的舊清單。
    getFormOptions.mockResolvedValue([])

    const wrapper = mountForm()
    await flush()
    await flush()

    expect(dropdownLabels()).not.toContain('靜態特休')
    expect(wrapper.find('.options-hint-error').exists()).toBe(false)
  })
})
