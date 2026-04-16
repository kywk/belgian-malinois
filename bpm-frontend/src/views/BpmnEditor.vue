<template>
  <div class="bpmn-editor">
    <div class="bpmn-toolbar">
      <el-button @click="handleNew">新建</el-button>
      <el-button @click="handleImport">開啟</el-button>
      <el-button @click="handleSave">儲存草稿</el-button>
      <el-button @click="handleExport">匯出 XML</el-button>
      <el-button type="primary" @click="handleDeploy">部署</el-button>
      <input ref="fileInput" type="file" accept=".bpmn,.xml" style="display:none" @change="onFileSelected" />
    </div>
    <div class="bpmn-body">
      <div ref="canvasRef" class="bpmn-canvas" />
      <div ref="panelRef" class="bpmn-panel" />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { ElMessage } from 'element-plus'
import { useBpmnModeler } from '../composables/useBpmnModeler.js'
import flowableModule from '../bpmn/index.js'
import axios from 'axios'

import 'bpmn-js/dist/assets/diagram-js.css'
import 'bpmn-js/dist/assets/bpmn-js.css'
import 'bpmn-js/dist/assets/bpmn-font/css/bpmn-embedded.css'
import '@bpmn-io/properties-panel/dist/assets/properties-panel.css'

const canvasRef = ref(null)
const panelRef = ref(null)
const fileInput = ref(null)

const { createModeler, importXml, exportXml, newDiagram, destroy } = useBpmnModeler()

onMounted(() => {
  createModeler(canvasRef.value, panelRef.value, [flowableModule])
  newDiagram()
})

onUnmounted(destroy)

function handleNew() { newDiagram(); ElMessage.info('已建立新流程') }

function handleImport() { fileInput.value?.click() }

async function onFileSelected(e) {
  const file = e.target.files?.[0]
  if (!file) return
  const xml = await file.text()
  await importXml(xml)
  ElMessage.success('已匯入')
  e.target.value = ''
}

async function handleSave() {
  const xml = await exportXml()
  localStorage.setItem('bpmn-draft', xml)
  ElMessage.success('草稿已儲存')
}

async function handleExport() {
  const xml = await exportXml()
  const blob = new Blob([xml], { type: 'application/xml' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url; a.download = 'process.bpmn20.xml'; a.click()
  URL.revokeObjectURL(url)
}

async function handleDeploy() {
  try {
    const xml = await exportXml()
    const form = new FormData()
    form.append('file', new Blob([xml], { type: 'application/xml' }), 'process.bpmn20.xml')
    await axios.post('/api/deployments', form)
    ElMessage.success('部署成功')
  } catch (e) {
    ElMessage.error('部署失敗：' + (e.response?.data?.message || e.message))
  }
}
</script>

<style scoped>
.bpmn-editor { display: flex; flex-direction: column; height: 100vh; }
.bpmn-toolbar { padding: 8px 16px; border-bottom: 1px solid #e4e7ed; background: #fff; }
.bpmn-body { display: flex; flex: 1; overflow: hidden; }
.bpmn-canvas { flex: 1; }
.bpmn-panel { width: 320px; overflow-y: auto; border-left: 1px solid #e4e7ed; background: #fafafa; }
</style>
