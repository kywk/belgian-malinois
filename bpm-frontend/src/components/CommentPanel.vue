<template>
  <el-card>
    <template #header>批註</template>
    <div style="margin-bottom: 12px">
      <el-input v-model="newComment" type="textarea" :rows="2" placeholder="輸入批註..." />
      <el-button type="primary" size="small" style="margin-top: 8px" @click="submit" :disabled="!newComment.trim()">
        送出
      </el-button>
    </div>
    <el-divider />
    <div v-for="c in comments" :key="c.id" style="margin-bottom: 12px">
      <div style="display: flex; justify-content: space-between; color: #909399; font-size: 12px">
        <span>{{ c.userId }}</span>
        <span>{{ c.time }}</span>
      </div>
      <div>{{ c.message }}</div>
    </div>
    <el-empty v-if="!comments.length" description="暫無批註" :image-size="60" />
  </el-card>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { getTaskComments, addTaskComment } from '../services/flowableApi.js'

const props = defineProps({ taskId: { type: String, required: true } })
const comments = ref([])
const newComment = ref('')

async function load() {
  if (props.taskId) comments.value = await getTaskComments(props.taskId)
}

async function submit() {
  await addTaskComment(props.taskId, { message: newComment.value, userId: 'current_user' })
  newComment.value = ''
  await load()
}

onMounted(load)
</script>
