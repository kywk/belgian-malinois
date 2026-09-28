import { h } from 'preact'
import { SelectEntry, TextFieldEntry, NumberFieldEntry } from '@bpmn-io/properties-panel'
import { useService } from 'bpmn-js-properties-panel'
import {
  ASSIGNEE_TYPES,
  DEFAULT_MANAGER_LEVEL,
  departmentCodeValue,
  detectAssigneeType,
  managerAtLevelExpr,
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

  const setValue = (value) => modeling.updateProperties(element, propertiesForType(value))

  return h(SelectEntry, { id: 'assigneeType', label: '審核對象類型', element, debounce, getOptions: () => ASSIGNEE_TYPES, getValue, setValue })
}

function AssigneeValueInput(props) {
  const { element } = props
  const modeling = useService('modeling')
  const debounce = useService('debounceInput')
  const bo = element.businessObject
  const assignee = bo.get('flowable:assignee') || ''
  const candidateUsers = bo.get('flowable:candidateUsers') || ''
  const candidateGroups = bo.get('flowable:candidateGroups') || ''

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
