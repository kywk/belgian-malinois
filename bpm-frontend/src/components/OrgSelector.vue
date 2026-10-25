<template>
  <div>
    <el-input v-model="keyword" placeholder="搜尋人員（姓名/工號）" clearable
      style="margin-bottom: 8px" />
    <el-alert v-if="searchError" :title="searchError" type="error" show-icon :closable="false" />
    <el-table v-loading="loading" :data="results" highlight-current-row @current-change="onSelect" max-height="300" size="small">
      <el-table-column v-if="multiple" width="50">
        <template #default="{ row }">
          <el-checkbox :model-value="isSelected(row.userId)"
            @change="toggleSelect(row.userId)" />
        </template>
      </el-table-column>
      <el-table-column prop="userId" label="工號" width="100" />
      <el-table-column prop="name" label="姓名" />
      <el-table-column prop="deptId" label="部門" width="120" />
    </el-table>
    <div v-if="displayValue" style="margin-top: 8px; color: #409eff">
      已選擇：{{ displayValue }}
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { searchUsers } from '../services/orgApi.js'

const props = defineProps({
  modelValue: { type: [String, Array], default: '' },
  multiple: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue'])

const keyword = ref('')
const results = ref([])
const loading = ref(false)
const searchError = ref('')

const displayValue = computed(() =>
  Array.isArray(props.modelValue) ? props.modelValue.join(', ') : props.modelValue
)

function isSelected(userId) {
  return Array.isArray(props.modelValue) && props.modelValue.includes(userId)
}

function toggleSelect(userId) {
  const list = Array.isArray(props.modelValue) ? [...props.modelValue] : []
  const idx = list.indexOf(userId)
  if (idx >= 0) list.splice(idx, 1)
  else list.push(userId)
  emit('update:modelValue', list)
}

// 輸入時立即取消上一輪；延遲送出避免每個按鍵都查詢後端。
// watcher cleanup 同時涵蓋切換關鍵字與元件卸載。
watch(keyword, (value, _previous, onCleanup) => {
  results.value = []
  searchError.value = ''
  loading.value = false
  const query = value.trim()
  if (!query) return
  loading.value = true
  const controller = new AbortController()
  const timer = setTimeout(async () => {
    try {
      const users = await searchUsers(query, controller.signal)
      if (!controller.signal.aborted) results.value = users
    } catch (e) {
      if (!controller.signal.aborted) searchError.value = '人員搜尋失敗：' + (e.response?.data?.message || e.message)
    } finally {
      if (!controller.signal.aborted) loading.value = false
    }
  }, 250)
  onCleanup(() => { clearTimeout(timer); controller.abort() })
}, { flush: 'sync' })

function onSelect(row) {
  if (row && !props.multiple) emit('update:modelValue', row.userId)
}
</script>
