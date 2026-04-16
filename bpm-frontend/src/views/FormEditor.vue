<template>
  <div class="form-editor">
    <!-- Toolbar -->
    <div class="toolbar">
      <el-input v-model="formName" placeholder="表單名稱" style="width:200px" />
      <el-input v-model="formKey" placeholder="formKey" style="width:160px;margin-left:8px" />
      <el-tag v-if="dirty" type="warning" style="margin-left:8px">未儲存</el-tag>
      <div style="flex:1" />
      <el-button @click="handleSave" :disabled="!formName || !formKey">儲存草稿</el-button>
      <el-button @click="previewVisible = true" :disabled="!fields.length">預覽</el-button>
      <el-button type="primary" @click="handlePublish" :disabled="!formId">發佈</el-button>
    </div>

    <!-- Three-column layout -->
    <div class="editor-body">
      <div class="panel-left"><FieldPalette /></div>
      <div class="panel-center"><FormCanvas /></div>
      <div class="panel-right"><FieldConfig /></div>
    </div>

    <FormPreview v-model="previewVisible" />
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useFormEditor } from '../composables/useFormEditor.js'
import { getFormSchema, createForm, updateForm, publishForm } from '../services/formApi.js'
import FieldPalette from '../components/form-editor/FieldPalette.vue'
import FormCanvas from '../components/form-editor/FormCanvas.vue'
import FieldConfig from '../components/form-editor/FieldConfig.vue'
import FormPreview from '../components/form-editor/FormPreview.vue'

const route = useRoute()
const { fields, formName, formKey, formId, dirty, getSchema, loadSchema, reset } = useFormEditor()
const previewVisible = ref(false)

async function handleSave() {
  const schema = getSchema()
  const payload = { name: formName.value, formKey: formKey.value, schemaJson: JSON.stringify(schema) }
  try {
    if (formId.value) {
      await updateForm(formId.value, payload)
    } else {
      const result = await createForm(payload)
      formId.value = result.id
    }
    dirty.value = false
    ElMessage.success('已儲存')
  } catch (e) {
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

onMounted(async () => {
  reset()
  const id = route.params.id
  if (id) {
    try {
      const def = await getFormSchema(id)
      const schema = typeof def.schemaJson === 'string' ? JSON.parse(def.schemaJson) : def.schemaJson
      loadSchema(schema, def.id, def.name, def.formKey)
    } catch { /* new form */ }
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
.panel-left { width: 250px; border-right: 1px solid #e4e7ed; overflow-y: auto; background: #fafafa; }
.panel-center { flex: 1; overflow-y: auto; background: #f0f2f5; }
.panel-right { width: 300px; border-left: 1px solid #e4e7ed; overflow-y: auto; }
</style>
