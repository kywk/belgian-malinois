/**
 * Form properties group for UserTask and StartEvent.
 */
import { html } from 'htm/preact'
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
  const bo = element.businessObject
  const formKey = bo.get('flowable:formKey') || ''

  return html`<${SelectEntry}
    id="formType" label="表單類型" element=${element}
    getOptions=${() => FORM_TYPES}
    getValue=${() => formKey.startsWith('external:') ? 'external' : 'internal'}
    setValue=${(v) => {
      if (v === 'external') modeling.updateProperties(element, { 'flowable:formKey': 'external:' })
      else modeling.updateProperties(element, { 'flowable:formKey': '' })
    }} />`
}

function FormValueInput(props) {
  const { element } = props
  const modeling = useService('modeling')
  const bo = element.businessObject
  const formKey = bo.get('flowable:formKey') || ''

  if (formKey.startsWith('external:')) {
    return html`<${TextFieldEntry}
      id="formValue" label="外部 URL" element=${element}
      getValue=${() => formKey.replace('external:', '')}
      setValue=${(v) => modeling.updateProperties(element, { 'flowable:formKey': 'external:' + v })}
      description="支援 {processInstanceId}, {taskId} 變數" />`
  }

  return html`<${TextFieldEntry}
    id="formValue" label="表單 formKey" element=${element}
    getValue=${() => formKey}
    setValue=${(v) => modeling.updateProperties(element, { 'flowable:formKey': v })}
    description="輸入已發佈的表單 formKey" />`
}
