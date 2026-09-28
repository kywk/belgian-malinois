<template>
  <div style="padding: 20px">
    <h2>我的申請</h2>
    <el-tabs v-model="activeTab" @tab-change="loadData">
      <el-tab-pane label="進行中" name="running" />
      <el-tab-pane label="已完成" name="completed" />
      <el-tab-pane label="已拒絕" name="rejected" />
    </el-tabs>

    <el-table :data="list" stripe>
      <el-table-column prop="processDefinitionKey" label="流程名稱" width="160" />
      <el-table-column label="發起時間" width="180">
        <template #default="{ row }">{{ fmt(row.startTime) }}</template>
      </el-table-column>
      <el-table-column label="當前節點" width="140">
        <template #default="{ row }">{{ row.currentTask?.taskName || '-' }}</template>
      </el-table-column>
      <el-table-column label="審核人" width="120">
        <template #default="{ row }">{{ row.currentTask?.assignee || '-' }}</template>
      </el-table-column>
      <el-table-column label="狀態" width="100">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="100" v-if="activeTab === 'running'">
        <template #default="{ row }">
          <el-button size="small" @click="urge(row)">催辦</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../stores/auth'
import { getProcessInstances, getHistoricProcessInstances } from '../services/flowableApi.js'

const auth = useAuthStore()
const userId = computed(() => auth.token)
const activeTab = ref('running')
const list = ref([])
const fmt = (t) => t ? new Date(t).toLocaleString('zh-TW') : ''

const statusType = (s) => ({ running: '', completed: 'success', rejected: 'danger', cancelled: 'info' }[s] || '')
const statusLabel = (s) => ({ running: '進行中', completed: '已完成', rejected: '已拒絕', cancelled: '已取消' }[s] || s)

async function loadData() {
  if (activeTab.value === 'running') {
    list.value = await getProcessInstances({ initiator: userId.value })
  } else {
    const all = await getHistoricProcessInstances({ initiator: userId.value, finished: true })
    list.value = activeTab.value === 'completed'
      ? all.filter(p => p.status === 'completed')
      : all.filter(p => p.status !== 'completed')
  }
}

function urge(row) {
  ElMessage.success(`已發送催辦通知：${row.currentTask?.assignee || '審核人'}`)
}

onMounted(loadData)
</script>
