import { h } from 'preact'
import { SelectEntry, TextFieldEntry, NumberFieldEntry } from '@bpmn-io/properties-panel'
import { useService } from 'bpmn-js-properties-panel'
import {
  ASSIGNEE_TYPES,
  DEFAULT_MANAGER_LEVEL,
  departmentCodeValue,
  detectAssigneeType,
  managerAtLevelExpr,
  dynamicManagerAtLevelExpr,
  firstAvailableExpr,
  managerWithPermissionExpr,
  managerLevelError,
  parseDynamicAssignee,
  propertiesForType,
  usersByPermissionExpr
} from './assigneeExpressions'

// 運算式的產生與反推都在 assigneeExpressions.js —— 那些字串是與 Flowable
// 引擎之間唯一的介面，寫錯的後果不在前端而在使用者送出表單的那一刻，
// 所以獨立出去以便單獨測試並與後端的 EL 白名單比對（security-audit P2-6）。

export default function AssigneeProps(element) {
  return {
    id: 'flowable-assignee',
    label: '審核對象',
    entries: [
      { id: 'assigneeType', component: AssigneeTypeSelect, isEdited: () => true },
      { id: 'assigneeValue', component: AssigneeValueInput, isEdited: () => true }
    ]
  }
}

function AssigneeTypeSelect(props) {
  const { element } = props
  const modeling = useService('modeling')
  const debounce = useService('debounceInput')
  const bo = element.businessObject

  const getValue = () => detectAssigneeType({
    assignee: bo.get('flowable:assignee') || '',
    candidateUsers: bo.get('flowable:candidateUsers') || '',
    candidateGroups: bo.get('flowable:candidateGroups') || ''
  })

  const setValue = (value) => {
    if (value !== 'custom') modeling.updateProperties(element, propertiesForType(value))
  }

  return h(SelectEntry, {
    id: 'assigneeType', label: '審核對象類型', element, debounce,
    getOptions: () => getValue() === 'custom'
      ? [...ASSIGNEE_TYPES, { value: 'custom', label: '自訂運算式（保留原值）' }]
      : ASSIGNEE_TYPES,
    getValue, setValue
  })
}

function AssigneeValueInput(props) {
  const { element } = props
  const modeling = useService('modeling')
  const debounce = useService('debounceInput')
  return assigneeValueVNode({ element, modeling, debounce })
}

/** 與面板共用的欄位決策及存檔路徑，可直接驗證產生的控制項。 */
export function assigneeValueVNode({ element, modeling, debounce }) {
  const bo = element.businessObject
  const assignee = bo.get('flowable:assignee') || ''
  const candidateUsers = bo.get('flowable:candidateUsers') || ''
  const candidateGroups = bo.get('flowable:candidateGroups') || ''

  const type = detectAssigneeType({ assignee, candidateUsers, candidateGroups })
  const dynamic = parseDynamicAssignee(assignee)

  if (type === 'custom') {
    return h('div', { className: 'bio-properties-panel-description' },
      h('p', null, '此指派運算式保留原值。若要使用引導設定，請選擇其他審核對象類型。'),
      ...[assignee, candidateUsers, candidateGroups].filter(Boolean).map(value =>
        h('pre', { style: { whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' } }, value)))
  }

  if (dynamic?.type === 'dynamicManager') {
    return h(NumberFieldEntry, {
      id: 'assigneeValue', label: '發起人的主管階數', element, debounce,
      min: 1, max: 2147483647, step: 1,
      description: '1 代表直屬主管；主管鏈不足時取最高階，並套用代理人。完全沒有主管時無法建立任務。',
      getValue: () => dynamic.level,
      validate: managerLevelError,
      setValue: value => {
        if (!managerLevelError(value)) modeling.updateProperties(element, {
          'flowable:assignee': dynamicManagerAtLevelExpr(value)
        })
      }
    })
  }

  if (dynamic) {
    const build = dynamic.type === 'firstAvailable' ? firstAvailableExpr : managerWithPermissionExpr
    return h(TextFieldEntry, {
      id: 'assigneeValue', label: '指定權限碼', element, debounce,
      description: dynamic.type === 'firstAvailable'
        ? '直接指派給第一位可受理的權限持有人；全員不在時使用第一位的代理人。找不到持有人時無法建立任務。'
        : '沿發起人的主管鏈，由近到遠找第一位持有此權限的主管，並套用代理人。找不到時無法建立任務。',
      getValue: () => dynamic.permCode,
      validate: value => !String(value ?? '').trim() ? '請輸入單一權限碼' : undefined,
      setValue: value => modeling.updateProperties(element, { 'flowable:assignee': build(value) })
    })
  }

  if (assignee.includes('getManagerAtLevel') || assignee.includes('getManagerChain')) {
    const match = assignee.match(/,\s*(\d+)/)
    return h(NumberFieldEntry, {
      id: 'assigneeValue', label: '主管階數', element, debounce,
      getValue: () => (match ? parseInt(match[1]) : DEFAULT_MANAGER_LEVEL),
      // 舊圖即使存的是 getManagerChain，使用者一改階數就寫回安全的形式 ——
      // 這讓修正自動遷移，不需要另外跑一次資料轉換。
      setValue: (v) => modeling.updateProperties(element, {
        'flowable:assignee': managerAtLevelExpr(v)
      })
    })
  }

  if (candidateUsers.includes('permService')) {
    const match = candidateUsers.match(/'([^']*)'/)
    return h(TextFieldEntry, {
      id: 'assigneeValue', label: '權限碼', element, debounce,
      getValue: () => (match ? match[1] : ''),
      setValue: (v) => modeling.updateProperties(element, {
        'flowable:candidateUsers': usersByPermissionExpr(v)
      })
    })
  }

  // 「發起人所屬單位」是完整的運算式，沒有要填的值。
  if (candidateGroups.includes('getDeptId')) return null

  // 特定人員：assignee 是字面的人員 ID。
  if (!assignee.includes('$') && !candidateUsers && !candidateGroups) {
    return h(TextFieldEntry, {
      id: 'assigneeValue', label: '指定人員 ID', element, debounce,
      getValue: () => assignee,
      setValue: (v) => modeling.updateProperties(element, { 'flowable:assignee': v })
    })
  }

  if (candidateGroups) {
    return h(TextFieldEntry, {
      id: 'assigneeValue', label: '部門代碼', element, debounce,
      // 舊圖存的是 ${dept001} 這種變數參照，顯示時去掉 ${}，
      // 寫回時存成字面值 —— 使用者一編輯就自動遷移。
      getValue: () => candidateGroups.replace(/[${}]/g, ''),
      // ⚠️ 不可再包 ${}（security-audit P2-6）。原本是 '${' + v + '}'，
      // 所以使用者輸入 dept001 會被存成 ${dept001} —— 一個不存在的流程變數。
      //
      // 這個錯誤部署時抓不到：BpmnLintService 的 EL 白名單 regex 是
      // \$\{(\w+)\. —— 必須有「點」才匹配，所以 ${dept001} 完全不被檢查。
      // 部署順利通過，等到使用者送出表單、引擎求值 candidateGroups 時，
      // 才拋 Unknown property used in expression。
      setValue: (v) => modeling.updateProperties(element, {
        'flowable:candidateGroups': departmentCodeValue(v)
      })
    })
  }

  return null
}
