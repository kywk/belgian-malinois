<template>
  <div class="form-editor" :class="{ embedded }">
    <div class="toolbar">
      <el-button v-if="embedded" @click="$emit('request-close')">返回流程設計</el-button>
      <el-input v-model="formName" aria-label="表單名稱" placeholder="表單名稱" class="name-input"
        :disabled="!canEdit" @update:model-value="dirty = true" />
      <el-input v-model="formKey" aria-label="表單代碼" placeholder="表單代碼（formKey）" class="key-input"
        :disabled="!canEdit || !!formId" @update:model-value="dirty = true" />
      <el-tag v-if="formVersion" :type="published ? 'success' : 'info'">第 {{ formVersion }} 版・{{ published ? '已發布' : '草稿' }}</el-tag>
      <el-tag v-if="dirty" type="warning">未儲存</el-tag>
      <div class="toolbar-spacer" />
      <el-button v-if="published" :loading="busy" :disabled="loading" @click="handleRevision">建立／開啟新版草稿</el-button>
      <template v-else>
        <el-button :disabled="!canPersist" :loading="busy" @click="handleSave">儲存草稿</el-button>
        <el-button type="primary" :disabled="!canPersist" :loading="busy" @click="handlePublish">儲存並發布</el-button>
      </template>
      <el-button :disabled="!hasFields || loading || busy || hasUnappliedChanges" @click="handlePreview">預覽填寫</el-button>
    </div>
    <el-alert v-if="published" title="此版本已發布，內容為唯讀。改版發布後，新申請採用新版，既有案件維持原版本。"
      type="info" show-icon :closable="false" />
    <el-alert v-if="hasUnappliedChanges" title="欄位設定尚未套用或有錯誤，請先完成設定；儲存與發布暫停。"
      type="warning" show-icon :closable="false" />
    <el-alert v-if="loadError" :title="loadError" type="error" show-icon :closable="false">
      <el-button @click="loadDefinition">重新載入</el-button>
    </el-alert>
    <OptionsSourcePanel v-if="dsl" :model-value="dsl" :readonly="!canEdit" :blocked="hasUnappliedChanges"
      @update:model-value="applyOptionsSource" />
    <div class="editor-body" v-loading="loading">
      <UfDesigner v-if="dsl" v-model="dsl" :palette="palette" :readonly="!canEdit"
        @update:model-value="dirty = true" @pending="onPending" @invalid="onInvalid" />
    </div>
    <el-dialog v-model="previewVisible" title="表單填寫預覽" width="min(720px, 95vw)" destroy-on-close append-to-body>
      <UfRenderer v-if="previewEnvelope" :envelope="previewEnvelope" />
      <el-alert v-else-if="previewError" :title="previewError" type="error" show-icon :closable="false" />
      <el-skeleton v-else :rows="4" animated />
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, ref, watch, onBeforeUnmount } from 'vue'
import { useRoute, onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { UfDesigner, UfRenderer } from '@nanshan/uniform-vue'
import '@nanshan/uniform-vue/designer.css'
import { getFormSchema, createForm, updateForm, publishForm, createFormRevision, listForms } from '../services/formApi.js'
import OptionsSourcePanel from '../components/OptionsSourcePanel.vue'
import { previewForm } from '../uniform/api.js'

const props = defineProps({ embedded: Boolean, initialFormKey: String, initialVersion: [Number, String] })
const emit = defineEmits(['saved', 'published', 'request-close'])
const palette = ['text', 'textarea', 'number', 'select', 'radio', 'checkbox', 'boolean', 'date', 'dateRange', 'divider', 'freeText']
const route = useRoute()
const formName = ref('')
const formKey = ref('')
const formId = ref(null)
const formVersion = ref(null)
const status = ref('draft')
const dsl = ref(null)
const dirty = ref(false)
const loading = ref(false)
const busy = ref(false)
const loadError = ref('')
const pending = ref(new Set())
const invalid = ref(new Set())
const previewVisible = ref(false)
const previewEnvelope = ref(null)
const previewError = ref('')
let loadGeneration = 0
let previewGeneration = 0

const published = computed(() => status.value === 'published')
const canEdit = computed(() => !published.value && !loading.value && !busy.value && !!dsl.value)
const hasUnappliedChanges = computed(() => pending.value.size > 0 || invalid.value.size > 0)
const canPersist = computed(() => canEdit.value && !!formName.value.trim() && !!formKey.value.trim() && !hasUnappliedChanges.value)
const hasFields = computed(() => Array.isArray(dsl.value?.root) && dsl.value.root.length > 0)
const target = computed(() => props.embedded
  ? [props.initialFormKey, props.initialVersion]
  : [route.params.id, route.query.version])

function applyOptionsSource(candidate) {
  if (!canEdit.value || hasUnappliedChanges.value) return
  dsl.value = candidate
  dirty.value = true
}

function onPending(key, value) { value ? pending.value.add(key) : pending.value.delete(key) }
function onInvalid(key, value) { value ? invalid.value.add(key) : invalid.value.delete(key) }
function currentDocument() { return { ...dsl.value, key: formKey.value.trim() } }
function errorDetail(e) { return e.response?.data?.message || e.message }

function applyDefinition(def) {
  const doc = typeof def.schemaJson === 'string' ? JSON.parse(def.schemaJson) : def.schemaJson
  if (!doc || !def.id) throw new Error('表單定義不完整')
  dsl.value = doc
  formId.value = def.id
  formName.value = def.name ?? ''
  formKey.value = def.formKey ?? doc.key ?? ''
  formVersion.value = def.version
  status.value = def.status
  pending.value.clear()
  invalid.value.clear()
  dirty.value = false
}

async function loadDefinition() {
  const generation = ++loadGeneration
  const [key, version] = target.value
  loading.value = true
  loadError.value = ''
  dsl.value = null
  formId.value = null
  formVersion.value = null
  formName.value = ''
  formKey.value = ''
  status.value = 'draft'
  pending.value.clear()
  invalid.value.clear()
  dirty.value = false
  try {
    if (key) {
      const def = await getFormSchema(key, version)
      if (generation !== loadGeneration) return
      applyDefinition(def)
    } else {
      dsl.value = { $uniform: '1.0', key: '', requires: { core: '^1.0' }, root: [] }
    }
  } catch (e) {
    if (generation === loadGeneration) loadError.value = '載入表單失敗：' + errorDetail(e)
  } finally {
    if (generation === loadGeneration) loading.value = false
  }
}

// 儲存與發布使用同一份快照；busy 期間鎖住輸入，避免 API 完成後清掉新修改。
async function persistDraft() {
  const doc = currentDocument()
  const payload = { name: formName.value.trim(), formKey: formKey.value.trim(), schemaJson: JSON.stringify(doc) }
  const saved = formId.value ? await updateForm(formId.value, payload) : await createForm(payload)
  applyDefinition(saved)
  emit('saved', saved)
  return saved
}

async function handleSave() {
  if (!canPersist.value) return
  busy.value = true
  try {
    await persistDraft()
    ElMessage.success('草稿已儲存')
  } catch (e) {
    ElMessage.error('儲存失敗：' + errorDetail(e))
  } finally { busy.value = false }
}

async function handlePublish() {
  if (!canPersist.value) return
  busy.value = true
  try {
    const saved = await persistDraft()
    const publishedDef = await publishForm(saved.id)
    applyDefinition(publishedDef)
    emit('published', publishedDef)
    ElMessage.success('表單已發布，新申請會使用此版本')
  } catch (e) {
    ElMessage.error('發布未完成：' + errorDetail(e) + '。已儲存的草稿可再次發布。')
  } finally { busy.value = false }
}

async function handleRevision() {
  if (busy.value || loading.value || !published.value) return
  busy.value = true
  try {
    const forms = await listForms()
    const existing = forms.find(def => def.formKey === formKey.value && def.status === 'draft')
    const def = existing ? await getFormSchema(existing.formKey, existing.version) : await createFormRevision(formKey.value)
    applyDefinition(def)
    emit('saved', def)
    ElMessage.success(existing ? '已開啟現有草稿' : '已建立新版草稿')
  } catch (e) {
    ElMessage.error('開啟新版草稿失敗：' + errorDetail(e))
  } finally { busy.value = false }
}

async function handlePreview() {
  if (!dsl.value || busy.value || loading.value || hasUnappliedChanges.value) return
  const generation = ++previewGeneration
  previewVisible.value = true
  previewEnvelope.value = null
  previewError.value = ''
  try {
    const env = await previewForm(JSON.stringify(currentDocument()))
    if (generation === previewGeneration) previewEnvelope.value = env
  } catch (e) {
    if (generation === previewGeneration) previewError.value = '預覽失敗：' + errorDetail(e)
  }
}

async function confirmLeave() {
  if (busy.value) { ElMessage.info('請等待儲存或發布完成'); return false }
  if (!dirty.value && !hasUnappliedChanges.value) return true
  try {
    await ElMessageBox.confirm('尚有未儲存或未套用的表單設定，離開將捨棄這些修改。', '離開表單設計', {
      confirmButtonText: '捨棄並離開', cancelButtonText: '繼續編輯', type: 'warning',
    })
    return true
  } catch { return false }
}

onBeforeRouteLeave(confirmLeave)
onBeforeRouteUpdate((to, from) => {
  if (props.embedded || (to.params.id === from.params.id && to.query.version === from.query.version)) return true
  return confirmLeave()
})
onBeforeUnmount(() => { loadGeneration += 1; previewGeneration += 1 })
watch(target, loadDefinition, { immediate: true })
defineExpose({ confirmLeave })
</script>

<style scoped>
.form-editor { height: 100vh; display: flex; flex-direction: column; min-height: 0; }
.form-editor.embedded { height: calc(100vh - 80px); }
.toolbar { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; padding: 12px 16px; border-bottom: 1px solid #e4e7ed; background: #fff; }
.name-input { width: 200px; }
.key-input { width: 190px; }
.toolbar-spacer { flex: 1; }
.editor-body { display: flex; flex: 1; min-height: 0; overflow: hidden; }
@media (max-width: 640px) { .name-input, .key-input { width: 100%; } .toolbar-spacer { display: none; } }
</style>
