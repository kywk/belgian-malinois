<template>
  <div class="bpmn-editor">
    <div class="bpmn-toolbar">
      <el-button :disabled="loading || deploying" @click="handleNew">新建</el-button>
      <el-button :disabled="loading || deploying" @click="handleImport">開啟</el-button>
      <el-button :disabled="loading || deploying" @click="handleSave">儲存流程草稿</el-button>
      <el-button :disabled="loading" @click="handleExport">匯出 XML</el-button>
      <el-button type="primary" :disabled="loading || deploying" :loading="deploying" @click="handleDeploy">部署流程</el-button>
      <el-tag :type="deploymentCurrent ? 'success' : 'info'">{{ deploymentCurrent ? '目前流程已部署' : '目前流程尚未部署' }}</el-tag>
      <input ref="fileInput" type="file" accept=".bpmn,.xml" style="display:none" @change="onFileSelected" />
    </div>

    <section class="application-form" aria-label="主申請表設定">
      <div class="application-controls">
        <strong>主申請表</strong>
        <el-select :model-value="startEventId" aria-label="申請起始事件" placeholder="選擇申請起始事件"
          :disabled="loading || deploying" @change="chooseStart">
          <el-option v-for="start in startEvents" :key="start.id" :value="start.id" :label="start.name || start.id" />
        </el-select>
        <el-select :model-value="boundFormKey" aria-label="主申請表" filterable placeholder="選取表單"
          :loading="formsLoading" :disabled="!startEventId || loading || deploying" @change="bindExistingForm">
          <el-option v-for="form in formChoices" :key="form.formKey" :value="form.formKey" :label="form.name + '（' + form.formKey + '）'" />
        </el-select>
        <el-button :disabled="!startEventId || loading || deploying" @click="openForm(true)">建立主申請表</el-button>
        <el-button :disabled="!boundFormKey || loading || deploying || formsLoading" @click="openForm(false)">設計／預覽主申請表</el-button>
        <el-button :disabled="formsLoading" @click="loadForms">重新整理表單狀態</el-button>
      </div>
      <p v-if="!startEventId">請選取流程層的起始事件，再建立或選取主申請表。</p>
      <p v-else-if="!boundFormKey">尚未綁定主申請表；申請入口需要已發布的表單。</p>
      <p v-else>
        綁定：{{ boundFormKey }}。
        <template v-if="publishedForm">已發布第 {{ publishedForm.version }} 版，新申請使用最新已發布版。</template>
        <template v-else>尚無已發布版本，申請入口尚未就緒。</template>
        <template v-if="draftForm">另有第 {{ draftForm.version }} 版草稿。</template>
      </p>
      <el-alert v-if="formsError" :title="formsError" type="error" show-icon :closable="false" />
      <el-alert v-if="deployError" :title="deployError" type="error" show-icon :closable="false" />
    </section>

    <div class="bpmn-body" v-loading="loading">
      <div ref="canvasRef" class="bpmn-canvas" />
      <div ref="panelRef" class="bpmn-panel" />
    </div>
    <el-dialog v-model="formEditorVisible" title="主申請表設計" fullscreen destroy-on-close
      :close-on-click-modal="false" :before-close="closeFormEditor">
      <FormEditor v-if="formEditorVisible" ref="formEditorRef" embedded :initial-form-key="editingFormKey"
        :initial-version="editingVersion" @saved="onFormSaved" @published="onFormSaved" @request-close="closeFormEditor()" />
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, ref, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRoute, onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useBpmnModeler } from '../composables/useBpmnModeler.js'
import FormEditor from './FormEditor.vue'
import flowableModule from '../bpmn/index.js'
import { getProcessDefinitionXml } from '../services/flowableApi.js'
import { listForms } from '../services/formApi.js'
import http from '../services/http'

import 'bpmn-js/dist/assets/diagram-js.css'
import 'bpmn-js/dist/assets/bpmn-js.css'
import 'bpmn-js/dist/assets/bpmn-font/css/bpmn-embedded.css'
import '@bpmn-io/properties-panel/dist/assets/properties-panel.css'

const route = useRoute()
const canvasRef = ref(null)
const panelRef = ref(null)
const fileInput = ref(null)
const loading = ref(true)
const deploying = ref(false)
const startEvents = ref([])
const startEventId = ref('')
const boundFormKey = ref('')
const forms = ref([])
const formsLoading = ref(false)
const formsError = ref('')
const deployError = ref('')
const formEditorVisible = ref(false)
const formEditorRef = ref()
const editingFormKey = ref()
const editingVersion = ref()
let editingStartId = ''
let active = true
let loadGeneration = 0
let formsGeneration = 0
let importQueue = Promise.resolve()
const diagramRevision = ref(0)
const deployedRevision = ref(-1)
const savedRevision = ref(0)
const modeler = useBpmnModeler()
const { createModeler, importXml, exportXml, newDiagram, destroy } = modeler

