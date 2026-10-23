<template>
  <div class="start-process">
    <h2>發起申請</h2>
    <el-form label-width="100px">
      <el-form-item label="流程類型">
        <el-select v-model="processKey" placeholder="選擇流程" style="width:100%"
          :loading="definitionsLoading" :disabled="submitting">
          <el-option v-for="def in definitions" :key="def.key" :label="def.name || def.key" :value="def.key" />
        </el-select>
      </el-form-item>
    </el-form>
    <el-alert v-if="definitionsError" :title="definitionsError" type="error" show-icon :closable="false" />
    <p v-else-if="!definitionsLoading && !definitions.length">
      目前沒有可發起的流程（清單只列出有起始表單的流程）。
    </p>

    <div v-if="processKey" v-loading="formLoading">
      <el-alert v-if="versionConflict" title="流程或表單已改版，尚未送件。原填寫內容已保留，請載入最新版並重新檢視。"
        type="warning" show-icon :closable="false" />
      <el-button v-if="versionConflict" :disabled="formLoading || submitting" @click="refreshForReview">
        載入最新版並保留填寫內容
      </el-button>
      <el-alert v-if="formError" :title="formError" type="error" show-icon :closable="false">
        <el-button v-if="!versionConflict" :disabled="formLoading" @click="loadStartForm(processKey)">重新載入</el-button>
      </el-alert>
      <template v-if="envelope">
        <p v-if="applicationContext">申請表第 {{ applicationContext.formVersion }} 版</p>
        <fieldset class="form-fields" :disabled="submitting || versionConflict" :inert="submitting || versionConflict">
          <UfRenderer :key="formGeneration" ref="rendererRef" :envelope="envelope"
            v-model="formData" v-model:errors="formErrors" />
        </fieldset>
      </template>
      <template v-if="retainedData">
        <el-alert title="已保留改版前的完整填寫內容。請比對新版欄位；被移除或變更型別的答案可在下方查看。"
          type="info" show-icon :closable="false" />
        <el-collapse class="retained-answers">
          <el-collapse-item title="查看改版前的完整填寫內容" name="retained">
            <pre>{{ JSON.stringify(retainedData, null, 2) }}</pre>
          </el-collapse-item>
        </el-collapse>
        <el-checkbox v-if="!versionConflict && envelope" v-model="reviewed">
          已比對新版欄位與保留內容，確認本次送出資料
        </el-checkbox>
      </template>
    </div>

    <div v-if="processKey" class="submit-actions">
      <el-button type="primary" :loading="submitting" :disabled="!canSubmit" @click="submit">送出申請</el-button>
      <el-button :disabled="submitting" @click="$router.push('/my-applications')">取消</el-button>
    </div>
    <el-alert v-if="result" :title="result.msg" :type="result.type" class="submit-actions" show-icon />
  </div>
</template>

