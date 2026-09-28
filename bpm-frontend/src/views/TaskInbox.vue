<template>
  <div style="padding: 20px">
    <h2>待辦清單</h2>
    <el-table :data="tasks" stripe @row-click="goDetail" style="cursor: pointer">
      <el-table-column prop="processDefinitionKey" label="流程名稱" width="160" />
      <el-table-column label="任務名稱">
        <template #default="{ row }">
          {{ row.taskName }}
          <el-tag v-if="row.taskName?.startsWith('加簽')" size="small" type="warning" style="margin-left:4px">加簽</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="assignee" label="審核人" width="120" />
      <el-table-column label="建立時間" width="180">
        <template #default="{ row }">{{ fmt(row.createTime) }}</template>
      </el-table-column>
      <el-table-column prop="formKey" label="表單" width="140" />
    </el-table>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { getTasks } from '../services/flowableApi.js'

const router = useRouter()
const auth = useAuthStore()
const userId = auth.token
const tasks = ref([])
const fmt = (t) => t ? new Date(t).toLocaleString('zh-TW') : ''

const goDetail = (row) => router.push(`/tasks/${row.taskId}`)

onMounted(async () => {
  // Query both assignee and candidateUser, deduplicate by taskId
  const [assigned, candidate] = await Promise.all([
    getTasks({ assignee: userId }),
    getTasks({ candidateUser: userId })
  ])
  const map = new Map()
  ;[...assigned, ...candidate].forEach(t => map.set(t.taskId, t))
  tasks.value = [...map.values()].sort((a, b) => new Date(b.createTime) - new Date(a.createTime))
})
</script>
