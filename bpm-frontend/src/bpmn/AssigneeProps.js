import { h } from 'preact'
import { SelectEntry, TextFieldEntry, NumberFieldEntry } from '@bpmn-io/properties-panel'
import { useService } from 'bpmn-js-properties-panel'

const ASSIGNEE_TYPES = [
  { value: 'specific', label: '特定人員' },
  { value: 'manager1', label: '直屬主管（一階）' },
  { value: 'managerN', label: '直屬主管（N階）' },
  { value: 'permission', label: '特定權限' },
  { value: 'department', label: '特定單位' },
  { value: 'callback', label: '特定 Callback' }
]

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

  const getValue = () => {
    const a = bo.get('flowable:assignee') || ''
    const cu = bo.get('flowable:candidateUsers') || ''
    const cg = bo.get('flowable:candidateGroups') || ''
    if (cg) return 'department'
    if (cu.includes('permService')) return 'permission'
    if (a.includes('getManagerChain')) return 'managerN'
    if (a.includes('getDirectManager')) return 'manager1'
    if (a.includes('callbackService')) return 'callback'
    if (a && !a.includes('$')) return 'specific'
    return 'specific'
  }

  const setValue = (value) => {
    const p = { 'flowable:assignee': '', 'flowable:candidateUsers': '', 'flowable:candidateGroups': '' }
    switch (value) {
      case 'specific':   p['flowable:assignee'] = ''; break
      case 'manager1':   p['flowable:assignee'] = '${orgService.getDirectManager(initiator)}'; break
      case 'managerN':   p['flowable:assignee'] = '${orgService.getManagerChain(initiator, 2)[1]}'; break
      case 'permission': p['flowable:candidateUsers'] = "${permService.getUsersByPermission('')}"; break
      case 'department': p['flowable:candidateGroups'] = '${dept}'; break
      case 'callback':   p['flowable:assignee'] = '${callbackService.resolve()}'; break
    }
    modeling.updateProperties(element, p)
  }

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

  if (!assignee.includes('$') && !candidateUsers && !candidateGroups) {
    return h(TextFieldEntry, { id: 'assigneeValue', label: '指定人員 ID', element, debounce, getValue: () => assignee, setValue: (v) => modeling.updateProperties(element, { 'flowable:assignee': v }) })
  }
  if (assignee.includes('getManagerChain')) {
    const match = assignee.match(/,\s*(\d+)/)
    return h(NumberFieldEntry, { id: 'assigneeValue', label: '主管階數', element, debounce, getValue: () => match ? parseInt(match[1]) : 2, setValue: (v) => modeling.updateProperties(element, { 'flowable:assignee': '${orgService.getManagerChain(initiator, ' + v + ')[' + (v - 1) + ']}' }) })
  }
  if (candidateUsers.includes('permService')) {
    const match = candidateUsers.match(/'([^']*)'/)
    return h(TextFieldEntry, { id: 'assigneeValue', label: '權限碼', element, debounce, getValue: () => match ? match[1] : '', setValue: (v) => modeling.updateProperties(element, { 'flowable:candidateUsers': "${permService.getUsersByPermission('" + v + "')}" }) })
  }
  if (candidateGroups) {
    return h(TextFieldEntry, { id: 'assigneeValue', label: '部門代碼', element, debounce, getValue: () => candidateGroups.replace(/[${}]/g, ''), setValue: (v) => modeling.updateProperties(element, { 'flowable:candidateGroups': '${' + v + '}' }) })
  }
  if (assignee.includes('callbackService')) {
    const match = assignee.match(/resolve\('?([^)']*)'?\)/)
    return h(TextFieldEntry, { id: 'assigneeValue', label: 'Callback 名稱', element, debounce, getValue: () => match ? match[1] : '', setValue: (v) => modeling.updateProperties(element, { 'flowable:assignee': "${callbackService.resolve('" + v + "')}" }) })
  }
  return null
}
