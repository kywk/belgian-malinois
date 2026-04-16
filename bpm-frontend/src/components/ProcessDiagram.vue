<template>
  <el-card>
    <template #header>流程圖</template>
    <img v-if="diagramUrl" :src="diagramUrl" alt="流程圖" style="max-width: 100%" />
    <el-empty v-else description="載入中..." :image-size="60" />
  </el-card>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { getProcessDiagram } from '../services/flowableApi.js'

const props = defineProps({ processInstanceId: { type: String, required: true } })
const diagramUrl = ref(null)

onMounted(async () => {
  try {
    const blob = await getProcessDiagram(props.processInstanceId)
    diagramUrl.value = URL.createObjectURL(blob)
  } catch {
    diagramUrl.value = null
  }
})

onUnmounted(() => {
  if (diagramUrl.value) URL.revokeObjectURL(diagramUrl.value)
})
</script>
