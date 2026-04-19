<template>
  <div style="padding: 20px">
    <el-page-header @back="$router.back()" title="返回" :content="task?.taskName || '任務詳情'" />

    <el-row :gutter="20" style="margin-top: 20px">
      <el-col :span="16">
        <el-card>
          <template #header>表單內容</template>
          <!-- External form -->
          <ExternalFormLink v-if="isExternal" :form-key="task.formKey"
            :process-instance-id="task.processInstanceId" :task-id="taskId" />
          <!-- Dynamic form -->
          <DynamicForm v-else-if="task?.formKey" :form-key="task.formKey"
            :process-instance-id="task.processInstanceId" mode="review" :variables="variables" />
        </el-card>
      </el-col>
      <el-col :span="8">
        <ProcessDiagram v-if="task?.processInstanceId" :process-instance-id="task.processInstanceId"
          style="margin-bottom: 16px" />
        <!-- Countersign status -->
        <el-card v-if="subtasks.length" style="margin-bottom: 16px">
          <template #header>加簽狀態</template>
          <div v-for="st in subtasks" :key="st.taskId" style="margin-bottom: 8px">
            <el-tag size="small" type="warning">加簽</el-tag>
            {{ st.assignee }} — {{ st.name }}
          </div>
          <p style="color:#909399;font-size:13px">等待加簽 {{ subtasks.length }} 人完成</p>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="20" style="margin-top: 16px">
      <el-col :span="12">
        <ApprovalTimeline :process-instance-id="task?.processInstanceId" />
      </el-col>
      <el-col :span="12">
        <CommentPanel :task-id="taskId" />
      </el-col>
    </el-row>

    <el-card style="margin-top: 16px">
      <el-space>
        <el-button type="success" @click="openAction('approve')">同意</el-button>
        <el-button type="warning" @click="openAction('return')">退件</el-button>
        <el-button type="danger" @click="openAction('reject')">拒絕</el-button>
        <el-button @click="openAction('delegate')">轉發</el-button>
        <el-button @click="csVisible = true">加簽</el-button>
      </el-space>
    </el-card>

    <ActionDialog v-model:visible="actionVisible" :action="currentAction"
      :task-id="taskId" @done="onActionDone" />
    <CountersignDialog v-model="csVisible" :task-id="taskId" @done="loadSubtasks" />
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import axios from 'axios'
import { useAuthStore } from '../stores/auth'
import { getTasks, getSubtasks } from '../services/flowableApi.js'
import DynamicForm from '../components/DynamicForm.vue'
import ExternalFormLink from '../components/ExternalFormLink.vue'
import ActionDialog from '../components/ActionDialog.vue'
import CountersignDialog from '../components/CountersignDialog.vue'
import CommentPanel from '../components/CommentPanel.vue'
import ApprovalTimeline from '../components/ApprovalTimeline.vue'
import ProcessDiagram from '../components/ProcessDiagram.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const taskId = route.params.taskId
const task = ref(null)
const variables = ref({})
const subtasks = ref([])
const actionVisible = ref(false)
const csVisible = ref(false)
const currentAction = ref('')

const isExternal = computed(() => task.value?.formKey?.startsWith('external:'))

function openAction(action) {
  currentAction.value = action
  actionVisible.value = true
}

function onActionDone() {
  actionVisible.value = false
  router.push('/tasks')
}

async function loadSubtasks() {
  try { subtasks.value = await getSubtasks(taskId) } catch { subtasks.value = [] }
}

onMounted(async () => {
  const [assigned, candidate] = await Promise.all([
    getTasks({ assignee: auth.token }),
    getTasks({ candidateUser: auth.token })
  ])
  const map = new Map()
  ;[...assigned, ...candidate].forEach(t => map.set(t.taskId, t))
  task.value = map.get(taskId) || null

  if (task.value?.processInstanceId) {
    try {
      const { data } = await axios.get(`/api/process-instances/${task.value.processInstanceId}/variables`)
      variables.value = data
    } catch { /* ignore */ }
  }
  await loadSubtasks()
})
</script>
