<script setup>
import { ref, watch } from 'vue'
import http from '../services/http'

const props = defineProps({ processInstanceId: { type: String, required: true } })
const container = ref(null)
const error = ref(null)

// 每輪擁有自己的 viewer；切換案件或卸載時取消請求並銷毀，
// import/XML 解析即使較晚完成，也不得重建或覆蓋目前案件的流程圖。
watch([container, () => props.processInstanceId], async ([element, processId], _previous, onCleanup) => {
  error.value = null
  if (!element || !processId) return
  const controller = new AbortController()
  let viewer
  onCleanup(() => { controller.abort(); viewer?.destroy() })
  try {
    const [{ data }, { default: BpmnViewer }] = await Promise.all([
      http.get(`/api/process-instances/${encodeURIComponent(processId)}/bpmn-xml`, { signal: controller.signal }),
      import('bpmn-js/lib/NavigatedViewer'),
    ])
    if (controller.signal.aborted) return
    if (!data.xml) { error.value = '無流程圖資料'; return }

    viewer = new BpmnViewer({ container: element })
    await viewer.importXML(data.xml)
    if (controller.signal.aborted) return
    viewer.get('canvas').zoom('fit-viewport')

    const overlays = viewer.get('overlays')
    const elementRegistry = viewer.get('elementRegistry')
    data.activeIds?.forEach(id => {
      if (elementRegistry.get(id)) {
        overlays.add(id, {
          position: { top: 0, left: 0 },
          html: '<div style="width:100%;height:100%;background:rgba(255,165,0,0.3);border:2px solid orange;border-radius:3px;pointer-events:none"></div>'
        })
      }
    })
  } catch (e) {
    if (controller.signal.aborted) return
    viewer?.destroy()
    viewer = null
    error.value = e.message || '載入失敗'
  }
}, { flush: 'post' })
</script>

<template>
  <el-card>
    <template #header>流程圖</template>
    <div ref="container" style="height:300px;border:1px solid #eee;border-radius:4px" />
    <el-empty v-if="error" :description="error" :image-size="60" />
  </el-card>
</template>
