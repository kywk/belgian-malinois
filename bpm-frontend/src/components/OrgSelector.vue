<template>
  <div>
    <el-input v-model="keyword" placeholder="搜尋人員（姓名/工號）" clearable @input="doSearch"
      style="margin-bottom: 8px" />
    <el-table :data="results" highlight-current-row @current-change="onSelect" max-height="300" size="small">
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
import { ref, computed } from 'vue'
import { searchUsers } from '../services/orgApi.js'

const props = defineProps({
  modelValue: { type: [String, Array], default: '' },
  multiple: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue'])

const keyword = ref('')
const results = ref([])

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

async function doSearch() {
  if (keyword.value.length < 1) { results.value = []; return }
  try { results.value = await searchUsers(keyword.value) } catch { results.value = [] }
}

function onSelect(row) {
  if (row && !props.multiple) emit('update:modelValue', row.userId)
}
</script>
