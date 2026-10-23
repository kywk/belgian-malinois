/**
 * greyhound 的 uni-form widget 覆寫（Element Plus 版）。
 *
 * 覆寫 date／dateRange／select（含 multiple）／radio／checkbox 五種型別；
 * text／textarea／number／boolean 沿用 @nanshan/uniform-element-plus，
 * grid／section／divider／freeText 沿用 SDK native 實作（見 setup.js）。
 *
 * 值語意的權威是 SDK `packages/vue/src/widgets.ts`：
 *   - select／radio 值是 option 的 scalar value（string／number／boolean）；
 *   - checkbox 與 multiple select 值是 option value 的陣列；
 *   - dateRange 值是 canonical `{start,end}`（元件內以 el-date-picker
 *     daterange 編輯，輸出時才轉 canonical）；
 *   - maxSelect 是「成長上限」：超過就整次變更丟棄（縮小不受限，
 *     既有超額資料仍可被移除）；
 *   - fill + editable 才可改；readonly 用 disabled；view 只輸出文字值；
 *   - 不在 options 裡的「原值」必須顯示（加一顆「（原值）」選項），
 *     不得默默丟掉。
 *
 * a11y 比照 SDK element-plus pack（packages/element-plus/src/widgets/basic.ts）：
 * 每個控件都有 id；錯誤狀態的 aria-invalid／aria-describedby 指向 renderer
 * 產生的 `{controlId}-errors` 清單。Element Plus 2.7.3 的兩個限制：
 * ElSelect 的 aria attrs 落在 root、焦點輸入框只吃 ariaLabel；ElDatePicker
 * 的會被內部 ElTooltip 吃掉 —— 因此 date 比照 SDK 對 ElSwitch 的做法，
 * 包一層 role=group 承載 aria-invalid／aria-describedby，輸入框拿 id 與
 * ariaLabel（含錯誤訊息）。radio／checkbox／dateRange 由 renderer 交給 widget
 * 自己畫欄位名（renderer 不產生 <label>），用 fieldset／legend 並把 id 與
 * 錯誤連結掛在 fieldset 上。
 */
import { defineComponent, h } from 'vue'
import { useDynamicOptions } from './options.js'
import { ElCheckbox, ElCheckboxGroup, ElDatePicker, ElOption, ElRadio, ElRadioGroup, ElSelect } from 'element-plus'

// ── 共用工具 ────────────────────────────────────────────────────────

function optionList(context) {
  const options = context.props['options']
  return Array.isArray(options)
    ? options.filter(option => option !== null && typeof option === 'object')
    : []
}

/** SDK widgets.ts 的 text()：null／空字串＝未填寫、物件走 JSON、其餘 String。 */
function text(value) {
  if (value == null || value === '') return '未填寫'
  if (typeof value === 'object') {
    try { return JSON.stringify(value) } catch { return '格式不支援' }
  }
  return String(value)
}

function optionLabel(option) {
  return text(option.label == null || option.label === '' ? option.value : option.label)
}

function selectedText(list, value) {
  const option = list.find(item => Object.is(item.value, value))
  return option ? optionLabel(option) : text(value)
}

function isDateRange(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value) &&
    typeof value.start === 'string' && typeof value.end === 'string'
}

function maxSelect(props) {
  const maximum = props['maxSelect']
  return Number.isInteger(maximum) && maximum >= 0 ? maximum : Infinity
}

function errorAriaLabel(context) {
  return context.errors.length
    ? [context.label, ...context.errors.map(error => error.message)].filter(Boolean).join('，')
    : context.label
}

function invalidAria(context) {
  const invalid = context.errors.length > 0
  return {
    'aria-invalid': invalid ? 'true' : undefined,
    'aria-describedby': invalid ? context.controlId + '-errors' : undefined,
  }
}

/** minDate／maxDate 是 date manifest 宣告的前端 UI 限制（valueSchema 未含）。 */
function disabledDates(props) {
  const min = typeof props['minDate'] === 'string' ? props['minDate'] : null
  const max = typeof props['maxDate'] === 'string' ? props['maxDate'] : null
  if (!min && !max) return undefined
  return (date) => {
    const day = new Date(date.getFullYear(), date.getMonth(), date.getDate())
    return (min !== null && day < new Date(min + 'T00:00:00')) ||
      (max !== null && day > new Date(max + 'T00:00:00'))
  }
}

function viewValue(context) {
  const value = context.value
  const list = optionList(context)
  if (context.node.type === 'checkbox' || (context.node.type === 'select' && context.props['multiple'] === true)) {
    if (Array.isArray(value)) {
      return value.length ? value.map(item => selectedText(list, item)).join('、') : '未填寫'
    }
    return text(value)
  }
  if (context.node.type === 'dateRange') {
    return isDateRange(value) ? text(value.start) + ' ～ ' + text(value.end) : text(value)
  }
  return selectedText(list, value)
}

function unsupportedValue(context) {
  return h('div', { class: 'uf-unsupported-value' }, [
    h('span', { class: 'uf-value' }, text(context.value)),
    h('p', { role: 'status' }, '原值格式不支援，將保持不變。'),
  ])
}

