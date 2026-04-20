import { h } from 'preact'
import { SelectEntry, TextFieldEntry } from '@bpmn-io/properties-panel'
import { useService } from 'bpmn-js-properties-panel'

const FORM_TYPES = [
  { value: 'internal', label: '內建表單' },
  { value: 'external', label: '外部連結' }
]

export default function FormProps(element) {
  return {
    id: 'flowable-form',
    label: '表單設定',
    entries: [
      { id: 'formType', component: FormTypeSelect, isEdited: () => true },
      { id: 'formValue', component: FormValueInput, isEdited: () => true }
    ]
  }
}

function FormTypeSelect(props) {
  const { element } = props
  const modeling = useService('modeling')
  const debounce = useService('debounceInput')
  const bo = element.businessObject
  const formKey = bo.get('flowable:formKey') || ''

  return h(SelectEntry, {
    id: 'formType', label: '表單類型', element, debounce,
    getOptions: () => FORM_TYPES,
    getValue: () => formKey.startsWith('external:') ? 'external' : 'internal',
    setValue: (v) => modeling.updateProperties(element, { 'flowable:formKey': v === 'external' ? 'external:' : '' })
  })
}

function FormValueInput(props) {
  const { element } = props
  const modeling = useService('modeling')
  const debounce = useService('debounceInput')
  const bo = element.businessObject
  const formKey = bo.get('flowable:formKey') || ''

  if (formKey.startsWith('external:')) {
    return h(TextFieldEntry, {
      id: 'formValue', label: '外部 URL', element, debounce,
      getValue: () => formKey.replace('external:', ''),
      setValue: (v) => modeling.updateProperties(element, { 'flowable:formKey': 'external:' + v }),
      description: '支援 {processInstanceId}, {taskId} 變數'
    })
  }

  return h(TextFieldEntry, {
    id: 'formValue', label: '表單 formKey', element, debounce,
    getValue: () => formKey,
    setValue: (v) => modeling.updateProperties(element, { 'flowable:formKey': v }),
    description: '輸入已發佈的表單 formKey'
  })
}
