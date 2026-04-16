/**
 * Assignee properties group for UserTask.
 * Provides assignee type selector and corresponding EL expression generation.
 */
import { html } from 'htm/preact'
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
      {
        id: 'assigneeType',
        component: AssigneeTypeSelect,
        isEdited: () => true
      },
      {
        id: 'assigneeValue',
        component: AssigneeValueInput,
        isEdited: () => true
      }
    ]
  }
}

function AssigneeTypeSelect(props) {
  const { element } = props
  const modeling = useService('modeling')
  const bo = element.businessObject

  // Detect current type from existing attributes
  const getValue = () => {
    const assignee = bo.get('flowable:assignee') || ''
    const candidateUsers = bo.get('flowable:candidateUsers') || ''
    const candidateGroups = bo.get('flowable:candidateGroups') || ''
    if (candidateGroups) return 'department'
    if (candidateUsers.includes('permService')) return 'permission'
    if (assignee.includes('getManagerChain')) return 'managerN'
    if (assignee.includes('getDirectManager')) return 'manager1'
    if (assignee && !assignee.includes('$')) return 'specific'
    return 'specific'
  }

  const setValue = (value) => {
    const props = { 'flowable:assignee': '', 'flowable:candidateUsers': '', 'flowable:candidateGroups': '' }
    if (value === 'manager1') {
      props['flowable:assignee'] = '${orgService.getDirectManager(initiator)}'
    }
    modeling.updateProperties(element, props)
  }

  return html`<${SelectEntry}
    id="assigneeType" label="審核對象類型" element=${element}
    getOptions=${() => ASSIGNEE_TYPES}
    getValue=${getValue} setValue=${setValue} />`
}

function AssigneeValueInput(props) {
  const { element } = props
  const modeling = useService('modeling')
  const bo = element.businessObject
  const assignee = bo.get('flowable:assignee') || ''
  const candidateUsers = bo.get('flowable:candidateUsers') || ''
  const candidateGroups = bo.get('flowable:candidateGroups') || ''

  // Specific user
  if (!assignee.includes('$') && !candidateUsers && !candidateGroups) {
    return html`<${TextFieldEntry}
      id="assigneeValue" label="指定人員 ID" element=${element}
      getValue=${() => assignee}
      setValue=${(v) => modeling.updateProperties(element, { 'flowable:assignee': v })} />`
  }

  // Manager N
  if (assignee.includes('getManagerChain')) {
    const match = assignee.match(/,\s*(\d+)/)
    return html`<${NumberFieldEntry}
      id="assigneeValue" label="主管階數" element=${element}
      getValue=${() => match ? parseInt(match[1]) : 2}
      setValue=${(v) => modeling.updateProperties(element, {
        'flowable:assignee': '$' + '{orgService.getManagerChain(initiator, ' + v + ')[' + (v-1) + ']}'
      })} />`
  }

  // Permission
  if (candidateUsers.includes('permService')) {
    const match = candidateUsers.match(/'([^']*)'/)
    return html`<${TextFieldEntry}
      id="assigneeValue" label="權限碼" element=${element}
      getValue=${() => match ? match[1] : ''}
      setValue=${(v) => modeling.updateProperties(element, {
        'flowable:candidateUsers': '$' + "{permService.getUsersByPermission('" + v + "')}"
      })} />`
  }

  // Department
  if (candidateGroups) {
    return html`<${TextFieldEntry}
      id="assigneeValue" label="部門代碼" element=${element}
      getValue=${() => candidateGroups.replace(/[${}]/g, '')}
      setValue=${(v) => modeling.updateProperties(element, {
        'flowable:candidateGroups': '$' + '{' + v + '}'
      })} />`
  }

  return null
}
