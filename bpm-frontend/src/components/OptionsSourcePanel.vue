<template>
  <section class="options-source-panel" aria-label="動態選項來源設定">
    <h3>選項來源</h3>
    <p>單選下拉與單選鈕可使用本人部門成員；複選及重複列暫不支援。</p>
    <el-select v-model="fieldId" aria-label="設定選項來源的欄位" placeholder="選擇欄位" :disabled="blocked">
      <el-option v-for="field in fields" :key="field.id" :value="field.id" :label="fieldLabel(field)" />
    </el-select>
    <template v-if="field">
      <el-select :model-value="source" aria-label="選項來源" placeholder="靜態選項" :disabled="readonly || blocked || loading || !!error"
        @update:model-value="applySource">
        <el-option value="" label="靜態選項" />
        <el-option v-for="provider in available" :key="provider.provider" :value="provider.provider" :label="provider.label" />
        <el-option v-if="source && !available.some(item => item.provider === source)" :value="source"
          :label="source + '（來源尚未支援）'" disabled />
      </el-select>
      <p v-if="field.optionsSource">動態模式不使用靜態清單；身分與部門由伺服器判定。</p>
    </template>
    <p v-if="blocked" role="status">請先套用或修正設計器內的欄位設定，再變更來源。</p>
    <p v-if="loading" role="status">來源清冊載入中…</p>
    <div v-if="error" role="alert">{{ error }} <el-button @click="load">重試</el-button></div>
  </section>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { fetchOptionProviders } from '../uniform/api.js'
import { scalarChoiceFields } from '../uniform/options.js'
const props = defineProps({ modelValue: Object, readonly: Boolean, blocked: Boolean })
const emit = defineEmits(['update:modelValue'])
const fieldId = ref('')
const catalog = ref([])
const loading = ref(false)
const error = ref('')
let generation = 0
let controller
const fields = computed(() => scalarChoiceFields(props.modelValue?.root))
const field = computed(() => fields.value.find(node => node.id === fieldId.value))
const source = computed(() => field.value?.optionsSource?.provider || '')
const available = computed(() => catalog.value.filter(item => item.provider === 'org.myDepartmentMembers'
  && Array.isArray(item.params) && !item.params.length && Array.isArray(item.types) && item.types.includes(field.value?.type)))
const fieldLabel = node => (typeof node.label === 'string' ? node.label : node.key) + ' (' + node.id + ')'
watch(fields, nodes => { if (!nodes.some(node => node.id === fieldId.value)) fieldId.value = '' })
function applySource(provider) {
  if (props.readonly || props.blocked || loading.value || error.value || !field.value) return
  if (provider && !available.value.some(item => item.provider === provider)) return
  const id = fieldId.value
  function replace(nodes) {
    return nodes.map(node => {
      if (node.type === 'repeater') return node
      if (node.id === id) {
        const next = { ...node }
        if (provider) next.optionsSource = { provider }
        else delete next.optionsSource
        return next
      }
      return ['grid', 'section'].includes(node.type) && node.children ? { ...node, children: replace(node.children) } : node
    })
  }
  emit('update:modelValue', { ...props.modelValue, root: replace(props.modelValue.root) })
}
async function load() {
  const current = ++generation
  controller?.abort()
  controller = new AbortController()
  loading.value = true
  error.value = ''
  try {
    const result = await fetchOptionProviders(controller.signal)
    if (current !== generation) return
    if (!Array.isArray(result)) throw new Error('來源清冊格式不正確')
    catalog.value = result.filter(item => item && typeof item.provider === 'string' && typeof item.label === 'string')
  } catch (e) {
    if (current === generation && !controller.signal.aborted) error.value = '來源清冊載入失敗：' + (e.response?.data?.message || e.message)
  } finally { if (current === generation) loading.value = false }
}
onMounted(load)
onBeforeUnmount(() => { generation += 1; controller?.abort() })
</script>

<style scoped>
.options-source-panel { border-bottom: 1px solid #e4e7ed; padding: 12px 16px; }
h3 { font-size: 15px; margin: 0 0 8px; }
p { margin: 8px 0; font-size: 13px; color: #606266; }
.el-select { width: 260px; margin-right: 8px; margin-bottom: 8px; max-width: 100%; }
</style>
