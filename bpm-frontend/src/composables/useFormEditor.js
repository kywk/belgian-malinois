import { ref, computed } from 'vue'

const fields = ref([])
const selectedFieldId = ref(null)
const formName = ref('')
const formKey = ref('')
const formId = ref(null)
const dirty = ref(false)

let counter = 0

export function useFormEditor() {
  const selectedField = computed(() =>
    fields.value.find(f => f.id === selectedFieldId.value) || null
  )

  function addField(type, index) {
    counter++
    const id = type + counter
    const field = { id, type, label: labelFor(type), required: false, readonly: false }
    if (['select', 'radio', 'checkbox'].includes(type)) {
      field.options = [{ label: '選項1', value: 'opt1' }, { label: '選項2', value: 'opt2' }]
    }
    if (type === 'number') { field.min = undefined; field.max = undefined; field.precision = 0 }
    if (type === 'text') { field.placeholder = ''; field.maxLength = undefined }
    if (type === 'textarea') { field.maxLength = undefined; field.rows = 3 }
    if (type === 'file') { field.accept = ''; field.maxSize = 10; field.maxCount = 5 }
    if (type === 'link') { field.url = ''; field.openInNewWindow = true }
    if (type === 'date') { field.format = 'YYYY-MM-DD' }
    if (index != null) fields.value.splice(index, 0, field)
    else fields.value.push(field)
    dirty.value = true
    selectedFieldId.value = id
    return field
  }

  function removeField(id) {
    fields.value = fields.value.filter(f => f.id !== id)
    if (selectedFieldId.value === id) selectedFieldId.value = null
    dirty.value = true
  }

  function selectField(id) { selectedFieldId.value = id }

  function updateField(id, props) {
    const f = fields.value.find(f => f.id === id)
    if (f) { Object.assign(f, props); dirty.value = true }
  }

  function getSchema() {
    return {
      formKey: formKey.value,
      version: 1,
      mode: 'edit',
      fields: fields.value
    }
  }

  function loadSchema(schema, id, name, key) {
    formId.value = id
    formName.value = name || ''
    formKey.value = key || ''
    fields.value = schema?.fields || []
    dirty.value = false
    selectedFieldId.value = null
  }

  function reset() {
    fields.value = []
    selectedFieldId.value = null
    formName.value = ''
    formKey.value = ''
    formId.value = null
    dirty.value = false
    counter = 0
  }

  return {
    fields, selectedFieldId, selectedField, formName, formKey, formId, dirty,
    addField, removeField, selectField, updateField, getSchema, loadSchema, reset
  }
}

function labelFor(type) {
  const map = {
    text: '單行文字', textarea: '多行文字', number: '數字', date: '日期',
    dateRange: '日期區間', select: '下拉選單', radio: '單選', checkbox: '多選',
    file: '檔案上傳', orgSelector: '人員選擇', amount: '金額',
    richtext: '說明文字', link: '連結'
  }
  return map[type] || type
}
