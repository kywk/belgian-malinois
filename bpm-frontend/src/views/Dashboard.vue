<template>
  <div style="padding: 20px">
    <h2>儀表板</h2>
    <el-row :gutter="16" style="margin-bottom: 20px">
      <el-col :span="8">
        <el-card shadow="hover">
          <el-statistic title="待辦事項" :value="stats.pending" />
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="hover">
          <el-statistic title="本週已處理" :value="stats.completedThisWeek" />
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="hover" class="urgent-card">
          <el-statistic title="緊急待辦（>3天）" :value="stats.urgent" value-style="color: #f56c6c" />
        </el-card>
      </el-col>
    </el-row>

    <el-card>
      <template #header>緊急待辦（超過 3 天未處理）</template>
      <el-table :data="urgentTasks" stripe>
        <el-table-column prop="taskName" label="任務名稱" />
        <el-table-column prop="processDefinitionKey" label="流程" />
        <el-table-column prop="assignee" label="審核人" width="120" />
        <el-table-column label="建立時間" width="180">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button size="small" type="primary" @click="$router.push(`/tasks/${row.taskId}`)">處理</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { getTasks, getHistoricTasks } from '../services/flowableApi.js'

const userId = 'current_user' // TODO: replace with auth store
const stats = reactive({ pending: 0, completedThisWeek: 0, urgent: 0 })
const urgentTasks = ref([])

const THREE_DAYS = 3 * 24 * 60 * 60 * 1000

function formatTime(t) { return t ? new Date(t).toLocaleString('zh-TW') : '' }

onMounted(async () => {
  const tasks = await getTasks({ assignee: userId })
  stats.pending = tasks.length

  const now = Date.now()
  const urgent = tasks.filter(t => now - new Date(t.createTime).getTime() > THREE_DAYS)
  stats.urgent = urgent.length
  urgentTasks.value = urgent.slice(0, 5)

  const weekStart = new Date()
  weekStart.setDate(weekStart.getDate() - weekStart.getDay())
  weekStart.setHours(0, 0, 0, 0)
  const history = await getHistoricTasks({ assignee: userId })
  stats.completedThisWeek = history.filter(t => new Date(t.endTime) >= weekStart).length
})
</script>
