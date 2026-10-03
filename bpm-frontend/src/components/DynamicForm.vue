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
        <el-option v-for="opt in optionsFor(field)" :key="opt.value" :label="opt.label" :value="opt.value" />
      </el-select>
      <div v-if="optionsLoading[field.id]" class="options-hint">選項載入中…</div>
      <div v-else-if="optionsError[field.id]" class="options-hint options-hint-error">
        選項載入失敗，已改用預設選項
      </div>

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
import { getFormSchema, getFormOptions } from '../services/formApi.js'

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

/**
 * 動態選項（#56）：以欄位 id 為 key 的遠端結果／狀態。
 *
 * 規則：**遠端優先、靜態 options 為 fallback**。
 * - 抓取成功 → optionsFor() 回遠端清單（即使空陣列，那也是上游的答案）。
 * - 抓取失敗 → remoteOptions 沒有這個 key，回退 schema 的靜態 options，
 *   並顯示提示；表單照常可填可送（選項載入失敗不阻擋表單）。
 */
const remoteOptions = reactive({})
const optionsLoading = reactive({})
const optionsError = reactive({})

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
    // 動態選項：不 await —— 遠端抓取不阻擋表單渲染，成功後選項自然補上。
    fields.value.forEach(f => {
      if (f.type === 'select' && f.optionsUrl) loadRemoteOptions(f)
    })
  } catch (e) {
    console.error('Failed to load form schema:', e)
  }
}

/**
 * select 欄位的遠端選項。成功 → 覆蓋靜態 options；失敗 → 保留靜態 fallback。
 *
 * 失敗時只記錄與顯示提示，不 throw：呼叫端是 fire-and-forget，
 * 而表單可用性不依賴外部選項來源（見 remoteOptions 的註解）。
 */
async function loadRemoteOptions(field) {
  optionsLoading[field.id] = true
  optionsError[field.id] = false
  try {
    remoteOptions[field.id] = await getFormOptions(field.optionsUrl)
  } catch (e) {
    optionsError[field.id] = true
    console.warn(`動態選項載入失敗（${field.id} / ${field.optionsUrl}），改用靜態選項:`, e)
  } finally {
    optionsLoading[field.id] = false
  }
}

/** 遠端優先、靜態 fallback；兩者都沒有時空陣列。 */
function optionsFor(field) {
  return remoteOptions[field.id] ?? field.options ?? []
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

<style scoped>
.options-hint { font-size: 12px; color: #909399; line-height: 1.4; }
.options-hint-error { color: #e6a23c; }
</style>
