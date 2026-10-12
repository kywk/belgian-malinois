<template>
  <div class="form-editor">
    <!-- Toolbar -->
    <div class="toolbar">
      <el-input v-model="formName" placeholder="表單名稱" style="width:200px" />
      <el-input v-model="formKey" placeholder="formKey" style="width:160px;margin-left:8px" />
      <el-tag v-if="dirty" type="warning" style="margin-left:8px">未儲存</el-tag>
      <div style="flex:1" />
      <el-button @click="handleSave" :disabled="!formName || !formKey || !dsl">儲存草稿</el-button>
      <el-button @click="handlePreview" :disabled="!hasFields">預覽</el-button>
      <el-button type="primary" @click="handlePublish" :disabled="!formId">發佈</el-button>
    </div>

    <!-- 設計器：v-model 是完整 DSL document；宿主負責存檔／發布 -->
    <div class="editor-body" v-loading="loading">
      <UfDesigner v-if="dsl" v-model="dsl" :palette="palette" @update:model-value="onDesignerUpdate" />
    </div>

    <!-- 預覽：後端以 DSL 組一份 fill Envelope，前端用同一個 UfRenderer 呈現 -->
    <el-dialog v-model="previewVisible" title="表單預覽" width="720px" destroy-on-close>
      <UfRenderer v-if="previewEnvelope" :envelope="previewEnvelope" />
      <el-alert v-else-if="previewError" :title="previewError" type="error" show-icon :closable="false" />
      <el-skeleton v-else :rows="4" animated />
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { UfDesigner, UfRenderer } from '@nanshan/uniform-vue'
// 設計器主題（僅影響 .uf-designer 內部）：不載入的話設計器會是無樣式的裸版面。
// SDK 說明此檔為選配、全部規則限於 .uf-designer，不與 Element Plus 頁面互染。
import '@nanshan/uniform-vue/designer.css'
import { getFormSchema, createForm, updateForm, publishForm } from '../services/formApi.js'
import { previewForm } from '../uniform/api.js'

// 計畫 §三決策 5：palette 收斂，file／richtext／orgSelector／amount／optionsSource 延後。
const palette = ['text', 'textarea', 'number', 'select', 'radio', 'checkbox', 'boolean', 'date', 'dateRange', 'divider', 'freeText']

const route = useRoute()
const formName = ref('')
const formKey = ref('')
const formId = ref(null)
const dsl = ref(null)
const dirty = ref(false)
const loading = ref(false)
const previewVisible = ref(false)
const previewEnvelope = ref(null)
const previewError = ref('')

const hasFields = computed(() => Array.isArray(dsl.value?.root) && dsl.value.root.length > 0)

/** 新表單的空 DSL；key 由 formKey 輸入框決定，存檔時補進 document。 */
function emptyDocument() {
  return { $uniform: '1.0', key: '', requires: { core: '^1.0' }, root: [] }
}

function onDesignerUpdate() {
  dirty.value = true
}

/** DSL document 的 key 必須與 formKey 一致（Envelope 的 schemaRef.key 來源）。 */
function currentDocument() {
  const doc = { ...dsl.value, key: formKey.value || dsl.value.key }
  return doc
}

async function handleSave() {
  const doc = currentDocument()
  const payload = { name: formName.value, formKey: formKey.value, schemaJson: JSON.stringify(doc) }
  try {
    if (formId.value) {
      await updateForm(formId.value, payload)
    } else {
      const result = await createForm(payload)
      formId.value = result.id
    }
    if (dsl.value.key !== doc.key) dsl.value = doc
    dirty.value = false
    ElMessage.success('已儲存')
  } catch (e) {
    // 後端以 compileStatic gate；DSL 不合法時 400 的 message 要讓使用者看得到。
    ElMessage.error('儲存失敗：' + (e.response?.data?.message || e.message))
  }
}

async function handlePublish() {
  try {
    await publishForm(formId.value)
    ElMessage.success('已發佈')
  } catch (e) {
    ElMessage.error('發佈失敗：' + (e.response?.data?.message || e.message))
  }
}

async function handlePreview() {
  previewVisible.value = true
  previewEnvelope.value = null
  previewError.value = ''
  try {
    previewEnvelope.value = await previewForm(JSON.stringify(currentDocument()))
  } catch (e) {
    previewError.value = '預覽失敗：' + (e.response?.data?.message || e.message)
  }
}

onMounted(async () => {
  // 路由參數是 formKey（FormList 以 formKey 導頁）；version 由 query 指明
  // 精確版本 —— 沒有 version 時 API 回最新 published，會把 draft 載成別版。
  const formKeyParam = route.params.id
  if (!formKeyParam) {
    dsl.value = emptyDocument()
    return
  }
  const version = route.query?.version
  loading.value = true
  try {
    // GET /api/forms/{formKey}?version=，回傳定義（schemaJson 內容是 DSL）。
    const def = await getFormSchema(formKeyParam, version)
    const doc = typeof def.schemaJson === 'string' ? JSON.parse(def.schemaJson) : def.schemaJson
    dsl.value = doc
    formId.value = def.id ?? null
    formName.value = def.name ?? ''
    formKey.value = def.formKey ?? doc.key ?? ''
  } catch (e) {
    ElMessage.error('載入表單失敗：' + (e.response?.data?.message || e.message))
    dsl.value = emptyDocument()
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.form-editor { height: 100vh; display: flex; flex-direction: column; }
.toolbar {
  display: flex; align-items: center; padding: 8px 16px;
  border-bottom: 1px solid #e4e7ed; background: #fff;
}
.editor-body { display: flex; flex: 1; overflow: hidden; }
</style>
