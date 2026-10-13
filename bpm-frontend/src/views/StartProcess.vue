<template>
  <div style="padding:20px;max-width:640px">
    <h2>發起申請</h2>

    <el-form label-width="100px">
      <el-form-item label="流程類型">
        <el-select v-model="processKey" placeholder="選擇流程" style="width:100%" :loading="definitionsLoading">
          <el-option v-for="def in definitions" :key="def.key"
            :label="def.name || def.key" :value="def.key" />
        </el-select>
      </el-form-item>
    </el-form>

    <el-alert v-if="definitionsError" :title="definitionsError" type="error"
      show-icon :closable="false" style="margin-bottom:16px" />
    <p v-else-if="!definitionsLoading && !definitions.length" style="color:#909399">
      目前沒有可發起的流程（清單只列出有起始表單的流程）。
    </p>

    <!-- 選了流程才有表單：Envelope 由後端依 startEvent 的 Access Profile 產生 -->
    <div v-if="processKey" v-loading="formLoading">
      <el-alert v-if="formError" :title="formError" type="error" show-icon :closable="false" />
      <UfRenderer v-else-if="envelope" ref="rendererRef" :envelope="envelope"
        v-model="formData" v-model:errors="formErrors" />
    </div>

    <div style="margin-top:16px" v-if="processKey">
      <el-button type="primary" :loading="submitting" :disabled="!envelope || formLoading" @click="submit">送出申請</el-button>
      <el-button @click="$router.push('/my-applications')">取消</el-button>
    </div>

    <el-alert v-if="result" :title="result.msg" :type="result.type" style="margin-top:16px" show-icon />
  </div>
</template>

<script setup>
import { ref, watch, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { UfRenderer } from '@nanshan/uniform-vue'
import http from '../services/http'
import { fetchProcessDefinitions, fetchStartForm } from '../uniform/api.js'

const router = useRouter()
const processKey = ref('')
const definitions = ref([])
// 初始即 true：清單載入前不閃「沒有可發起的流程」的提示。
const definitionsLoading = ref(true)
const definitionsError = ref('')
const envelope = ref(null)
const formData = ref({})
const formErrors = ref([])
const formLoading = ref(false)
const formError = ref('')
const rendererRef = ref()
const submitting = ref(false)
const result = ref(null)

// 只列「有 startFormKey」的流程（計畫 §五 A.2）。沒有起始表單的流程
// 選了也只會 404，不如不要出現在清單裡。
onMounted(async () => {
  definitionsLoading.value = true
  try {
    const list = await fetchProcessDefinitions({ latestVersion: true })
    definitions.value = (Array.isArray(list) ? list : []).filter(def => def && def.startFormKey)
  } catch (e) {
    definitionsError.value = '流程清單載入失敗：' + (e.response?.data?.message || e.message)
  } finally {
    definitionsLoading.value = false
  }
})

watch(processKey, async (key) => {
  result.value = null
  envelope.value = null
  formData.value = {}
  formErrors.value = []
  formError.value = ''
  if (!key) return
  formLoading.value = true
  try {
    const env = await fetchStartForm(key)
    envelope.value = env
    formData.value = { ...env.data }
  } catch (e) {
    formError.value = '表單載入失敗：' + (e.response?.data?.message || e.message)
  } finally {
    formLoading.value = false
  }
})

async function submit() {
  const errors = rendererRef.value?.validate?.() ?? []
  if (errors.length) {
    ElMessage.error('表單驗證未通過，請修正後再送出')
    return
  }
  submitting.value = true
  try {
    // #60：表單值走 formData（formDefinitionId 用 Envelope 的 schemaRef.key，
    // 即 BPMN formKey），後端驗證通過才啟動流程並落地表單資料。
    // ⚠️ 不可送 initiator／variables（#66、計畫 §五 A.6）：發起人由登入身分決定，
    // 兩條資料來源並存會讓後端以「重疊」回 400。
    const { data } = await http.post('/api/process-instances', {
      processDefinitionKey: processKey.value,
      formData: {
        formDefinitionId: envelope.value.schemaRef.key,
        dataJson: JSON.stringify(formData.value),
      },
    })
    result.value = { type: 'success', msg: `申請已送出，流程 ID：${data.processInstanceId}，目前審核人：${data.currentTask?.assignee || '-'}` }
    setTimeout(() => router.push('/my-applications'), 2000)
  } catch (e) {
    result.value = { type: 'error', msg: `送出失敗：${e.response?.data?.message || e.message}` }
  } finally {
    submitting.value = false
  }
}
</script>
