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
      </el-timeline-item>
    </el-timeline>
    <el-empty v-if="!items.length" description="暫無審核紀錄" :image-size="60" />
  </el-card>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { getHistoricTasks } from '../services/flowableApi.js'

const props = defineProps({ processInstanceId: { type: String, default: null } })
const items = ref([])
const fmt = (t) => t ? new Date(t).toLocaleString('zh-TW') : ''

const timelineType = (item) => item.endTime ? 'success' : 'primary'
const tagType = (item) => item.endTime ? 'success' : ''

onMounted(async () => {
  if (props.processInstanceId) {
    items.value = await getHistoricTasks({ processInstanceId: props.processInstanceId })
  }
})
</script>
