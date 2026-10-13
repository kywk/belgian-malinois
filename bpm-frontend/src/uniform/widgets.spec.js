import { describe, it, expect, afterEach } from 'vitest'
import { mount, enableAutoUnmount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { ElCheckboxGroup, ElDatePicker, ElRadioGroup, ElSelect } from 'element-plus'
import { greyhoundWidgets } from './widgets.js'
import checkboxManifest from '@nanshan/uniform-spec/spec/types/core/checkbox.manifest.json'

/**
 * greyhound 覆寫 widgets 的值語意。
 *
 * 權威是 SDK `packages/vue/src/widgets.ts`：select／radio scalar、
 * checkbox 與 multiple select 是陣列、dateRange 是 `{start,end}`、
 * maxSelect 是成長上限、readonly 不可改、view 只顯示文字。
 * 這些語意若走鐘，形狀都是「畫面看起來正常」——例如 dateRange 送出
 * 陣列會在後端被判 400、checkbox 送出 scalar 會靜默丟掉其他勾選。
 *
 * 直接掛 widget 元件（不經 UfRenderer）並從 Element Plus 子元件
 * emit `update:modelValue`，以隔離「渲染器轉接」與「widget 值轉換」。
 */

enableAutoUnmount(afterEach)

const flush = () => new Promise((resolve) => setTimeout(resolve, 0))

function mountWidget(type, overrides = {}) {
  const updates = []
  const context = {
    node: { id: type, type, key: 'a' },
    props: {},
    mode: 'fill',
    state: 'editable',
    required: false,
    value: undefined,
    label: '欄位',
    controlId: 'field-' + type,
    errors: [],
    updateValue: (value) => updates.push(value),
    ...overrides,
  }
  const definition = greyhoundWidgets.find((widget) => widget.type === type)
  const wrapper = mount(definition.component, {
    props: { context },
    global: { plugins: [ElementPlus] },
  })
  return { wrapper, updates, context }
}

const OPTIONS = [
  { value: 'annual', label: '特休' },
  { value: 'personal', label: '事假' },
]

describe('select 值語意', () => {
  it('單選：emit 的 option value 原樣（scalar）轉交，不清洗型別', async () => {
    const { wrapper, updates } = mountWidget('select', { props: { options: OPTIONS }, value: 'annual' })
    wrapper.findComponent(ElSelect).vm.$emit('update:modelValue', 'personal')
    await flush()
    expect(updates).toEqual(['personal'])
  })

  it('單選：清空（undefined）轉交 undefined', async () => {
    const { wrapper, updates } = mountWidget('select', { props: { options: OPTIONS }, value: 'annual' })
    wrapper.findComponent(ElSelect).vm.$emit('update:modelValue', undefined)
    await flush()
    expect(updates).toEqual([undefined])
  })

  it('多選：值是 option value 陣列；空選取保留空陣列（SDK 語意）', async () => {
    const { wrapper, updates } = mountWidget('select', {
      props: { options: OPTIONS, multiple: true },
      value: ['annual'],
    })
    const select = wrapper.findComponent(ElSelect)
    expect(select.props('multiple')).toBe(true)
    select.vm.$emit('update:modelValue', ['annual', 'personal'])
    await flush()
    select.vm.$emit('update:modelValue', [])
    await flush()
    expect(updates).toEqual([['annual', 'personal'], []])
  })

  it('maxSelect：超過上限的成長被丟棄，縮小不受限', async () => {
    const { wrapper, updates } = mountWidget('select', {
      props: { options: OPTIONS, multiple: true, maxSelect: 2 },
      value: ['annual', 'personal'],
    })
    const select = wrapper.findComponent(ElSelect)
    // 已滿 2 個，再勾一個 → 丟棄整次變更
    select.vm.$emit('update:modelValue', ['annual', 'personal', 'other'])
    await flush()
    expect(updates).toEqual([])
    // 縮小可以
    select.vm.$emit('update:modelValue', ['annual'])
    await flush()
    expect(updates).toEqual([['annual']])
  })

  it('未知原值：不在 options 的值仍以「（原值）」選項顯示，不被默默丟掉', async () => {
    const { wrapper } = mountWidget('select', { props: { options: OPTIONS }, value: 'legacy-value' })
    const labels = wrapper.findAllComponents({ name: 'ElOption' }).map((option) => option.props('label'))
    expect(labels).toContain('legacy-value（原值）')
  })
})

describe('checkbox 值語意', () => {
  it('值是 option value 陣列', async () => {
    const { wrapper, updates } = mountWidget('checkbox', { props: { options: OPTIONS }, value: ['annual'] })
    wrapper.findComponent(ElCheckboxGroup).vm.$emit('update:modelValue', ['annual', 'personal'])
    await flush()
    expect(updates).toEqual([['annual', 'personal']])
  })

  it('maxSelect 上限：超過就丟棄整次變更', async () => {
    const { wrapper, updates } = mountWidget('checkbox', {
      props: { options: OPTIONS, maxSelect: 1 },
      value: ['annual'],
    })
    wrapper.findComponent(ElCheckboxGroup).vm.$emit('update:modelValue', ['annual', 'personal'])
    await flush()
    expect(updates).toEqual([])
    wrapper.findComponent(ElCheckboxGroup).vm.$emit('update:modelValue', [])
    await flush()
    expect(updates).toEqual([[]])
  })

  it('未知原值保留在陣列中並顯示（不得默默丟掉）', async () => {
    const { wrapper, updates } = mountWidget('checkbox', {
      props: { options: OPTIONS },
      value: ['legacy', 'annual'],
    })
    // 未知值以勾選框呈現
    const unknown = wrapper.findAllComponents({ name: 'ElCheckbox' })
      .filter((box) => String(box.text()).includes('legacy（原值）'))
    expect(unknown).toHaveLength(1)
    // 切換已知選項時，未知值仍在送往 renderer 的陣列裡
    wrapper.findComponent(ElCheckboxGroup).vm.$emit('update:modelValue', ['legacy', 'personal'])
    await flush()
    expect(updates).toEqual([['legacy', 'personal']])
  })

  it('manifest 的 maxSelect 是 checkbox 的宣告 props（釘住我們讀的 key）', () => {
    const keys = checkboxManifest.propsSchema.map((prop) => prop.key)
    expect(keys).toContain('maxSelect')
  })
})

describe('radio 值語意', () => {
  it('scalar option value', async () => {
    const { wrapper, updates } = mountWidget('radio', { props: { options: OPTIONS }, value: 'annual' })
    wrapper.findComponent(ElRadioGroup).vm.$emit('update:modelValue', 'personal')
    await flush()
    expect(updates).toEqual(['personal'])
  })
})

describe('dateRange 值語意', () => {
  it('fill：el-date-picker 的 [start, end] 轉成 canonical {start,end}', async () => {
    const { wrapper, updates } = mountWidget('dateRange', {
      value: { start: '2026-10-01', end: '2026-10-03' },
    })
    const picker = wrapper.findComponent(ElDatePicker)
    expect(picker.props('type')).toBe('daterange')
    picker.vm.$emit('update:modelValue', ['2026-10-05', '2026-10-06'])
    await flush()
    expect(updates).toEqual([{ start: '2026-10-05', end: '2026-10-06' }])
  })

  it('清空與半截區間 → undefined（不產生非法 canonical）', async () => {
    const { wrapper, updates } = mountWidget('dateRange', {
      value: { start: '2026-10-01', end: '2026-10-03' },
    })
    const picker = wrapper.findComponent(ElDatePicker)
    picker.vm.$emit('update:modelValue', null)
    await flush()
    picker.vm.$emit('update:modelValue', ['2026-10-05', null])
    await flush()
    expect(updates).toEqual([undefined, undefined])
  })
})

describe('a11y 連結（比照 SDK element-plus pack 的已知限制處理）', () => {
  const ERRORS = [{ path: '/a', code: 'required', message: '此欄位必填' }]

  it('date：group 掛 aria-invalid／aria-describedby；輸入框有 id 與含錯誤的 ariaLabel', async () => {
    const { wrapper } = mountWidget('date', { value: '2026-10-01', errors: ERRORS })
    await flush()
    const input = wrapper.find('input')
    expect(input.attributes('id')).toBe('field-date')
    expect(input.attributes('aria-label')).toBe('欄位，此欄位必填')
    const group = wrapper.find('[role="group"]')
    expect(group.attributes('aria-invalid')).toBe('true')
    expect(group.attributes('aria-describedby')).toBe('field-date-errors')
  })

  it('select：root 掛 aria-invalid／aria-describedby；輸入框有 id 與含錯誤的 ariaLabel', async () => {
    const { wrapper } = mountWidget('select', { props: { options: OPTIONS }, value: 'annual', errors: ERRORS })
    await flush()
    const root = wrapper.find('.el-select')
    expect(root.attributes('aria-invalid')).toBe('true')
    expect(root.attributes('aria-describedby')).toBe('field-select-errors')
    const input = wrapper.find('input')
    expect(input.attributes('id')).toBe('field-select')
    expect(input.attributes('aria-label')).toBe('欄位，此欄位必填')
  })

  it('radio／checkbox／dateRange：fieldset 是分組容器（id／legend／錯誤連結）', async () => {
    const radio = mountWidget('radio', { props: { options: OPTIONS }, value: 'annual', errors: ERRORS })
    await flush()
    const radioSet = radio.wrapper.find('fieldset')
    expect(radioSet.attributes('id')).toBe('field-radio')
    expect(radioSet.find('legend').text()).toBe('欄位')
    expect(radioSet.attributes('aria-describedby')).toBe('field-radio-errors')
    expect(radioSet.find('[role="radiogroup"]').attributes('aria-label')).toBe('欄位')

    const checkbox = mountWidget('checkbox', { props: { options: OPTIONS }, value: ['annual'], errors: ERRORS })
    await flush()
    const checkboxSet = checkbox.wrapper.find('fieldset')
    expect(checkboxSet.attributes('id')).toBe('field-checkbox')
    expect(checkboxSet.attributes('aria-invalid')).toBe('true')

    const range = mountWidget('dateRange', { value: { start: '2026-10-01', end: '2026-10-03' }, errors: ERRORS })
    await flush()
    const rangeSet = range.wrapper.find('fieldset')
    expect(rangeSet.attributes('id')).toBe('field-dateRange')
    expect(rangeSet.attributes('aria-describedby')).toBe('field-dateRange-errors')
    const ids = range.wrapper.findAll('input').map((input) => input.attributes('id'))
    expect(ids).toEqual(['field-dateRange-start', 'field-dateRange-end'])
  })
})

describe('readonly 與 view', () => {
  it('readonly：控件 disabled，emit 也不得送出（fill 只有 editable 可改）', async () => {
    const { wrapper, updates } = mountWidget('select', {
      props: { options: OPTIONS },
      value: 'annual',
      state: 'readonly',
    })
    const select = wrapper.findComponent(ElSelect)
    expect(select.props('disabled')).toBe(true)
    select.vm.$emit('update:modelValue', 'personal')
    await flush()
    expect(updates).toEqual([])
  })

  it('readonly：date／dateRange／radio／checkbox 一律 disabled', async () => {
    const radio = mountWidget('radio', { props: { options: OPTIONS }, value: 'annual', state: 'readonly' })
    expect(radio.wrapper.findComponent(ElRadioGroup).props('disabled')).toBe(true)
    const checkbox = mountWidget('checkbox', { props: { options: OPTIONS }, value: ['annual'], state: 'readonly' })
    expect(checkbox.wrapper.findComponent(ElCheckboxGroup).props('disabled')).toBe(true)
    const date = mountWidget('date', { value: '2026-10-01', state: 'readonly' })
    expect(date.wrapper.findComponent(ElDatePicker).props('disabled')).toBe(true)
    const range = mountWidget('dateRange', { value: { start: '2026-10-01', end: '2026-10-03' }, state: 'readonly' })
    expect(range.wrapper.findComponent(ElDatePicker).props('disabled')).toBe(true)
  })

  it('view：select 顯示 option label（不是 raw value），且沒有輸入控件', () => {
    const { wrapper } = mountWidget('select', { props: { options: OPTIONS }, value: 'annual', mode: 'view' })
    expect(wrapper.text()).toContain('特休')
    expect(wrapper.find('input').exists()).toBe(false)
  })

  it('view：checkbox 顯示所有已選 label、以「、」串接；未知值顯示原值', () => {
    const { wrapper } = mountWidget('checkbox', {
      props: { options: OPTIONS },
      value: ['annual', 'legacy'],
      mode: 'view',
    })
    expect(wrapper.text()).toContain('特休、legacy')
  })

  it('view：dateRange 顯示 "起 ～ 訖"', () => {
    const { wrapper } = mountWidget('dateRange', {
      value: { start: '2026-10-01', end: '2026-10-03' },
      mode: 'view',
    })
    expect(wrapper.text()).toContain('2026-10-01 ～ 2026-10-03')
  })
})
