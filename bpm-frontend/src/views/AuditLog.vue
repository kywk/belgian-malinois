<template>
  <div style="padding: 20px">
    <h2>稽核 Log 查詢</h2>

    <!-- 查詢條件 -->
    <el-form :model="query" inline>
      <el-form-item label="流程實例 ID">
        <el-input v-model="query.processInstanceId" clearable placeholder="PRC-..." />
      </el-form-item>
      <el-form-item label="操作人">
        <el-input v-model="query.operatorId" clearable placeholder="user ID" />
      </el-form-item>
      <el-form-item label="操作類型">
        <el-select v-model="query.operationType" clearable placeholder="全部">
          <el-option v-for="t in operationTypes" :key="t" :label="t" :value="t" />
        </el-select>
      </el-form-item>
      <el-form-item label="時間區間">
        <el-date-picker v-model="query.dateRange" type="datetimerange"
          range-separator="至" start-placeholder="開始" end-placeholder="結束"
          value-format="YYYY-MM-DDTHH:mm:ss[Z]" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="doSearch">查詢</el-button>
        <el-button @click="doIntegrityCheck">完整性驗證</el-button>
      </el-form-item>
    </el-form>

    <!-- 驗證結果 -->
    <el-alert v-if="integrityResult" :type="integrityResult.intact ? 'success' : 'error'"
      :title="integrityResult.intact
        ? `驗證通過：${integrityResult.checked} 筆紀錄完整`
        : `驗證失敗：${integrityResult.broken} 筆異常（首筆 ID: ${integrityResult.firstBrokenId}）`"
      show-icon closable style="margin-bottom: 16px" />

    <!-- 結果表格 -->
    <el-table :data="logs" stripe border style="width: 100%">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="operationType" label="操作類型" width="160">
        <template #default="{ row }">
          <el-tag :type="tagType(row.operationType)" size="small">{{ row.operationType }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="operatorId" label="操作人" width="120" />
      <el-table-column prop="operatorName" label="姓名" width="100" />
      <el-table-column prop="operatorSource" label="來源" width="90" />
      <el-table-column prop="processInstanceId" label="流程實例" width="160" show-overflow-tooltip />
      <el-table-column prop="businessKey" label="業務單號" width="140" show-overflow-tooltip />
      <el-table-column prop="taskId" label="任務 ID" width="120" show-overflow-tooltip />
      <el-table-column prop="detail" label="詳情" min-width="200" show-overflow-tooltip />
      <el-table-column prop="createdAt" label="時間" width="180">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column prop="hashValue" label="Hash" width="120" show-overflow-tooltip />
    </el-table>

    <!-- 分頁 -->
    <el-pagination style="margin-top: 16px; justify-content: flex-end"
      v-model:current-page="page" v-model:page-size="size"
      :total="total" :page-sizes="[20, 50, 100]"
      layout="total, sizes, prev, pager, next" @change="doSearch" />
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { searchAuditLogs, integrityCheck } from '../services/auditLogApi.js'

const operationTypes = [
  'PROCESS_START', 'PROCESS_CANCEL', 'PROCESS_COMPLETE',
  'TASK_APPROVE', 'TASK_REJECT', 'TASK_RETURN', 'TASK_RETURN_INITIATOR',
  'TASK_DELEGATE', 'TASK_REASSIGN', 'TASK_COUNTERSIGN',
  'TASK_COMMENT', 'TASK_CLAIM', 'TASK_URGE',
  'FORM_SUBMIT', 'FORM_UPDATE', 'BPMN_DEPLOY',
  'EXTERNAL_API_CALL', 'DATA_ACCESS', 'CONFIG_CHANGE', 'EXPORT_DATA'
]

const query = reactive({ processInstanceId: '', operatorId: '', operationType: '', dateRange: null })
const logs = ref([])
const page = ref(1)
const size = ref(20)
const total = ref(0)
const integrityResult = ref(null)

function tagType(op) {
  if (op.includes('APPROVE') || op === 'PROCESS_COMPLETE') return 'success'
  if (op.includes('REJECT')) return 'danger'
  if (op.includes('RETURN')) return 'warning'
  if (op.includes('DEPLOY') || op.includes('CONFIG')) return 'info'
  return ''
}

function formatTime(t) {
  return t ? new Date(t).toLocaleString('zh-TW') : ''
}

async function doSearch() {
  const params = { page: page.value - 1, size: size.value }
  if (query.processInstanceId) params.processInstanceId = query.processInstanceId
  if (query.operatorId) params.operatorId = query.operatorId
  if (query.operationType) params.operationType = query.operationType
  if (query.dateRange?.length === 2) {
    params.startDate = query.dateRange[0]
    params.endDate = query.dateRange[1]
  }
  const data = await searchAuditLogs(params)
  logs.value = data.content
  total.value = data.totalElements
}

async function doIntegrityCheck() {
  if (!query.dateRange?.length) {
    return alert('請先選擇時間區間')
  }
  integrityResult.value = await integrityCheck(query.dateRange[0], query.dateRange[1])
}

doSearch()
</script>
