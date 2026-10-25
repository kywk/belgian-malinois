/** 宿主限定的動態選項接點；可信來源與參數一律由伺服器解析。 */
import { computed, inject, onBeforeUnmount, provide, ref, unref, watch } from 'vue'
import { fetchStartOptions, fetchTaskOptions } from './api.js'

const optionsHost = Symbol('greyhound-options')
export const optionParameters = [
  { name: 'query', label: '搜尋文字', type: 'string', maxLength: 100 },
  { name: 'excludeUser', label: '排除人員', type: 'string', maxLength: 255 },
]

// layout 不增加資料層；repeater 與其子欄位不在本批支援範圍。
export function topLevelFields(nodes = []) {
  return nodes.flatMap(node => node.type === 'repeater' ? [] : [
    ...(node.key ? [node] : []),
    ...(['grid', 'section'].includes(node.type) ? topLevelFields(node.children) : []),
  ])
}
export function scalarChoiceFields(nodes = []) {
  return topLevelFields(nodes).filter(node => ['radio', 'checkbox'].includes(node.type)
    || (node.type === 'select' && node.props?.multiple !== true))
}
export function supportsParameterField(node) {
  return !!node?.key && ['text', 'textarea', 'select', 'radio'].includes(node.type)
    && node.props?.allowOther !== true && !(node.type === 'select' && node.props?.multiple === true)
}
export function parameterReference(value) {
  if (typeof value !== 'string') return null
  const match = /^(?:\{\{data\.([^{}]+)\}\}|\{data\.([^{}]+)\})$/.exec(value)
  return match ? match[1] || match[2] : null
}
export function createsDependencyCycle(fields, ownerKey, sourceKey) {
  const byKey = new Map(fields.map(node => [node.key, node]))
  const visited = new Set()
  function reaches(key) {
    if (key === ownerKey) return true
    if (visited.has(key)) return false
    visited.add(key)
    const deps = byKey.get(key)?.optionsSource?.dependsOn
    return Array.isArray(deps) && deps.some(dependency => reaches(dependency))
  }
  return reaches(sourceKey)
}
export function parameterCandidates(nodes, owner) {
  const fields = topLevelFields(nodes)
  return fields.filter(node => supportsParameterField(node) && node.key !== owner?.key
    && !createsDependencyCycle(fields, owner?.key, node.key))
}
export function provideOptionsHost(envelope, target, onConflict, lifecycle = {}) {
  // 每個 Envelope 建立一次索引，避免每個選項元件各自走訪整份 schema。
  const fieldsById = computed(() => new Map(scalarChoiceFields(unref(envelope)?.schema?.root)
    .map(node => [node.id, node])))
  provide(optionsHost, { envelope, target, onConflict, fieldsById, ...lifecycle })
}
export function useOptionsHost() { return inject(optionsHost, null) }
export function supportsDynamicSource(node, fields) {
  const source = node.optionsSource
  if (!(['radio', 'checkbox'].includes(node.type) || (node.type === 'select' && node.props?.multiple !== true))
    || node.props?.allowOther === true || source?.provider !== 'org.myDepartmentMembers'
    || Object.keys(source).some(key => !['provider', 'params', 'dependsOn'].includes(key))) return false
  const params = source.params ?? {}
  if (source.params === null || typeof params !== 'object' || Array.isArray(params)) return false
  const referenced = new Set()
  for (const [key, value] of Object.entries(params)) {
    const spec = optionParameters.find(item => item.name === key)
    if (!spec || typeof value !== 'string') return false
    const dependency = parameterReference(value)
    if (dependency) {
      if (dependency === node.key) return false
      if (fields && (!fields.some(item => item.key === dependency && supportsParameterField(item))
        || createsDependencyCycle(fields, node.key, dependency))) return false
      referenced.add(dependency)
    } else if (value.length > spec.maxLength || /[{}]/.test(value)) return false
  }
  const dependencies = source.dependsOn ?? []
  return source.dependsOn !== null && Array.isArray(dependencies) && dependencies.length === referenced.size
    && dependencies.every(key => typeof key === 'string' && referenced.has(key))
    && new Set(dependencies).size === dependencies.length
}

