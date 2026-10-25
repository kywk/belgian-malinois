<template>
  <div style="padding: 20px">
    <el-page-header @back="$router.back()" title="返回" :content="task?.taskName || '任務詳情'" />

    <!--
      ⚠️ #68b：審核人原本在這裡看不到「這張單是代誰發起的」。
      R-20 規定外部系統發起時 initiator 一律是 system:<id>、員工記在 onBehalfOf，
      所以主管打開案件只看到一個沒有申請人的單 —— 無從判斷該問誰補件。
      放在表單卡片「上方」而不是欄位之間：它是這一頁的**解讀前提**
      （下面每一欄的資料都是那位員工的），不是表單裡的一個欄位。
    -->
    <el-alert
      v-if="task?.onBehalfOf"
      type="warning"
      show-icon
      :closable="false"
      style="margin-bottom:16px"
      :title="`本案件由外部系統代 ${task.onBehalfOf} 發起`"
      description="表單內容與補件關卡都是這位員工的。退回時請以本系統的資料為準，或退回要求系統端補正。"
    />

    <el-row :gutter="20" style="margin-top: 20px">
      <el-col :span="16">
        <el-card>
          <template #header>表單內容</template>
          <!-- External form -->
          <ExternalFormLink v-if="isExternal" :form-key="task.formKey"
            :process-instance-id="task.processInstanceId" :task-id="taskId" />
          <!-- uni-form Envelope：fill＝補件（可編輯）、view＝審核（唯讀）。
               載入失敗必須顯示原因 —— 空白卡片會讓審核者以為「這張單沒有內容」。 -->
          <template v-else>
            <el-skeleton v-if="formLoading" :rows="3" animated />
            <el-alert v-else-if="formError" type="error" show-icon :closable="false" :title="formError" />
            <UfRenderer v-else-if="envelope" ref="rendererRef" :envelope="envelope"
              v-model="formData" v-model:errors="formErrors" />
            <p v-else style="color:#909399;margin:0">此任務沒有動態表單。</p>
          </template>
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

    <el-row v-if="task?.processInstanceId && !isCountersign" :gutter="20" style="margin-top: 16px">
      <el-col :span="12">
        <ApprovalTimeline :process-instance-id="task?.processInstanceId" />
      </el-col>
      <el-col :span="12">
        <CommentPanel :task-id="taskId" />
      </el-col>
    </el-row>

    <el-card style="margin-top: 16px">
      <!-- 權限的唯一來源是 Envelope.mode：fill＝補件、view＝審核。
           ⚠️ fail-closed：Envelope 還沒到（載入中／失敗／此任務無表單）時
           「不顯示任何任務動作」——以任務名稱猜測會讓補件任務在後端失聯時
           露出一整排審核按鈕（同意／拒絕），而補件任務的受指派人是申請人本人。
           載入狀態與失敗原因由上方表單區呈現。 -->
      <template v-if="isCountersign">
        <p v-if="task.description">加簽說明：{{ task.description }}</p>
        <el-input v-model="countersignOpinion" type="textarea" aria-label="加簽意見"
          placeholder="請輸入加簽意見" :rows="3" style="margin-bottom:12px" />
        <el-button type="primary" :loading="submitting" :disabled="!countersignOpinion.trim() || !envelope || formLoading || Boolean(formError)" @click="handleCountersignComplete">完成加簽</el-button>
      </template>
      <el-space v-else-if="isDelegated && envelope && !formError">
        <span>轉發人：{{ task.owner }}</span>
        <el-button type="primary" @click="openAction('resolve')">完成轉發並交回</el-button>
      </el-space>
      <el-space v-else-if="isRevision">
        <el-button type="primary" :loading="submitting" @click="handleRevisionSubmit()">補件重送</el-button>
      </el-space>
      <el-space v-else-if="envelope?.mode === 'view'">
        <el-button type="success" :disabled="subtasks.length > 0" @click="openAction('approve')">同意</el-button>
        <el-button type="warning" :disabled="subtasks.length > 0" @click="openAction('return')">退件</el-button>
        <el-button type="danger" :disabled="subtasks.length > 0" @click="openAction('reject')">拒絕</el-button>
        <el-button @click="openAction('delegate')">轉發</el-button>
        <el-button @click="csVisible = true">加簽</el-button>
      </el-space>
      <p v-else style="color:#909399;margin:0">{{ actionHint }}</p>
    </el-card>

    <ActionDialog v-model:visible="actionVisible" :action="currentAction"
      :task-id="taskId" @done="onActionDone" />
    <CountersignDialog v-model="csVisible" :task-id="taskId" @done="loadSubtasks" />
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { ElMessage } from 'element-plus'
import UfRenderer from '../uniform/UniformForm.vue'
import { getTasks, getSubtasks, updateTask, completeSubtask } from '../services/flowableApi.js'
import { provideOptionsHost } from '../uniform/options.js'
import { fetchTaskForm } from '../uniform/api.js'
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
const subtasks = ref([])
const actionVisible = ref(false)
const csVisible = ref(false)
const currentAction = ref('')