function optionsUnavailable(context, dynamic) {
  return h('div', { class: 'uf-options-unavailable' }, [
    h('span', { class: 'uf-value' }, text(context.value)),
    h('p', { role: 'status' }, dynamic?.loading.value ? '選項載入中…'
      : dynamic?.error.value || dynamic?.reason.value || '尚未設定動態選項服務，已停用選取。'),
    dynamic?.error.value ? h('button', { type: 'button', onClick: dynamic.retry }, '重新載入選項') : null,
  ])
}

// ── date ───────────────────────────────────────────────────────────

const DateField = defineComponent({
  name: 'UfGreyhoundDateField',
  props: { context: { type: Object, required: true } },
  setup(props) {
    return () => {
      const context = props.context
      if (context.mode === 'view') return h('span', { class: 'uf-value' }, viewValue(context))
      if (context.value != null && typeof context.value !== 'string') return unsupportedValue(context)
      const readonly = context.state !== 'editable'
      // ElDatePicker 2.7.3 的 aria-invalid／aria-describedby 會被內部 ElTooltip
      // 吃掉（不落在 root 或輸入框），所以比照 SDK 對 ElSwitch 的處理：
      // 外層 div[role=group] 掛 aria-invalid／aria-describedby 指向 renderer
      // 的錯誤清單，輸入框本身拿 id 與 ariaLabel（含錯誤訊息）。
      return h('div', { role: 'group', 'aria-label': context.label, ...invalidAria(context) }, [
        h(ElDatePicker, {
          id: context.controlId,
          modelValue: typeof context.value === 'string' ? context.value : '',
          type: 'date',
          valueFormat: 'YYYY-MM-DD',
          disabled: readonly,
          clearable: !readonly,
          ariaLabel: errorAriaLabel(context),
          disabledDate: disabledDates(context.props),
          'onUpdate:modelValue': (value) => {
            if (readonly) return
            context.updateValue(typeof value === 'string' && value !== '' ? value : undefined)
          },
        }),
      ])
    }
  },
})

// ── dateRange ──────────────────────────────────────────────────────

const DateRangeField = defineComponent({
  name: 'UfGreyhoundDateRangeField',
  props: { context: { type: Object, required: true } },
  setup(props) {
    return () => {
      const context = props.context
      if (context.mode === 'view') return h('span', { class: 'uf-value' }, viewValue(context))
      if (context.value != null && !isDateRange(context.value)) return unsupportedValue(context)
      const readonly = context.state !== 'editable'
      const current = isDateRange(context.value) ? [context.value.start, context.value.end] : []
      return h('fieldset', { id: context.controlId, class: 'uf-greyhound-dateRange', disabled: readonly, ...invalidAria(context) }, [
        h('legend', context.label),
        h(ElDatePicker, {
          // daterange 的兩個輸入框各自要 id（Element Plus 的 id prop 收陣列）。
          id: [context.controlId + '-start', context.controlId + '-end'],
          modelValue: current,
          type: 'daterange',
          valueFormat: 'YYYY-MM-DD',
          disabled: readonly,
          clearable: !readonly,
          startPlaceholder: '開始日期',
          endPlaceholder: '結束日期',
          rangeSeparator: '至',
          disabledDate: disabledDates(context.props),
          'onUpdate:modelValue': (value) => {
            if (readonly) return
            const next = Array.isArray(value) && typeof value[0] === 'string' && value[0] !== '' &&
              typeof value[1] === 'string' && value[1] !== ''
              ? { start: value[0], end: value[1] }
              : undefined
            context.updateValue(next)
          },
        }),
      ])
    }
  },
})

// ── select（含 multiple） ───────────────────────────────────────────

const SelectField = defineComponent({
  name: 'UfGreyhoundSelectField',
  props: { context: { type: Object, required: true } },
  setup(props) {
    const dynamic = useDynamicOptions(() => props.context)
    return () => {
      const context = props.context
      if (dynamic.source.value && (context.mode === 'view' || context.state !== 'editable')) {
        return h('span', { class: 'uf-value' }, dynamic.snapshot.value ?? text(context.value))
      }
      if (context.mode === 'view') return h('span', { class: 'uf-value' }, viewValue(context))
      if (dynamic.source.value && (dynamic.loading.value || dynamic.error.value || dynamic.reason.value)) {
        return optionsUnavailable(context, dynamic)
      }
      const multiple = context.props['multiple'] === true
      if (multiple && context.value != null && !Array.isArray(context.value)) return unsupportedValue(context)
      const readonly = context.state !== 'editable'
      const list = dynamic.source.value ? dynamic.list.value : optionList(context)
      const values = multiple && Array.isArray(context.value) ? context.value : []
      const limit = maxSelect(context.props)
      const unknown = multiple
        ? values.filter(value => !list.some(option => Object.is(option.value, value)))
        : context.value != null && !list.some(option => Object.is(option.value, context.value))
          ? [context.value]
          : []
      return h(ElSelect, {
        id: context.controlId,
        modelValue: multiple ? values : context.value,
        multiple,
        disabled: readonly,
        clearable: !readonly,
        placeholder: typeof context.props['placeholder'] === 'string' ? context.props['placeholder'] : '請選擇',
        ariaLabel: errorAriaLabel(context),
        ...invalidAria(context),
        'onUpdate:modelValue': (value) => {
          if (readonly) return
          if (multiple) {
            const selected = Array.isArray(value) ? value : []
            // SDK widgets.ts updateMany：超過 maxSelect 就丟棄整次變更；
            // 既有超額資料仍可縮小（selected.length < values.length）。
            if (selected.length > limit && selected.length >= values.length) return
            context.updateValue(selected)
          } else {
            context.updateValue(value == null || value === '' ? undefined : value)
          }
        },
      }, () => [
        ...list.map((option, index) => h(ElOption, {
          key: String(index),
          value: option.value,
          label: optionLabel(option),
          disabled: multiple && values.length >= limit && !values.some(value => Object.is(value, option.value)),
        })),
        ...unknown.map((value, index) => h(ElOption, {
          key: 'unknown-' + index,
          value,
          label: text(value) + '（原值）',
          disabled: multiple,
        })),
      ])
    }
  },
})