<script setup>
import { computed, ref, watch, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { UfRenderer } from '@nanshan/uniform-vue'
import http from '../services/http'
import { fetchProcessDefinitions, fetchStartForm } from '../uniform/api.js'
import { manifests } from '../uniform/setup.js'

const router = useRouter()
const processKey = ref('')
const definitions = ref([])
const definitionsLoading = ref(true)
const definitionsError = ref('')
const envelope = ref(null)
const applicationContext = ref(null)
const formData = ref({})
const formErrors = ref([])
const formLoading = ref(false)
const formError = ref('')
const rendererRef = ref()
const submitting = ref(false)
const result = ref(null)
const versionConflict = ref(false)
const retainedData = ref(null)
const reviewed = ref(false)
const formGeneration = ref(0)
const loadedSchema = ref(null)
let requestGeneration = 0
let redirectTimer
let active = true

const canSubmit = computed(() => !!envelope.value && !!applicationContext.value && !formLoading.value
  && !submitting.value && !versionConflict.value && (!retainedData.value || reviewed.value)
  && result.value?.type !== 'success')
const copy = value => JSON.parse(JSON.stringify(value))
const detail = e => e.response?.data?.message || e.message
const kinds = new Map(manifests.map(manifest => [manifest.type, manifest.kind]))

// 沿用 manifest 的資料層：版面不開層，repeater 子欄位才開新層。
function fieldsAtLayer(nodes = []) {
  return nodes.flatMap(node => kinds.get(node.type) === 'layout' ? fieldsAtLayer(node.children)
    : kinds.get(node.type) === 'field' && node.key ? [node] : [])
}

function compatibleAnswers(previous, oldNodes, newNodes) {
  const oldFields = new Map(fieldsAtLayer(oldNodes).map(node => [node.key, node]))
  return Object.fromEntries(fieldsAtLayer(newNodes).flatMap(node => {
    const old = oldFields.get(node.key)
    if (!old || old.type !== node.type || !Object.hasOwn(previous, node.key)) return []
    const value = previous[node.key]
    if (node.type === 'repeater' && Array.isArray(value)) {
      return [[node.key, value.map(row => row && typeof row === 'object' && !Array.isArray(row)
        ? compatibleAnswers(row, old.children, node.children) : row)]]
    }
    return [[node.key, value]]
  }))
}

onMounted(async () => {
  try {
    const list = await fetchProcessDefinitions({ latestVersion: true })
    if (active) definitions.value = (Array.isArray(list) ? list : []).filter(def => def && def.startFormKey)
  } catch (e) {
    if (active) definitionsError.value = '流程清單載入失敗：' + detail(e)
  } finally {
    if (active) definitionsLoading.value = false
  }
})

// key 切換及重新載入各自遞增 generation，過期請求不得更新表單／loading／錯誤。
watch(processKey, key => {
  clearTimeout(redirectTimer)
  result.value = null
  versionConflict.value = false
  retainedData.value = null
  reviewed.value = false
  formData.value = {}
  loadedSchema.value = null
  formErrors.value = []
  loadStartForm(key)
}, { flush: 'sync' })

async function loadStartForm(key, preserve = false) {
  const generation = ++requestGeneration
  const previous = preserve ? copy(formData.value) : null
  const previousSchema = loadedSchema.value
  envelope.value = null
  applicationContext.value = null
  formError.value = ''
  formLoading.value = !!key
  if (!key) return
  try {
    const env = await fetchStartForm(key)
    if (!active || generation !== requestGeneration || key !== processKey.value) return
    const context = env.applicationContext
    if (!context?.processDefinitionId || !context.formDefinitionId || !context.formKey || !Number.isInteger(context.formVersion)
      || context.formVersion < 1
      || context.formKey !== env.schemaRef?.key || context.formVersion !== env.schemaRef?.version) {
      throw new Error('申請版本資訊不完整或不一致，請重新載入')
    }
    applicationContext.value = { ...context }
    formData.value = preserve ? { ...copy(env.data), ...compatibleAnswers(previous, previousSchema?.root, env.schema?.root) } : copy(env.data)
    loadedSchema.value = env.schema
    formErrors.value = []
    envelope.value = env
    formGeneration.value += 1
    if (preserve) { versionConflict.value = false; reviewed.value = false }
  } catch (e) {
    if (active && generation === requestGeneration) formError.value = '表單載入失敗：' + detail(e)
  } finally {
    if (active && generation === requestGeneration) formLoading.value = false
  }
}

async function refreshForReview() {
  if (formLoading.value || submitting.value) return
  if (!retainedData.value) retainedData.value = [{ version: applicationContext.value?.formVersion, answers: copy(formData.value) }]
  await loadStartForm(processKey.value, true)
}

async function submit() {
  if (!canSubmit.value) return
  if (!rendererRef.value?.validate) { ElMessage.error('表單尚未準備完成，請稍後再試'); return }
  const errors = rendererRef.value.validate()
  if (errors.length) {
    ElMessage.error('表單驗證未通過，請修正後再送出')
    return
  }
  const generation = requestGeneration
  const key = processKey.value
  const context = { ...applicationContext.value }
  const dataJson = JSON.stringify(formData.value)
  submitting.value = true
  result.value = null
  try {
    const { data } = await http.post('/api/process-instances', {
      processDefinitionKey: key,
      processDefinitionId: context.processDefinitionId,
      formData: {
        formDefinitionId: context.formDefinitionId,
        formVersion: context.formVersion,
        dataJson,
      },
    })
    if (!active || generation !== requestGeneration || key !== processKey.value) return
    result.value = { type: 'success', msg: '申請已送出，流程 ID：' + data.processInstanceId + '，目前審核人：' + (data.currentTask?.assignee || '-') }
    redirectTimer = setTimeout(() => router.push('/my-applications'), 2000)
  } catch (e) {
    if (!active || generation !== requestGeneration || key !== processKey.value) return
    if (e.response?.status === 409) {
      versionConflict.value = true
      reviewed.value = false
      // 完整答案另存記憶體；新版 Renderer 即使移除欄位也不會吃掉原答案。
      retainedData.value = [...(retainedData.value || []), {
        version: context.formVersion, answers: JSON.parse(dataJson),
      }]
      result.value = { type: 'warning', msg: '版本已更新，請載入最新版並檢視後再送出。' }
    } else {
      result.value = { type: 'error', msg: '送出失敗：' + detail(e) }
    }
  } finally {
    if (active) submitting.value = false
  }
}

onBeforeUnmount(() => { active = false; requestGeneration += 1; clearTimeout(redirectTimer) })
</script>

<style scoped>
.start-process { padding: 20px; max-width: 720px; }
.submit-actions { margin-top: 16px; }
.retained-answers { margin-top: 12px; }
.form-fields { border: 0; padding: 0; margin: 0; min-width: 0; }
.retained-answers pre { white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
