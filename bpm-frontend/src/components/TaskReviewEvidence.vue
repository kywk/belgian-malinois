<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import http from '../services/http.js'

const props = defineProps({ taskId: { type: String, required: true } })
const emit = defineEmits(['access-lost'])
const evidence = ref(null)
const preview = ref(null)
const error = ref('')
const loading = ref(false)
const abort = new AbortController()
let download
let checking = false
let timer
const hasPdfViewer = navigator.pdfViewerEnabled !== false
const base = '/api/tasks/' + encodeURIComponent(props.taskId) + '/review-evidence'

function closePreview() {
  download?.abort()
  if (preview.value) URL.revokeObjectURL(preview.value.url)
  preview.value = null
}

function unavailable(cause) {
  closePreview()
  evidence.value = null
  error.value = '此任務的送件附件目前無法取得。'
  if ([401, 403, 404].includes(cause.response?.status)) emit('access-lost', cause.response.status)
}

async function refresh() {
  if (checking || document.hidden || abort.signal.aborted) return
  checking = true
  try {
    const { data } = await http.get(base, { signal: abort.signal })
    if (!abort.signal.aborted && !document.hidden) {
      evidence.value = data
      error.value = ''
    }
  } catch (cause) {
    if (!abort.signal.aborted) unavailable(cause)
  } finally { checking = false }
}

function visibilityChanged() {
  if (document.hidden) {
    closePreview()
    evidence.value = null
  } else refresh()
}

onMounted(() => {
  refresh()
  window.addEventListener('focus', refresh)
  document.addEventListener('visibilitychange', visibilityChanged)
  // A different session can reassign the same task while this page stays visible.
  timer = window.setInterval(refresh, 5000)
})
onBeforeUnmount(() => {
  window.clearInterval(timer)
  window.removeEventListener('focus', refresh)
  document.removeEventListener('visibilitychange', visibilityChanged)
  abort.abort()
  closePreview()
})

async function openDocument(doc) {
  if (loading.value || !evidence.value) return
  closePreview()
  download = new AbortController()
  const { signal } = download
  error.value = ''
  loading.value = true
  const { applicationId, submissionId, revision } = evidence.value
  try {
    const { data } = await http.get(base + '/documents/' + encodeURIComponent(doc.documentId), {
      params: { applicationId, submissionId, revision }, responseType: 'blob', signal,
    })
    if (signal.aborted) return
    if (!(data instanceof Blob) || data.size !== doc.sizeBytes || data.type !== doc.contentType ||
        !['image/jpeg', 'image/png', 'application/pdf'].includes(data.type)) throw new Error()
    preview.value = { url: URL.createObjectURL(data), fileName: doc.fileName, type: data.type }
  } catch (cause) {
    if (signal.aborted) return
    if ([401, 403, 404].includes(cause.response?.status)) unavailable(cause)
    else error.value = '附件無法開啟，請重新進入待辦確認任務與版本。'
  } finally { loading.value = false }
}
</script>

<template>
  <el-card class="evidence">
    <template #header>本次送件附件</template>
    <p v-if="error" role="alert">{{ error }}</p>
    <template v-if="evidence">
      <p>送件版本 {{ evidence.revision }}</p>
      <p v-if="evidence.revision > 1">前版退件原因與附件的跨版核閱權限待確認。</p>
      <p v-if="!evidence.documents.length">本次送件沒有附件。</p>
      <ul v-else>
        <li v-for="doc in evidence.documents" :key="doc.documentId">
          {{ doc.fileName }}（檔案版本 {{ doc.version }}）
          <span>來源：{{ doc.source === 'MOCK' ? 'Recruitment 開發附件' : doc.source }}</span>
          <el-button :disabled="loading" @click="openDocument(doc)">核閱附件</el-button>
        </li>
      </ul>
      <section v-if="preview" :aria-label="preview.fileName">
        <p>{{ preview.fileName }} <el-button @click="closePreview">關閉預覽</el-button></p>
        <img v-if="preview.type !== 'application/pdf'" :src="preview.url" :alt="preview.fileName">
        <iframe v-else-if="hasPdfViewer" :src="preview.url" :title="preview.fileName" referrerpolicy="no-referrer" />
        <a v-else :href="preview.url" :download="preview.fileName">下載本版 PDF 核閱</a>
      </section>
    </template>
    <p v-else-if="!error">正在讀取送件附件…</p>
  </el-card>
</template>

<style scoped>
.evidence { margin-top: 16px; }
img { max-width: 100%; height: auto; }
iframe { width: 100%; height: 60vh; border: 0; }
</style>
