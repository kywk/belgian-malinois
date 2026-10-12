import { h } from 'preact'
import { SelectEntry, TextFieldEntry } from '@bpmn-io/properties-panel'
import { useService } from 'bpmn-js-properties-panel'
import { readAccess, saveAccess } from './uniformAccessStorage'

/**
 * UserTask 的「表單權限」面板（ADR-23、uni-form 重構 plan §五 B）。
 *
 * 與 {@link ./WebhookProps} 同一個形狀：讀進 profile、每個欄位一條 entry、
 * 任一改動立刻走同一次 {@code saveAccess} 落地。掛載由
 * {@link ./FlowablePropertiesProvider} 決定（只掛 bpmn:UserTask）。
 *
 * 為什麼 mode／default 有「未設定」選項、而 state／required 也有：
 * 設計器不能替使用者做決定，也不能在「只是打開面板」時補值。未設定＝XML 上
 * 沒有該屬性，後端的 fail-closed（無 access／mode 非法 → view＋readonly）
 * 是執行期語意，不是設計器要寫進檔案的東西。
 *
 * 為什麼非法值要動態附加選項：與 WebhookProps.eventOptions 同一個理由 ——
 * 手改檔案或舊版設計器可能留下清單以外的值；SelectEntry 沒有對應選項時
 * 畫面是空白，使用者連「檔案裡存了什麼」都看不到。原值照實顯示並標示，
 * 開啟面板不會改值、不會存檔。
 */

export const MODE_OPTIONS = [
  { value: '', label: '未設定' },
  { value: 'fill', label: 'fill（填寫）' },
  { value: 'view', label: 'view（檢視）' }
]

/** state 的同一組值域：task 層級 default 與 field 覆寫都是這一組（ADR-09 §8.1）。 */
export const STATE_OPTIONS = [
  { value: '', label: '未設定' },
  { value: 'hidden', label: 'hidden（隱藏）' },
  { value: 'readonly', label: 'readonly（唯讀）' },
  { value: 'editable', label: 'editable（可編輯）' }
]

export const FIELD_REQUIRED_OPTIONS = [
  { value: '', label: '未設定' },
  { value: 'true', label: '必填' },
  { value: 'false', label: '非必填' }
]

export default function UniformAccessProps(element) {
  const bo = element.businessObject
  const access = readAccess(bo)
  const entries = [
    { id: 'uniform-access-mode', component: makeModeEntry(element, access), isEdited: () => true },
    { id: 'uniform-access-default', component: makeDefaultEntry(element, access), isEdited: () => true }
  ]

  access.fields.forEach((field, i) => {
    entries.push({ id: `uniform-field-key-${i}`, component: makeFieldKeyEntry(element, access, field, i), isEdited: () => true })
    entries.push({ id: `uniform-field-state-${i}`, component: makeFieldStateEntry(element, access, field, i), isEdited: () => true })
    entries.push({ id: `uniform-field-required-${i}`, component: makeFieldRequiredEntry(element, access, field, i), isEdited: () => true })
    entries.push({ id: `uniform-field-remove-${i}`, component: makeFieldRemoveEntry(element, access, i), isEdited: () => false })
  })

  entries.push({ id: 'uniform-field-add', component: makeFieldAddEntry(element, access), isEdited: () => false })
  return { id: 'uniform-access', label: '表單權限', entries }
}

/** 清單以外的值：附加一個標示無效的選項，原值照實顯示（不自動改值）。 */
function withCurrentOption(options, value, suffix = '（無效值）') {
  if (!value || options.some(o => o.value === value)) return options
  return [...options, { value, label: `${value}${suffix}` }]
}

function makeModeEntry(element, access) {
  return function (props) {
    const modeling = useService('modeling')
    const debounce = useService('debounceInput')
    return h(SelectEntry, {
      id: 'uniform-access-mode',
      label: '模式',
      element,
      debounce,
      description: '未設定時後端 fail-closed 為 view（全唯讀）',
      getOptions: () => withCurrentOption(MODE_OPTIONS, access.mode, '（無效，後端 fail-closed）'),
      getValue: () => access.mode,
      setValue: (v) => { access.mode = v; saveAccess(modeling, element, access) }
    })
  }
}

function makeDefaultEntry(element, access) {
  return function (props) {
    const modeling = useService('modeling')
    const debounce = useService('debounceInput')
    return h(SelectEntry, {
      id: 'uniform-access-default',
      label: '欄位預設狀態',
      element,
      debounce,
      description: '未設定時後端 fail-closed 為 readonly',
      getOptions: () => withCurrentOption(STATE_OPTIONS, access.default, '（無效，後端 fail-closed）'),
      getValue: () => access.default,
      setValue: (v) => { access.default = v; saveAccess(modeling, element, access) }
    })
  }
}

function makeFieldKeyEntry(element, access, field, i) {
  return function (props) {
    const modeling = useService('modeling')
    const debounce = useService('debounceInput')
    return h(TextFieldEntry, {
      id: `uniform-field-key-${i}`,
      label: `欄位 #${i + 1} key`,
      element,
      debounce,
      getValue: () => field.key,
      setValue: (v) => { field.key = v; saveAccess(modeling, element, access) }
    })
  }
}

function makeFieldStateEntry(element, access, field, i) {
  return function (props) {
    const modeling = useService('modeling')
    const debounce = useService('debounceInput')
    return h(SelectEntry, {
      id: `uniform-field-state-${i}`,
      label: `欄位 #${i + 1} 狀態`,
      element,
      debounce,
      description: '未設定時沿用上方預設狀態；只填 key、狀態留空的列後端不採用',
      getOptions: () => withCurrentOption(STATE_OPTIONS, field.state),
      getValue: () => field.state,
      setValue: (v) => { field.state = v; saveAccess(modeling, element, access) }
    })
  }
}

function makeFieldRequiredEntry(element, access, field, i) {
  return function (props) {
    const modeling = useService('modeling')
    const debounce = useService('debounceInput')
    return h(SelectEntry, {
      id: `uniform-field-required-${i}`,
      label: `欄位 #${i + 1} 必填`,
      element,
      debounce,
      getOptions: () => withCurrentOption(FIELD_REQUIRED_OPTIONS, field.required),
      getValue: () => field.required,
      setValue: (v) => { field.required = v; saveAccess(modeling, element, access) }
    })
  }
}

function makeFieldRemoveEntry(element, access, i) {
  return function () {
    const modeling = useService('modeling')
    return h('div', { style: 'padding:4px 8px' },
      h('button', {
        style: 'cursor:pointer',
        onclick: () => { access.fields.splice(i, 1); saveAccess(modeling, element, access) }
      }, '刪除此欄位'))
  }
}

function makeFieldAddEntry(element, access) {
  return function () {
    const modeling = useService('modeling')
    return h('div', { style: 'padding:8px' },
      h('button', {
        style: 'cursor:pointer',
        onclick: () => {
          // 空白列照樣落地：properties panel 每次存檔都重新讀 businessObject，
          // 不落地的列會在重繪後立刻消失（使用者看到「新增無效」）。
          access.fields.push({ key: '', state: '', required: '' })
          saveAccess(modeling, element, access)
        }
      }, '+ 新增欄位'))
  }
}