/** 只讀取伺服器投影的快照；複選標籤不進 SDK 的 scalar $labels。 */
export function snapshotOptions(env, node) {
  const value = env?.data?.[node.key]
  if (node.type === 'checkbox') {
    if (!Array.isArray(value)) return []
    const entries = env?.ext?.greyhoundOptionLabels?.[node.key]
    return value.map(item => ({ value: item, label: Array.isArray(entries)
      ? entries.find(entry => entry && Object.is(entry.value, item) && typeof entry.label === 'string')?.label ?? String(item)
      : String(item) }))
  }
  if (value == null) return []
  const label = env?.data?.$labels?.[node.key]
  return [{ value, label: typeof label === 'string' ? label : String(value) }]
}

export function useDynamicOptions(context) {
  const host = inject(optionsHost, null)
  const list = ref([])
  const loading = ref(false)
  const error = ref('')
  const ready = ref(false)
  let controller
  let generation = 0
  const canonical = computed(() => host?.fieldsById.value.get(context().node.id))
  const source = computed(() => canonical.value?.optionsSource || context().node.optionsSource)
  // 投影可能已移除隱藏 optional inputs；實際參數解析仍由伺服器負責。
  const supported = computed(() => !!host && !!canonical.value && supportsDynamicSource(canonical.value))
  const reason = computed(() => supported.value ? '' : host
    ? '此動態來源、參數或欄位型別尚未支援，已停用選取。'
    : '尚未設定動態選項服務，已停用選取。設計預覽無法查詢正式來源。')
  const snapshot = computed(() => {
    const c = context()
    const env = unref(host?.envelope)
    if (!canonical.value || JSON.stringify(env?.data?.[c.node.key]) !== JSON.stringify(c.value)) return null
    const options = snapshotOptions(env, c.node)
    return options.length ? options.map(option => option.label).join('、') : null
  })
  // 不把整份 data 當 watch source，避免選取本欄位時重新查詢自己。
  const dependencyValues = computed(() => {
    const dependencies = source.value?.dependsOn
    if (!Array.isArray(dependencies)) return '[]'
    const data = unref(host?.formData) || unref(host?.envelope)?.data || {}
    return JSON.stringify(dependencies.map(key => [key, Object.hasOwn(data, key), data[key]]))
  })
  async function load() {
    const current = ++generation
    controller?.abort()
    controller = null
    list.value = []
    loading.value = false
    error.value = ''
    ready.value = false
    const c = context()
    if (!source.value || c.mode === 'view' || c.state !== 'editable' || !supported.value) return
    const target = unref(host.target)
    if (!target) { error.value = '表單版本或任務資訊尚未準備完成，請重新載入表單。'; return }
    const requestController = new AbortController()
    controller = requestController
    loading.value = true
    // 送原始答案，絕不在前端解參數，也不傳 Envelope.ext／provider／ctx。
    const raw = unref(host.formData) || unref(host.envelope)?.data || {}
    const data = Object.fromEntries(Object.entries(raw).filter(([key]) => key !== '$labels'))
    try {
      const response = target.taskId
        ? await fetchTaskOptions(target.taskId, c.node.id, data, requestController.signal)
        : await fetchStartOptions(target.processKey, c.node.id, target.applicationContext, data, requestController.signal)
      if (current !== generation || requestController.signal.aborted) return
      if (!Array.isArray(response?.options) || response.options.some(option => !option ||
        typeof option.value !== 'string' || typeof option.label !== 'string')
        || new Set(response.options.map(option => option.value)).size !== response.options.length) {
        throw new Error('選項回應格式不正確')
      }
      list.value = response.options
      ready.value = true
    } catch (e) {
      if (current !== generation || requestController.signal.aborted) return
      error.value = e.response?.status === 409
        ? '流程或表單版本已更新，請重新載入表單後再選取。'
        : '選項載入失敗：' + (e.response?.data?.message || e.message)
      if (e.response?.status === 409) host.onConflict?.()
    } finally {
      if (current === generation) loading.value = false
    }
  }
  watch([() => source.value, () => context().node.id, () => context().mode, () => context().state,
    () => unref(host?.target), () => unref(host?.envelope), dependencyValues], load, { immediate: true, flush: 'sync' })
  const status = { list, loading, error, reason, ready, source,
    editable: computed(() => context().mode === 'fill' && context().state === 'editable') }
  const id = context().node.id
  host?.register?.(id, status)
  onBeforeUnmount(() => { generation += 1; controller?.abort(); host?.unregister?.(id, status) })
  return { list, loading, error, reason, snapshot, source, retry: load }
}
