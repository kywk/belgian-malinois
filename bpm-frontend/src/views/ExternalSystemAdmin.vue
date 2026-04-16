<template>
  <div style="padding: 20px">
    <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:16px">
      <h2>外部系統管理</h2>
      <el-button type="primary" @click="showCreate = true">建立外部系統</el-button>
    </div>

    <el-table :data="systems" stripe>
      <el-table-column prop="systemId" label="System ID" width="160" />
      <el-table-column prop="systemName" label="名稱" width="160" />
      <el-table-column label="狀態" width="80">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'danger'">{{ row.enabled ? '啟用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="允許流程" min-width="200">
        <template #default="{ row }">
          <el-tag v-for="k in parseJson(row.allowedProcessKeys)" :key="k" size="small" style="margin:2px">{{ k }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="最後使用" width="180">
        <template #default="{ row }">{{ fmt(row.lastUsedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="240">
        <template #default="{ row }">
          <el-button size="small" @click="editSystem(row)">編輯</el-button>
          <el-button size="small" :type="row.enabled ? 'danger' : 'success'"
            @click="toggleEnabled(row)">{{ row.enabled ? '停用' : '啟用' }}</el-button>
          <el-button size="small" type="warning" @click="handleRotate(row)">輪換 Key</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- Create/Edit Dialog -->
    <el-dialog v-model="showCreate" :title="editingId ? '編輯外部系統' : '建立外部系統'" width="600px">
      <el-form :model="form" label-position="top">
        <el-form-item label="System ID" v-if="!editingId">
          <el-input v-model="form.systemId" />
        </el-form-item>
        <el-form-item label="系統名稱" required>
          <el-input v-model="form.systemName" />
        </el-form-item>
        <el-form-item label="聯絡信箱" required>
          <el-input v-model="form.contactEmail" />
        </el-form-item>
        <el-form-item label="允許流程 (JSON array)">
          <el-input v-model="form.allowedProcessKeys" placeholder='["leave-approval","purchase-approval"]' />
        </el-form-item>
        <el-form-item label="允許操作">
          <el-checkbox-group v-model="actions">
            <el-checkbox value="start_process">發起流程</el-checkbox>
            <el-checkbox value="complete_task">完成任務</el-checkbox>
            <el-checkbox value="query_status">查詢狀態</el-checkbox>
            <el-checkbox value="callback">Callback</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="IP 白名單（逗號分隔）">
          <el-input v-model="form.ipWhitelist" type="textarea" :rows="2" placeholder="10.0.0.1,10.0.0.2" />
        </el-form-item>
        <el-form-item label="Callback URL">
          <el-input v-model="form.callbackUrl" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showCreate = false">取消</el-button>
        <el-button type="primary" @click="submitForm">{{ editingId ? '更新' : '建立' }}</el-button>
      </template>
    </el-dialog>

    <!-- API Key Display Dialog -->
    <el-dialog v-model="showKey" title="API Key" width="500px" :close-on-click-modal="false">
      <el-alert type="warning" title="此 Key 僅顯示一次，請立即複製" show-icon :closable="false" style="margin-bottom:12px" />
      <el-input :model-value="newApiKey" readonly>
        <template #append>
          <el-button @click="copyKey">複製</el-button>
        </template>
      </el-input>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getExternalSystems, createExternalSystem, updateExternalSystem, deleteExternalSystem, rotateKey } from '../services/externalApi.js'

const systems = ref([])
const showCreate = ref(false)
const showKey = ref(false)
const newApiKey = ref('')
const editingId = ref(null)
const actions = ref([])
const form = reactive({ systemId: '', systemName: '', contactEmail: '', allowedProcessKeys: '', ipWhitelist: '', callbackUrl: '' })

const fmt = (t) => t ? new Date(t).toLocaleString('zh-TW') : '-'
const parseJson = (s) => { try { return JSON.parse(s || '[]') } catch { return [] } }

async function load() { systems.value = await getExternalSystems() }

function editSystem(row) {
  editingId.value = row.systemId
  Object.assign(form, row)
  actions.value = parseJson(row.allowedActions)
  showCreate.value = true
}

function resetForm() {
  editingId.value = null
  Object.assign(form, { systemId: '', systemName: '', contactEmail: '', allowedProcessKeys: '', ipWhitelist: '', callbackUrl: '' })
  actions.value = []
}

async function submitForm() {
  const data = { ...form, allowedActions: JSON.stringify(actions.value) }
  if (editingId.value) {
    await updateExternalSystem(editingId.value, data)
    ElMessage.success('已更新')
  } else {
    const result = await createExternalSystem(data)
    newApiKey.value = result.apiKey
    showKey.value = true
  }
  showCreate.value = false
  resetForm()
  await load()
}

async function toggleEnabled(row) {
  if (row.enabled) {
    await deleteExternalSystem(row.systemId)
  } else {
    await updateExternalSystem(row.systemId, { ...row, enabled: true })
  }
  await load()
}

async function handleRotate(row) {
  await ElMessageBox.confirm('舊 Key 將立即失效，確定要輪換？', '輪換 API Key', { type: 'warning' })
  const result = await rotateKey(row.systemId)
  newApiKey.value = result.apiKey
  showKey.value = true
}

function copyKey() {
  navigator.clipboard.writeText(newApiKey.value)
  ElMessage.success('已複製')
}

onMounted(load)
</script>
