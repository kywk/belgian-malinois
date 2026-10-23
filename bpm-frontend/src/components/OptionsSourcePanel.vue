<template>
  <section class="options-source-panel" aria-label="動態選項來源設定">
    <h3>選項來源</h3>
    <p>單選下拉、單選鈕與複選可使用本人部門成員；相依參數可綁定其他文字或單選欄位。</p>
    <el-select v-model="fieldId" aria-label="設定選項來源的欄位" placeholder="選擇欄位" :disabled="blocked || draftDirty">
      <el-option v-for="item in fields" :key="item.id" :value="item.id" :label="fieldLabel(item)" />
    </el-select>
    <template v-if="field">
      <el-select :model-value="source" aria-label="選項來源" placeholder="靜態選項"
        :disabled="readonly || blocked || draftDirty || loading || !!error" @update:model-value="applySource">
        <el-option value="" label="靜態選項" />
        <el-option v-for="provider in available" :key="provider.provider" :value="provider.provider" :label="provider.label" />
        <el-option v-if="source && !available.some(item => item.provider === source)" :value="source"
          :label="source + '（來源尚未支援）'" disabled />
      </el-select>
      <p v-if="field.optionsSource">動態模式不使用靜態清單；身分與部門由伺服器判定。</p>
      <template v-if="available.some(item => item.provider === source)">
        <div v-for="param in optionParameters" :key="param.name" class="parameter-row">
          <label :for="'option-param-' + param.name">{{ param.label }}</label>
          <el-select :id="'option-param-' + param.name" :model-value="draft[param.name].mode"
            :aria-label="param.label + '綁定方式'" :disabled="readonly || blocked" @update:model-value="setDraft(param.name, 'mode', $event)">
            <el-option value="literal" label="固定文字" />
            <el-option value="field" label="其他欄位" />
          </el-select>
          <el-input v-if="draft[param.name].mode === 'literal'" :model-value="draft[param.name].literal"
            :aria-label="param.label + '固定文字'" :maxlength="param.maxLength" placeholder="留空表示不篩選"
            :disabled="readonly || blocked" @update:model-value="setDraft(param.name, 'literal', $event)" />
          <el-select v-else :model-value="draft[param.name].field" :aria-label="param.label + '來源欄位'"
            placeholder="選擇來源欄位" :disabled="readonly || blocked" @update:model-value="setDraft(param.name, 'field', $event)">
            <el-option v-for="item in candidates" :key="item.key" :value="item.key" :label="fieldLabel(item)" />
          </el-select>
        </div>
        <p>來源欄位未填寫或被隱藏時，伺服器會略過該參數；不因此要求必填。</p>
        <p v-if="parameterError" role="alert">{{ parameterError }}</p>
        <div v-if="draftDirty" class="parameter-actions">
          <el-button :disabled="readonly || blocked || !!parameterError" @click="applyParameters">套用參數</el-button>
          <el-button :disabled="readonly || blocked" @click="resetDraft">取消參數變更</el-button>
        </div>
      </template>
      <p v-if="source && !supportedSource" role="alert">現有來源設定不受支援。請修正參數，或切回靜態選項後重新選擇來源。</p>
    </template>
    <p v-if="blocked" role="status">請先套用或修正設計器內的欄位設定，再變更來源。</p>
    <p v-if="draftDirty" role="status">參數尚未套用，儲存、發布與預覽暫停。</p>
    <p v-if="loading" role="status">來源清冊載入中…</p>
    <div v-if="error" role="alert">{{ error }} <el-button @click="load">重試</el-button></div>
  </section>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { fetchOptionProviders } from '../uniform/api.js'
import { optionParameters, parameterCandidates, parameterReference, scalarChoiceFields, supportsDynamicSource, topLevelFields } from '../uniform/options.js'
const props = defineProps({ modelValue: Object, readonly: Boolean, blocked: Boolean })
const emit = defineEmits(['update:modelValue', 'pending', 'invalid'])
const fieldId = ref('')
const catalog = ref([])
const loading = ref(false)
const error = ref('')
const draft = ref({})
const draftDirty = ref(false)
let generation = 0
let controller
const fields = computed(() => scalarChoiceFields(props.modelValue?.root))
const field = computed(() => fields.value.find(node => node.id === fieldId.value))
const source = computed(() => field.value?.optionsSource?.provider || '')
const candidates = computed(() => parameterCandidates(props.modelValue?.root, field.value))
const supportedSource = computed(() => !source.value || supportsDynamicSource(field.value, topLevelFields(props.modelValue?.root)))
const available = computed(() => catalog.value.filter(item => item.provider === 'org.myDepartmentMembers'
  && field.value?.props?.allowOther !== true && Array.isArray(item.params)
  && item.params.length === optionParameters.length && optionParameters.every(expected => item.params.some(param =>
    param?.name === expected.name && param.type === expected.type && param.maxLength === expected.maxLength))
  && Array.isArray(item.types) && item.types.includes(field.value?.type)))
