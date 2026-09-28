<template>
  <el-card>
    <template #header>流程圖</template>
    <div ref="container" style="height:300px;border:1px solid #eee;border-radius:4px" />
    <el-empty v-if="error" :description="error" :image-size="60" />
  </el-card>
</template>

<script setup>
import { ref, onMounted, onUnmounted, watch } from 'vue'
import axios from 'axios'

const props = defineProps({ processInstanceId: { type: String, required: true } })
const container = ref(null)
const error = ref(null)
let viewer = null

async function load() {
  if (!container.value) return
  error.value = null
  try {
    const { data } = await axios.get(`/api/process-instances/${props.processInstanceId}/bpmn-xml`)
    if (!data.xml) { error.value = '無流程圖資料'; return }

    const { default: BpmnViewer } = await import('bpmn-js/lib/NavigatedViewer')
    viewer?.destroy()
    viewer = new BpmnViewer({ container: container.value })
    await viewer.importXML(data.xml)
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
    console.error('ProcessDiagram error:', e)
    error.value = e.message || '載入失敗'
  }
}

onMounted(load)
watch(() => props.processInstanceId, () => { viewer?.destroy(); viewer = null; load() })
onUnmounted(() => viewer?.destroy())
</script>
