<template>
  <el-form ref="formRef" :model="formData" :disabled="mode === 'readonly'" label-position="top">
    <el-form-item v-for="field in visibleFields" :key="field.id" :label="field.label"
      :required="field.required && !isReadonly(field)" :prop="field.id">

      <el-input v-if="field.type === 'text'" v-model="formData[field.id]"
        :readonly="isReadonly(field)" :placeholder="field.placeholder" :maxlength="field.maxLength" />

      <el-input v-else-if="field.type === 'textarea'" v-model="formData[field.id]"
        type="textarea" :rows="field.rows || 3" :readonly="isReadonly(field)" :maxlength="field.maxLength" />

      <el-input-number v-else-if="field.type === 'number'" v-model="formData[field.id]"
        :disabled="isReadonly(field)" :min="field.min" :max="field.max" :precision="field.precision" />

      <el-date-picker v-else-if="field.type === 'date'" v-model="formData[field.id]"
        type="date" :disabled="isReadonly(field)" value-format="YYYY-MM-DD" />

      <el-date-picker v-else-if="field.type === 'dateRange'" v-model="formData[field.id]"
        type="daterange" :disabled="isReadonly(field)" range-separator="至"
        start-placeholder="開始" end-placeholder="結束" value-format="YYYY-MM-DD" />

      <el-select v-else-if="field.type === 'select'" v-model="formData[field.id]"
        :disabled="isReadonly(field)" :placeholder="field.placeholder">
        <el-option v-for="opt in field.options" :key="opt.value" :label="opt.label" :value="opt.value" />
      </el-select>

      <el-radio-group v-else-if="field.type === 'radio'" v-model="formData[field.id]"
        :disabled="isReadonly(field)">
        <el-radio v-for="opt in field.options" :key="opt.value" :value="opt.value">{{ opt.label }}</el-radio>
      </el-radio-group>

      <el-checkbox-group v-else-if="field.type === 'checkbox'" v-model="formData[field.id]"
        :disabled="isReadonly(field)">
        <el-checkbox v-for="opt in field.options" :key="opt.value" :value="opt.value">{{ opt.label }}</el-checkbox>
      </el-checkbox-group>

      <el-upload v-else-if="field.type === 'file'" :disabled="isReadonly(field)"
        action="/api/files/upload" :limit="field.maxCount || 5">
        <el-button :disabled="isReadonly(field)">上傳檔案</el-button>
      </el-upload>

      <el-link v-else-if="field.type === 'link'" :href="field.url" target="_blank" type="primary">
        {{ field.label }}
      </el-link>

      <span v-else>不支援的欄位類型: {{ field.type }}</span>
    </el-form-item>

    <el-form-item v-if="mode === 'edit'">
      <el-button type="primary" @click="handleSubmit">提交</el-button>
    </el-form-item>
  </el-form>
</template>

<script setup>
import { ref, reactive, computed, onMounted, watch } from 'vue'
import { getFormSchema } from '../services/formApi.js'

const props = defineProps({
  formKey: { type: String, required: true },
  formVersion: { type: Number, default: null },
  processInstanceId: { type: String, default: null },
  mode: { type: String, default: 'edit' }, // edit | review | readonly
  variables: { type: Object, default: () => ({}) }
})

const emit = defineEmits(['submit'])

const fields = ref([])
const formData = reactive({})
const formRef = ref()

const visibleFields = computed(() => fields.value.filter(f => {
  // Hide readonly fields that have no value (e.g. approverComment on first submit)
  if (f.readonly === true && (formData[f.id] == null || formData[f.id] === '')) return false
  return true
}))

function isReadonly(field) {
  if (props.mode === 'readonly') return true
  if (props.mode === 'revision') return !field.editableOnRevision
  if (props.mode === 'review') return field.readonly === true
  return field.readonly === true
}

async function loadSchema() {
  try {
    const def = await getFormSchema(props.formKey, props.formVersion)
    const schema = typeof def.schemaJson === 'string' ? JSON.parse(def.schemaJson) : def.schemaJson
    fields.value = schema.fields || []
    // Init formData with defaults
    fields.value.forEach(f => {
      // In review mode, don't pre-fill editable fields (reviewer should fill fresh)
      let val = (props.mode === 'review' && f.readonly !== true) ? undefined : props.variables[f.id]
      if (f.type === 'checkbox') {
        formData[f.id] = val || []
      } else if (f.type === 'dateRange' && typeof val === 'string' && val.includes('~')) {
        formData[f.id] = val.split('~')
      } else {
        formData[f.id] = val ?? null
      }
    })
  } catch (e) {
    console.error('Failed to load form schema:', e)
  }
}

// Fill variables into form when they change
watch(() => props.variables, (vars) => {
  fields.value.forEach(f => {
    let val = vars[f.id]
    if (val !== undefined) {
      // dateRange stored as "YYYY-MM-DD~YYYY-MM-DD", convert to array for el-date-picker
      if (f.type === 'dateRange' && typeof val === 'string' && val.includes('~')) {
        val = val.split('~')
      }
      formData[f.id] = val
    }
  })
}, { deep: true })

function handleSubmit() {
  const data = {}
  fields.value.forEach(f => { data[f.id] = formData[f.id] })
  emit('submit', data)
}

onMounted(loadSchema)

defineExpose({ formData })
</script>
