<template>
  <UfRenderer ref="renderer" :envelope="presentation" :model-value="rendererModel" :errors="errors"
    @update:model-value="updateModel" @update:errors="$emit('update:errors', $event)" />
</template>

<script setup>
import { computed, ref, shallowReactive, unref } from 'vue'
import { UfRenderer } from '@nanshan/uniform-vue'
import { provideOptionsHost, scalarChoiceFields, supportsDynamicSource, snapshotOptions, useOptionsHost } from './options.js'
const props = defineProps({ envelope: { type: Object, required: true }, modelValue: Object, errors: Array })
const emit = defineEmits(['update:modelValue', 'update:errors'])
const renderer = ref()
const host = useOptionsHost()
const statuses = shallowReactive(new Map())
// SDK 靜態選項編譯不接受空 enum；此值僅供呈現 schema 通過編譯。
// Widgets 仍從宿主 status.list 取真實選項；validate 另拒絕空清單的所有非空答案。
const emptyOptionsPlaceholder = [{ value: '__greyhound_empty_options__', label: '' }]
if (host) provideOptionsHost(host.envelope, host.target, host.onConflict, {
  formData: host.formData,
  register: (id, status) => statuses.set(id, status),
  unregister: (id, status) => { if (statuses.get(id) === status) statuses.delete(id) },
})
// null 是宿主明確清除動態單選的提交意圖。SDK scalar schema 用缺鍵表示未填寫，
// 僅在呈現模型省略；之後編輯其他欄位時仍須把 null 留在宿主提交資料。
const clearableScalarKeys = computed(() => !host || props.envelope.mode !== 'fill' ? []
  : scalarChoiceFields(props.envelope.schema?.root)
    .filter(node => node.type !== 'checkbox' && supportsDynamicSource(node)
      && props.envelope.meta?.states?.[node.key] === 'editable')
    .map(node => node.key))
const rendererModel = computed(() => {
  const data = props.modelValue
  if (!data) return data
  const cleared = clearableScalarKeys.value.filter(key => data[key] === null)
  if (!cleared.length) return data
  const next = { ...data }
  for (const key of cleared) delete next[key]
  return next
})
function updateModel(data) {
  const cleared = clearableScalarKeys.value.filter(key => props.modelValue?.[key] === null && !Object.hasOwn(data, key))
  if (!cleared.length) { emit('update:modelValue', data); return }
  const next = { ...data }
  for (const key of cleared) next[key] = null
  emit('update:modelValue', next)
}

// Canonical DSL 始終留在宿主。只有已接線的來源轉成 SDK 能驗證的選項快照，
// 不清掉不支援來源或濾除 SDK 錯誤；後端仍依 canonical 定義重新 resolve。
const presentation = computed(() => {
  const env = props.envelope
  if (!host || !env.schema) return env
  const fields = new Map(scalarChoiceFields(env.schema.root).filter(node => supportsDynamicSource(node)).map(node => [node.id, node]))
  function project(nodes) {
    return nodes.map(node => {
      if (fields.has(node.id)) {
        const next = { ...node }
        delete next.optionsSource
        const readonly = env.mode === 'view' || env.meta?.states?.[node.key] !== 'editable'
        const options = readonly ? snapshotOptions(env, node) : unref(statuses.get(node.id)?.list) || []
        next.props = { ...node.props, options: options.length ? options : emptyOptionsPlaceholder }
        return next
      }
      return ['grid', 'section'].includes(node.type) && node.children
        ? { ...node, children: project(node.children) } : node
    })
  }
  return { ...env, schema: { ...env.schema, root: project(env.schema.root) } }
})
function validate() {
  if (!renderer.value?.validate) return [{ path: '', code: 'formUnavailable', message: '表單尚未準備完成。' }]
  const pending = [...statuses.entries()].flatMap(([id, status]) => {
    if (!unref(status.source) || !unref(status.editable) || unref(status.ready)) return []
    const node = scalarChoiceFields(props.envelope.schema?.root).find(field => field.id === id)
    return [{ path: '/' + (node?.key || ''), code: 'optionsUnavailable',
      message: unref(status.error) || unref(status.reason) || '選項仍在載入，請稍後再送出。' }]
  })
  const emptySelections = [...statuses.entries()].flatMap(([id, status]) => {
    if (!unref(status.source) || !unref(status.editable) || !unref(status.ready) || unref(status.list)?.length) return []
    const node = scalarChoiceFields(props.envelope.schema?.root).find(field => field.id === id)
    if (!node) return []
    const value = props.modelValue?.[node.key]
    const empty = value === undefined || (node.type === 'checkbox' ? Array.isArray(value) && value.length === 0 : value === null)
    if (empty) return []
    return [{ path: '/' + node.key, code: 'optionNotAllowed',
      message: '目前沒有可選項目，請清除原選取後再送出。' }]
  })
  const errors = [...(renderer.value?.validate?.() || []), ...pending, ...emptySelections]
  emit('update:errors', errors)
  return errors
}
defineExpose({ validate })
</script>
