<template>
  <el-card>
    <template #header>審核時間軸</template>
    <el-timeline>
      <el-timeline-item v-for="item in items" :key="item.id"
        :timestamp="fmt(item.endTime || item.startTime)" placement="top"
        :type="timelineType(item)" :hollow="!item.endTime">
        <div>
          <strong>{{ item.name }}</strong>
          <el-tag :type="tagType(item)" size="small" style="margin-left: 8px">
            {{ item.endTime ? '已完成' : '處理中' }}
          </el-tag>
        </div>
        <div style="color: #909399; font-size: 13px">
          審核人：{{ item.assignee || '待認領' }}
        </div>
        <div v-for="c in item.comments" :key="c.id"
          style="margin-top: 6px; padding: 6px 10px; background: #f5f7fa; border-radius: 4px; font-size: 13px">
          {{ c.message }}
        </div>
      </el-timeline-item>
    </el-timeline>
    <el-empty v-if="!items.length" description="暫無審核紀錄" :image-size="60" />
  </el-card>
</template>

<script setup>
import { ref, watch } from 'vue'
import { getHistoricTasks, getHistoricTaskComments } from '../services/flowableApi.js'

const props = defineProps({ processInstanceId: { type: String, default: null } })
const items = ref([])
const fmt = (t) => t ? new Date(t).toLocaleString('zh-TW') : ''

const timelineType = (item) => item.endTime ? 'success' : 'primary'
const tagType = (item) => item.endTime ? 'success' : ''

watch(() => props.processInstanceId, async (pid) => {
  if (!pid) return
  const tasks = await getHistoricTasks({ processInstanceId: pid })
  for (const t of tasks) {
    try {
      t.comments = t.endTime ? await getHistoricTaskComments(t.id) : []
    } catch { t.comments = [] }
  }
  items.value = tasks
}, { immediate: true })
</script>