const fieldLabel = node => (typeof node.label === 'string' ? node.label : node.key) + ' (' + node.key + ')'
const parameterError = computed(() => {
  if (!source.value) return ''
  for (const param of optionParameters) {
    const value = draft.value[param.name]
    if (!value) continue
    if (value.mode === 'field') {
      if (!candidates.value.some(item => item.key === value.field)) return param.label + '請選擇合法來源欄位；不可引用自己、循環相依或其他答案欄位。'
    } else if (value.literal.length > param.maxLength || /[{}]/.test(value.literal)) {
      return param.label + '請填入範圍內的固定文字；欄位綁定請使用「其他欄位」。'
    }
  }
  return ''
})
watch(fields, nodes => { if (!nodes.some(node => node.id === fieldId.value)) fieldId.value = '' })
watch([fieldId, () => field.value?.optionsSource], resetDraft, { immediate: true, deep: true })
watch([draftDirty, parameterError, supportedSource], () => {
  emit('pending', draftDirty.value)
  emit('invalid', !!parameterError.value || !supportedSource.value)
}, { immediate: true })
function resetDraft() {
  draft.value = Object.fromEntries(optionParameters.map(param => {
    const value = field.value?.optionsSource?.params?.[param.name]
    const reference = parameterReference(value)
    return [param.name, { mode: reference ? 'field' : 'literal', field: reference || '', literal: typeof value === 'string' && !reference ? value : '' }]
  }))
  draftDirty.value = false
}
function setDraft(name, key, value) {
  if (props.readonly || props.blocked) return
  draft.value = { ...draft.value, [name]: { ...draft.value[name], [key]: value } }
  draftDirty.value = true
}
function updateField(optionsSource) {
  const id = fieldId.value
  function replace(nodes) {
    return nodes.map(node => {
      if (node.type === 'repeater') return node
      if (node.id === id) {
        const next = { ...node }
        if (optionsSource) next.optionsSource = optionsSource
        else delete next.optionsSource
        return next
      }
      return ['grid', 'section'].includes(node.type) && node.children ? { ...node, children: replace(node.children) } : node
    })
  }
  emit('update:modelValue', { ...props.modelValue, root: replace(props.modelValue.root) })
}
function applySource(provider) {
  if (props.readonly || props.blocked || draftDirty.value || loading.value || error.value || !field.value) return
  if (provider && !available.value.some(item => item.provider === provider)) return
  updateField(provider ? { provider } : null)
}
function applyParameters() {
  if (props.readonly || props.blocked || !draftDirty.value || parameterError.value || !field.value
    || !available.value.some(item => item.provider === source.value)) return
  const params = {}
  const dependsOn = new Set()
  for (const param of optionParameters) {
    const value = draft.value[param.name]
    if (value.mode === 'field') {
      params[param.name] = '{data.' + value.field + '}'
      dependsOn.add(value.field)
    } else if (value.literal !== '') params[param.name] = value.literal
  }
  // params 與 dependsOn 同一次 DSL 更新，沒有短暫的不同步定義。
  updateField({ provider: source.value, ...(Object.keys(params).length ? { params } : {}),
    ...(dependsOn.size ? { dependsOn: [...dependsOn] } : {}) })
}
async function load() {
  const current = ++generation
  controller?.abort()
  const requestController = new AbortController()
  controller = requestController
  loading.value = true
  error.value = ''
  try {
    const result = await fetchOptionProviders(requestController.signal)
    if (current !== generation) return
    if (!Array.isArray(result)) throw new Error('來源清冊格式不正確')
    catalog.value = result.filter(item => item && typeof item.provider === 'string' && typeof item.label === 'string')
  } catch (e) {
    if (current === generation && !requestController.signal.aborted) error.value = '來源清冊載入失敗：' + (e.response?.data?.message || e.message)
  } finally { if (current === generation) loading.value = false }
}
onMounted(load)
onBeforeUnmount(() => { generation += 1; controller?.abort(); emit('pending', false); emit('invalid', false) })
</script>

<style scoped>
.options-source-panel { border-bottom: 1px solid #e4e7ed; padding: 12px 16px; }
h3 { font-size: 15px; margin: 0 0 8px; }
p { margin: 8px 0; font-size: 13px; color: #606266; }
.el-select { width: 260px; margin-right: 8px; margin-bottom: 8px; max-width: 100%; }
.parameter-row { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin: 8px 0; }
.parameter-row label { width: 80px; }
.parameter-row .el-select { margin: 0; }
.parameter-row .el-input { width: 260px; max-width: 100%; }
.parameter-actions { margin: 8px 0; }
</style>