const draftKey = computed(() => 'bpmn-draft:' + (route.query.id || route.params.processKey || 'new'))
const deploymentCurrent = computed(() => deployedRevision.value === diagramRevision.value)
const formChoices = computed(() => {
  const choices = new Map()
  for (const form of forms.value) {
    const previous = choices.get(form.formKey)
    if (!previous || form.version > previous.version) choices.set(form.formKey, form)
  }
  return [...choices.values()]
})
const boundVersions = computed(() => forms.value.filter(form => form.formKey === boundFormKey.value))
const publishedForm = computed(() => boundVersions.value.filter(form => form.status === 'published').sort((a, b) => b.version - a.version)[0])
const draftForm = computed(() => boundVersions.value.find(form => form.status === 'draft'))
const detail = e => e.response?.data?.message || e.message

function syncStarts() {
  const starts = modeler.getApplicationStartEvents()
  startEvents.value = starts.map(start => ({ id: start.id, name: start.businessObject.name }))
  if (!starts.some(start => start.id === startEventId.value)) startEventId.value = starts.length === 1 ? starts[0].id : ''
  const start = starts.find(start => start.id === startEventId.value)
  boundFormKey.value = start?.businessObject.get('flowable:formKey') || ''
}

function chooseStart(id) {
  startEventId.value = id
  modeler.selectElement(id)
  syncStarts()
}

function bindExistingForm(key) {
  try {
    modeler.bindStartForm(startEventId.value, key)
    syncStarts()
  } catch (e) { ElMessage.error(e.message) }
}

async function loadForms() {
  const generation = ++formsGeneration
  formsLoading.value = true
  formsError.value = ''
  try {
    const result = await listForms()
    if (active && generation === formsGeneration) forms.value = result
  } catch (e) {
    if (active && generation === formsGeneration) formsError.value = '表單狀態載入失敗：' + detail(e)
  } finally {
    if (active && generation === formsGeneration) formsLoading.value = false
  }
}

async function loadDiagram() {
  const generation = ++loadGeneration
  const definitionId = route.query.id
  const key = draftKey.value
  loading.value = true
  deployError.value = ''
  try {
    const draft = localStorage.getItem(key)
    const xml = draft || (definitionId ? await getProcessDefinitionXml(definitionId) : null)
    if (!active || generation !== loadGeneration) return
    // bpmn-js import 非同步且不可取消：串行匯入，防止切換路由時舊圖覆蓋新圖。
    importQueue = importQueue.catch(() => {}).then(async () => {
      if (!active || generation !== loadGeneration) return
      if (xml) await importXml(xml)
      else await newDiagram()
      if (!active || generation !== loadGeneration) return
      syncStarts()
      diagramRevision.value += 1
      savedRevision.value = diagramRevision.value
      deployedRevision.value = definitionId && !draft ? diagramRevision.value : -1
      if (draft) ElMessage.info('已還原此流程的本機草稿，部署後才會開放新流程內容')
    })
    await importQueue
  } catch (e) {
    if (active && generation === loadGeneration) ElMessage.error('載入流程失敗：' + detail(e))
  } finally {
    if (active && generation === loadGeneration) loading.value = false
  }
}

async function confirmReplace() {
  if (diagramRevision.value === savedRevision.value) return true
  try {
    await ElMessageBox.confirm('流程有尚未儲存的修改，是否捨棄？', '變更流程', {
      confirmButtonText: '捨棄修改', cancelButtonText: '繼續編輯', type: 'warning',
    })
    return true
  } catch { return false }
}

async function handleNew() {
  if (loading.value || deploying.value || !await confirmReplace()) return
  await newDiagram()
  diagramRevision.value += 1
  deployedRevision.value = -1
  deployError.value = ''
  syncStarts()
  ElMessage.info('已建立新流程')
}

function handleImport() { fileInput.value?.click() }

async function onFileSelected(e) {
  const file = e.target.files?.[0]
  e.target.value = ''
  if (!file || loading.value || deploying.value || !await confirmReplace()) return
  loading.value = true
  try {
    await importXml(await file.text())
    diagramRevision.value += 1
    deployedRevision.value = -1
    deployError.value = ''
    syncStarts()
    ElMessage.success('已匯入流程')
  } catch (error) { ElMessage.error('匯入失敗：' + detail(error)) }
  finally { loading.value = false }
}

async function handleSave() {
  try {
    const revision = diagramRevision.value
    localStorage.setItem(draftKey.value, await exportXml())
    savedRevision.value = revision
    ElMessage.success('流程草稿已儲存在此瀏覽器')
  } catch (e) { ElMessage.error('草稿儲存失敗：' + detail(e)) }
}