// ── radio ──────────────────────────────────────────────────────────

const RadioField = defineComponent({
  name: 'UfGreyhoundRadioField',
  props: { context: { type: Object, required: true } },
  setup(props) {
    const dynamic = useDynamicOptions(() => props.context)
    return () => {
      const context = props.context
      if (dynamic.source.value && (context.mode === 'view' || context.state !== 'editable')) {
        return h('span', { class: 'uf-value' }, dynamic.snapshot.value ?? text(context.value))
      }
      if (context.mode === 'view') return h('span', { class: 'uf-value' }, viewValue(context))
      if (dynamic.source.value && (dynamic.loading.value || dynamic.error.value || dynamic.reason.value)) {
        return optionsUnavailable(context, dynamic)
      }
      const readonly = context.state !== 'editable'
      const list = dynamic.source.value ? dynamic.list.value : optionList(context)
      const unknown = context.value != null && !list.some(option => Object.is(option.value, context.value))
        ? [context.value]
        : []
      return h('fieldset', { id: context.controlId, class: 'uf-greyhound-radio', disabled: readonly, ...invalidAria(context) }, [
        h('legend', context.label),
        h(ElRadioGroup, {
          modelValue: context.value,
          disabled: readonly,
          ariaLabel: context.label,
          'onUpdate:modelValue': (value) => {
            if (!readonly) context.updateValue(value)
          },
        }, () => [
          ...list.map((option, index) => h(ElRadio, { key: String(index), value: option.value }, () => optionLabel(option))),
          ...unknown.map((value, index) => h(ElRadio, { key: 'unknown-' + index, value }, () => text(value) + '（原值）')),
        ]),
      ])
    }
  },
})

// ── checkbox ───────────────────────────────────────────────────────

const CheckboxField = defineComponent({
  name: 'UfGreyhoundCheckboxField',
  props: { context: { type: Object, required: true } },
  setup(props) {
    return () => {
      const context = props.context
      if (context.mode === 'view') return h('span', { class: 'uf-value' }, viewValue(context))
      if (context.node.optionsSource) return optionsUnavailable(context)
      if (context.value != null && !Array.isArray(context.value)) return unsupportedValue(context)
      const readonly = context.state !== 'editable'
      const list = optionList(context)
      const values = Array.isArray(context.value) ? context.value : []
      const limit = maxSelect(context.props)
      const unknown = values.filter(value => !list.some(option => Object.is(option.value, value)))
      return h('fieldset', { id: context.controlId, class: 'uf-greyhound-checkbox', disabled: readonly, ...invalidAria(context) }, [
        h('legend', context.label),
        h(ElCheckboxGroup, {
          modelValue: values,
          disabled: readonly,
          ariaLabel: context.label,
          'onUpdate:modelValue': (value) => {
            if (readonly) return
            const selected = Array.isArray(value) ? value : []
            if (selected.length > limit && selected.length >= values.length) return
            context.updateValue(selected)
          },
        }, () => [
          ...list.map((option, index) => h(ElCheckbox, {
            key: String(index),
            value: option.value,
            disabled: readonly || (values.length >= limit && !values.some(value => Object.is(value, option.value))),
          }, () => optionLabel(option))),
          // 原值保留在陣列裡（SDK 語意）；以 disabled 勾選框呈現，
          // 讓它看得見但不會被這裡的編輯覆蓋。
          ...unknown.map((value, index) => h(ElCheckbox, { key: 'unknown-' + index, value, disabled: true }, () => text(value) + '（原值）')),
        ]),
      ])
    }
  },
})

export const greyhoundWidgets = [
  { type: 'date', component: DateField },
  { type: 'dateRange', component: DateRangeField },
  { type: 'select', component: SelectField },
  { type: 'radio', component: RadioField },
  { type: 'checkbox', component: CheckboxField },
]
