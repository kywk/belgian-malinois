<template>
  <div style="padding: 20px">
    <el-page-header @back="$router.back()" title="返回" :content="task?.taskName || '任務詳情'" />

    <el-row :gutter="20" style="margin-top: 20px">
      <el-col :span="16">
        <el-card>
          <template #header>表單內容</template>
          <DynamicForm v-if="task?.formKey" :form-key="task.formKey"
            :process-instance-id="task.processInstanceId" mode="review" :variables="variables" />
        </el-card>
      </el-col>
      <el-col :span="8">
        <ProcessDiagram v-if="task?.processInstanceId" :process-instance-id="task.processInstanceId"
          style="margin-bottom: 16px" />
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
      </el-space>
    </el-card>

    <ActionDialog v-model:visible="actionVisible" :action="currentAction"
      :task-id="taskId" @done="onActionDone" />
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getTasks } from '../services/flowableApi.js'
import DynamicForm from '../components/DynamicForm.vue'
import ActionDialog from '../components/ActionDialog.vue'
import CommentPanel from '../components/CommentPanel.vue'
import ApprovalTimeline from '../components/ApprovalTimeline.vue'
import ProcessDiagram from '../components/ProcessDiagram.vue'

const route = useRoute()
const router = useRouter()
const taskId = route.params.taskId
const task = ref(null)
const variables = ref({})
const actionVisible = ref(false)
const currentAction = ref('')

function openAction(action) {
  currentAction.value = action
  actionVisible.value = true
}

function onActionDone() {
  actionVisible.value = false
  router.push('/tasks')
}

onMounted(async () => {
  const tasks = await getTasks({})
  task.value = tasks.find(t => t.taskId === taskId) || null
})
</script>
