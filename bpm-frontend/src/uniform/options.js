/** 宿主限定的動態選項接點；DSL 只提供 fieldId，可信來源由伺服器解析。 */
import { computed, inject, onBeforeUnmount, provide, ref, unref, watch } from 'vue'
import { fetchStartOptions, fetchTaskOptions } from './api.js'

const optionsHost = Symbol('greyhound-options')

// layout 不增加資料層；repeater 與其子欄位不在本批支援範圍。
export function scalarChoiceFields(nodes = []) {
  return nodes.flatMap(node => node.type === 'repeater' ? [] : [
    ...((node.type === 'radio' || (node.type === 'select' && node.props?.multiple !== true)) && node.key ? [node] : []),
    ...(['grid', 'section'].includes(node.type) ? scalarChoiceFields(node.children) : []),
  ])
}

export function provideOptionsHost(envelope, target, onConflict, lifecycle = {}) {
  provide(optionsHost, { envelope, target, onConflict, ...lifecycle })
}
export function useOptionsHost() { return inject(optionsHost, null) }
export function supportsDynamicSource(node) {
  return (node.type === 'radio' || (node.type === 'select' && node.props?.multiple !== true))
    && node.optionsSource?.provider === 'org.myDepartmentMembers'
    && Object.keys(node.optionsSource).every(key => ['provider', 'params', 'dependsOn'].includes(key))
    && (node.optionsSource.params === undefined || (node.optionsSource.params != null
      && !Array.isArray(node.optionsSource.params) && typeof node.optionsSource.params === 'object'
      && Object.keys(node.optionsSource.params).length === 0))
    && (node.optionsSource.dependsOn === undefined || (Array.isArray(node.optionsSource.dependsOn)
      && node.optionsSource.dependsOn.length === 0))
}

export function useDynamicOptions(context) {
  const host = inject(optionsHost, null)
  const list = ref([])
  const loading = ref(false)
  const error = ref('')
  let controller
  let generation = 0
  const canonical = computed(() => scalarChoiceFields(unref(host?.envelope)?.schema?.root)
    .find(node => node.id === context().node.id))
  const source = computed(() => canonical.value?.optionsSource || context().node.optionsSource)
  const supported = computed(() => !!host && !!canonical.value && supportsDynamicSource(canonical.value))
  const ready = ref(false)
  const reason = computed(() => supported.value ? '' : host
    ? '此動態來源、參數或欄位型別尚未支援，已停用選取。'
    : '尚未設定動態選項服務，已停用選取。設計預覽無法查詢正式來源。')
  const snapshot = computed(() => {
    const c = context()
    const env = unref(host?.envelope)
    if (!scalarChoiceFields(env?.schema?.root).some(node => node.id === c.node.id)) return null
    const label = env?.data?.$labels?.[c.node.key]
    return Object.is(env?.data?.[c.node.key], c.value) && typeof label === 'string' ? label : null
  })
  async function load() {
    const current = ++generation
    controller?.abort()
    controller = null
    list.value = []
    loading.value = false
    error.value = ''
    const c = context()
    ready.value = false
    if (!source.value || c.mode === 'view' || c.state !== 'editable' || !supported.value) return
    const target = unref(host.target)
    if (!target) { error.value = '表單版本或任務資訊尚未準備完成，請重新載入表單。'; return }
    controller = new AbortController()
    loading.value = true
    try {
      const response = target.taskId
        ? await fetchTaskOptions(target.taskId, c.node.id, controller.signal)
        : await fetchStartOptions(target.processKey, c.node.id, target.applicationContext, controller.signal)
      if (current !== generation) return
      if (!Array.isArray(response?.options) || response.options.some(option => !option ||
        !['string', 'number', 'boolean'].includes(typeof option.value) || typeof option.label !== 'string')) {
        throw new Error('選項回應格式不正確')
      }
      list.value = response.options
      ready.value = true
    } catch (e) {
      if (current !== generation || controller?.signal.aborted) return
      error.value = e.response?.status === 409
        ? '流程或表單版本已更新，請重新載入表單後再選取。'
        : '選項載入失敗：' + (e.response?.data?.message || e.message)
      if (e.response?.status === 409) host.onConflict?.()
    } finally {
      if (current === generation) loading.value = false
    }
  }
  watch([() => source.value, () => context().node.id, () => context().mode, () => context().state,
    () => unref(host?.target), () => unref(host?.envelope)], load, { immediate: true })
  const status = { list, loading, error, reason, ready, source,
    editable: computed(() => context().mode === 'fill' && context().state === 'editable') }
  const id = context().node.id
  host?.register?.(id, status)
  onBeforeUnmount(() => { generation += 1; controller?.abort(); host?.unregister?.(id, status) })
  return { list, loading, error, reason, snapshot, source, retry: load }
}
