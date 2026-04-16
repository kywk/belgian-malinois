<template>
  <div style="padding: 20px">
    <el-page-header @back="$router.back()" :content="`流程變數規格 — ${processKey}`" />

    <el-table :data="specs" stripe style="margin-top:16px">
      <el-table-column label="變數名稱" min-width="150">
        <template #default="{ row }">
          <el-input v-model="row.variableName" size="small" />
        </template>
      </el-table-column>
      <el-table-column label="型別" width="130">
        <template #default="{ row }">
          <el-select v-model="row.variableType" size="small">
            <el-option v-for="t in types" :key="t" :value="t" :label="t" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="必填" width="70">
        <template #default="{ row }">
          <el-checkbox v-model="row.required" />
        </template>
      </el-table-column>
      <el-table-column label="說明" min-width="180">
        <template #default="{ row }">
          <el-input v-model="row.description" size="small" />
        </template>
      </el-table-column>
      <el-table-column label="範例" width="140">
        <template #default="{ row }">
          <el-input v-model="row.example" size="small" />
        </template>
      </el-table-column>
      <el-table-column label="" width="70">
        <template #default="{ $index }">
          <el-button size="small" type="danger" @click="specs.splice($index, 1)">刪除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div style="margin-top:12px;display:flex;gap:8px">
      <el-button @click="addRow">新增行</el-button>
      <el-button type="primary" @click="save">儲存</el-button>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getVariableSpec, saveVariableSpec } from '../services/externalApi.js'

const route = useRoute()
const processKey = route.params.key
const specs = ref([])
const types = ['string', 'number', 'date', 'boolean']

function addRow() {
  specs.value.push({ variableName: '', variableType: 'string', required: false, description: '', example: '' })
}

async function save() {
  await saveVariableSpec(processKey, specs.value)
  ElMessage.success('已儲存')
}

onMounted(async () => {
  specs.value = await getVariableSpec(processKey)
  if (!specs.value.length) addRow()
})
</script>
