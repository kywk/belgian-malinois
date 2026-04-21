<template>
  <div style="padding:20px">
    <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:16px">
      <h2 style="margin:0">流程定義管理</h2>
      <el-button type="primary" @click="$router.push('/admin/bpmn-editor')">新建流程</el-button>
    </div>

    <el-table :data="definitions" v-loading="loading" stripe>
      <el-table-column prop="key" label="流程 Key" min-width="160" />
      <el-table-column prop="name" label="名稱" min-width="180" />
      <el-table-column prop="version" label="版本" width="70" />
      <el-table-column prop="deploymentId" label="部署 ID" width="120" />
      <el-table-column prop="suspended" label="狀態" width="80">
        <template #default="{ row }">
          <el-tag :type="row.suspended ? 'danger' : 'success'" size="small">
            {{ row.suspended ? '停用' : '啟用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="160">
        <template #default="{ row }">
          <el-button size="small" @click="editProcess(row)">編輯</el-button>
          <el-button size="small" type="info" @click="viewXml(row)">XML</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- XML 預覽 Dialog -->
    <el-dialog v-model="xmlDialogVisible" title="流程 XML" width="70%" top="5vh">
      <pre style="overflow:auto;max-height:60vh;font-size:12px;background:#f5f5f5;padding:12px;border-radius:4px">{{ xmlContent }}</pre>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getProcessDefinitions, getProcessDefinitionXml } from '../services/flowableApi.js'

const router = useRouter()
const definitions = ref([])
const loading = ref(false)
const xmlDialogVisible = ref(false)
const xmlContent = ref('')

onMounted(async () => {
  loading.value = true
  try {
    const data = await getProcessDefinitions({ latestVersion: true })
    definitions.value = data.data ?? data
  } catch (e) {
    ElMessage.error('載入流程定義失敗')
  } finally {
    loading.value = false
  }
})

function editProcess(row) {
  router.push({ path: '/admin/bpmn-editor', query: { id: row.id } })
}

async function viewXml(row) {
  try {
    xmlContent.value = await getProcessDefinitionXml(row.id)
    xmlDialogVisible.value = true
  } catch (e) {
    ElMessage.error('載入 XML 失敗')
  }
}
</script>