async function handleExport() {
  try {
    const blob = new Blob([await exportXml()], { type: 'application/xml' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = 'process.bpmn20.xml'
    a.click()
    URL.revokeObjectURL(url)
  } catch (e) { ElMessage.error('匯出失敗：' + detail(e)) }
}

async function openForm(create) {
  if (!startEventId.value || loading.value || deploying.value) return
  try {
    // 開啟設計前保存 BPMN；同一 modeler 保持掛載，返回時不重載或重建圖。
    const revision = diagramRevision.value
    localStorage.setItem(draftKey.value, await exportXml())
    savedRevision.value = revision
    editingStartId = startEventId.value
    editingFormKey.value = create ? undefined : boundFormKey.value
    editingVersion.value = create ? undefined : (draftForm.value || publishedForm.value)?.version
    formEditorVisible.value = true
  } catch (e) { ElMessage.error('無法保存流程草稿：' + detail(e)) }
}

async function onFormSaved(form) {
  // 使用進入設計器時記下的 StartEvent，絕不將新表誤綁到之後的選取節點。
  const start = modeler.getApplicationStartEvents().find(item => item.id === editingStartId)
  if (!start) { ElMessage.error('原申請起始事件已不存在，請返回流程重新選取'); return }
  modeler.bindStartForm(start.id, form.formKey)
  startEventId.value = start.id
  syncStarts()
  forms.value = [...forms.value.filter(item => item.id !== form.id), form]
  await handleSave()
}

async function closeFormEditor(done) {
  if (formEditorRef.value && !await formEditorRef.value.confirmLeave()) return
  formEditorVisible.value = false
  if (typeof done === 'function') done()
  await loadForms()
}

async function handleDeploy() {
  if (loading.value || deploying.value) return
  deploying.value = true
  deployError.value = ''
  try {
    const revision = diagramRevision.value
    const xml = await exportXml()
    const form = new FormData()
    form.append('file', new Blob([xml], { type: 'application/xml' }), 'process.bpmn20.xml')
    await http.post('/api/deployments', form)
    deployedRevision.value = revision
    // 表單發布與流程部署是分開的操作；僅成功回應才標示目前快照已部署。
    ElMessage.success('流程部署成功')
  } catch (e) {
    deployError.value = (publishedForm.value ? '表單已發布；' : '') + '這次流程部署未完成：' + detail(e)
      + (deploymentCurrent.value ? '。目前仍使用既有已部署版本。' : '。目前編輯內容尚未開放申請，請修正後重試。')
  } finally { deploying.value = false }
}

onMounted(() => {
  createModeler(canvasRef.value, panelRef.value, [flowableModule])
  modeler.on('commandStack.changed', () => { diagramRevision.value += 1; syncStarts() })
  modeler.on('selection.changed', event => {
    const id = event.newSelection?.[0]?.id
    if (modeler.getApplicationStartEvents().some(start => start.id === id)) startEventId.value = id
    syncStarts()
  })
  loadDiagram()
  loadForms()
})
watch(() => [route.query.id, route.params.processKey], loadDiagram)
onBeforeRouteLeave(() => {
  if (deploying.value) { ElMessage.info('請等待部署完成'); return false }
  return confirmReplace()
})
onBeforeRouteUpdate(async (to, from) => {
  if (to.query.id === from.query.id && to.params.processKey === from.params.processKey) return true
  if (deploying.value) { ElMessage.info('請等待部署完成'); return false }
  if (!await confirmReplace()) return false
  if (formEditorVisible.value) {
    if (!await formEditorRef.value?.confirmLeave()) return false
    formEditorVisible.value = false
  }
  return true
})
onBeforeUnmount(() => { active = false; loadGeneration += 1; formsGeneration += 1; destroy() })
</script>

<style scoped>
.bpmn-editor { display: flex; flex-direction: column; height: 100vh; min-height: 0; }
.bpmn-toolbar, .application-controls { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.bpmn-toolbar { padding: 12px 16px; border-bottom: 1px solid #e4e7ed; background: #fff; }
.application-form { padding: 12px 16px; border-bottom: 1px solid #e4e7ed; background: #fafafa; }
.application-controls .el-select { width: 230px; }
.application-form p { margin: 8px 0 0; line-height: 1.6; overflow-wrap: anywhere; }
.bpmn-body { display: flex; flex: 1; min-height: 0; overflow: hidden; }
.bpmn-canvas { flex: 1; min-width: 0; }
.bpmn-panel { width: 320px; overflow-y: auto; border-left: 1px solid #e4e7ed; background: #fafafa; }
@media (max-width: 720px) { .application-controls .el-select { width: 100%; } .bpmn-panel { width: 250px; } }
</style>
