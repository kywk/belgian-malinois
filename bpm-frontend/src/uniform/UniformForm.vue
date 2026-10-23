<template>
  <UfRenderer ref="renderer" :envelope="presentation" :model-value="modelValue" :errors="errors"
    @update:model-value="$emit('update:modelValue', $event)" @update:errors="$emit('update:errors', $event)" />
</template>

<script setup>
import { computed, ref, shallowReactive, unref } from 'vue'
import { UfRenderer } from '@nanshan/uniform-vue'
import { provideOptionsHost, scalarChoiceFields, supportsDynamicSource, useOptionsHost } from './options.js'
const props = defineProps({ envelope: { type: Object, required: true }, modelValue: Object, errors: Array })
const emit = defineEmits(['update:modelValue', 'update:errors'])
const renderer = ref()
const host = useOptionsHost()
const statuses = shallowReactive(new Map())
if (host) provideOptionsHost(host.envelope, host.target, host.onConflict, {
  register: (id, status) => statuses.set(id, status),
  unregister: (id, status) => { if (statuses.get(id) === status) statuses.delete(id) },
})
// Canonical DSL 始終留在宿主。只有已接線的純量來源轉成 SDK 能驗證的選項快照，
// 不清掉不支援來源或濾除 SDK 錯誤；後端仍依 canonical 定義重新 resolve。
const presentation = computed(() => {
  const env = props.envelope
  if (!host || !env.schema) return env
  const fields = new Map(scalarChoiceFields(env.schema.root).filter(supportsDynamicSource).map(node => [node.id, node]))
  function project(nodes) {
    return nodes.map(node => {
      if (fields.has(node.id)) {
        const next = { ...node }
        delete next.optionsSource
        const readonly = env.mode === 'view' || env.meta?.states?.[node.key] !== 'editable'
        const value = env.data?.[node.key]
        const label = env.data?.$labels?.[node.key]
        const options = readonly ? value == null ? [] : [{ value, label: typeof label === 'string' ? label : String(value) }]
          : unref(statuses.get(node.id)?.list) || []
        next.props = { ...node.props, options }
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
  const errors = [...(renderer.value?.validate?.() || []), ...pending]
  emit('update:errors', errors)
  return errors
}
defineExpose({ validate })
</script>