// ── uni-form Envelope ────────────────────────────────────────────
// envelope.mode 是唯一的權限來源（fill＝補件、view＝審核），
// 不再從任務名稱推測；Envelope 未載入時任務動作一律不顯示（fail-closed），
// 載入狀態與失敗原因呈現在表單區。
const envelope = ref(null)
const formData = ref({})
const formErrors = ref([])
const formLoading = ref(false)
const formError = ref('')
const rendererRef = ref()
const submitting = ref(false)

provideOptionsHost(envelope, computed(() => envelope.value ? { taskId } : null), undefined, { formData })

const isExternal = computed(() => task.value?.formKey?.startsWith('external:'))
const countersignOpinion = ref('')
const isCountersign = computed(() => Boolean(task.value?.parentTaskId))
const isDelegated = computed(() => task.value?.delegationState === 'PENDING')
const isRevision = computed(() => envelope.value?.mode === 'fill')
const actionHint = computed(() =>
  !task.value?.formKey || isExternal.value
    ? '此任務沒有動態表單。'
    : '表單載入完成後才能執行任務操作；載入失敗請重新整理。',
)

function openAction(action) {
  currentAction.value = action
  actionVisible.value = true
}

function onActionDone() {
  actionVisible.value = false
  router.push('/tasks')
}

async function loadForm() {
  if ((!task.value?.formKey && !isCountersign.value) || isExternal.value) return
  formLoading.value = true
  formError.value = ''
  try {
    const env = await fetchTaskForm(taskId)
    // Envelope 是後端產物，但載入路徑可能遇到代理錯誤／舊快取；UfRenderer
    // 假定 envelope.data 存在（SDK 契約），缺件時在 setup 就拋錯 —— 這裡先擋，
    // 讓畫面顯示明確原因而不是破圖。
    if (!env || typeof env !== 'object' || !env.schemaRef || env.data === undefined) {
      throw new Error('表單回應格式不正確')
    }
    envelope.value = env
    formData.value = { ...env.data }
    formErrors.value = []
  } catch (e) {
    envelope.value = null
    formError.value = '表單載入失敗：' + (e.response?.data?.message || e.message)
  } finally {
    formLoading.value = false
  }
}

async function handleRevisionSubmit() {
  // 前端 validate 只是提示（計畫 §三決策 6）——後端仍會以 validateV1 重驗。
  // 有錯誤就不送，讓使用者先修正；renderer 已同步把錯誤畫在畫面上。
  const errors = rendererRef.value?.validate?.() ?? []
  if (errors.length) {
    ElMessage.error('表單驗證未通過，請修正後再送出')
    return
  }
  submitting.value = true
  try {
    // 補件走 FormData（計畫 §五 A.5）：後端驗證通過後存新版本列、
    // 推導流程變數再完成任務。不再送 variables／assignee。
    await updateTask(taskId, {
      action: 'complete',
      formData: { dataJson: JSON.stringify(Object.fromEntries(Object.entries(formData.value).filter(([key]) => key !== '$labels'))) },
    })
    ElMessage.success('補件重送成功')
    router.push('/tasks')
  } catch (e) {
    ElMessage.error('重送失敗：' + (e.response?.data?.message || e.message))
  } finally {
    submitting.value = false
  }
}

async function handleCountersignComplete() {
  if (submitting.value || !countersignOpinion.value.trim() || !envelope.value || formLoading.value || formError.value) return
  submitting.value = true
  try {
    await completeSubtask(task.value.parentTaskId, taskId, { opinion: countersignOpinion.value.trim() })
    ElMessage.success('加簽完成，已交回原審核人')
    router.push('/tasks')
  } catch (e) {
    ElMessage.error('加簽失敗：' + (e.response?.data?.message || e.message))
  } finally {
    submitting.value = false
  }
}

async function loadSubtasks() {
  if (isCountersign.value) return
  try { subtasks.value = await getSubtasks(taskId) } catch { subtasks.value = [] }
}

onMounted(async () => {
  const [assigned, candidate] = await Promise.all([
    getTasks({ assignee: auth.userId }),
    getTasks({ candidateUser: auth.userId })
  ])
  const map = new Map()
  ;[...assigned, ...candidate].forEach(t => map.set(t.taskId, t))
  task.value = map.get(taskId) || null

  await loadForm()
  await loadSubtasks()
})
</script>
