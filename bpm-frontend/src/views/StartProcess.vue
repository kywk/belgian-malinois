<template>
  <div style="padding:20px;max-width:600px">
    <h2>發起申請</h2>

    <el-form label-width="100px">
      <el-form-item label="流程類型">
        <el-select v-model="processKey" placeholder="選擇流程" style="width:100%" @change="onProcessChange">
          <el-option label="請假申請" value="leave-approval" />
          <el-option label="採購申請" value="purchase-approval" />
        </el-select>
      </el-form-item>
    </el-form>

    <!-- 請假表單 -->
    <el-form v-if="processKey === 'leave-approval'" :model="leaveForm" label-width="100px">
      <el-form-item label="假別">
        <el-select v-model="leaveForm.leaveType" style="width:100%">
          <el-option label="特休" value="annual" />
          <el-option label="事假" value="personal" />
          <el-option label="病假" value="sick" />
        </el-select>
      </el-form-item>
      <el-form-item label="請假期間">
        <el-date-picker v-model="leaveForm.dateRange" type="daterange"
          start-placeholder="開始日期" end-placeholder="結束日期" style="width:100%" />
      </el-form-item>
      <el-form-item label="事由">
        <el-input v-model="leaveForm.reason" type="textarea" :rows="3" maxlength="500" show-word-limit />
      </el-form-item>
    </el-form>

    <!-- 採購表單 -->
    <el-form v-if="processKey === 'purchase-approval'" :model="purchaseForm" label-width="100px">
      <el-form-item label="品項名稱">
        <el-input v-model="purchaseForm.itemName" />
      </el-form-item>
      <el-form-item label="數量">
        <el-input-number v-model="purchaseForm.quantity" :min="1" style="width:100%" />
      </el-form-item>
      <el-form-item label="金額">
        <el-input-number v-model="purchaseForm.amount" :min="0" :precision="0" style="width:100%" />
      </el-form-item>
      <el-form-item label="採購事由">
        <el-input v-model="purchaseForm.reason" type="textarea" :rows="3" maxlength="500" show-word-limit />
      </el-form-item>
    </el-form>

    <div style="margin-top:16px" v-if="processKey">
      <el-button type="primary" :loading="submitting" @click="submit">送出申請</el-button>
      <el-button @click="$router.push('/my-applications')">取消</el-button>
    </div>

    <el-alert v-if="result" :title="result.msg" :type="result.type" style="margin-top:16px" show-icon />
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import axios from 'axios'

const router = useRouter()
const auth = useAuthStore()
const processKey = ref('')
const submitting = ref(false)
const result = ref(null)

const leaveForm = reactive({ leaveType: 'annual', dateRange: null, reason: '' })
const purchaseForm = reactive({ itemName: '', quantity: 1, amount: 0, reason: '' })

function onProcessChange() { result.value = null }

async function submit() {
  const userId = auth.token
  let variables = {}

  if (processKey.value === 'leave-approval') {
    const [start, end] = leaveForm.dateRange || []
    variables = {
      leaveType: leaveForm.leaveType,
      dateRange: start && end ? `${fmt(start)}~${fmt(end)}` : '',
      reason: leaveForm.reason
    }
  } else {
    variables = {
      itemName: purchaseForm.itemName,
      quantity: purchaseForm.quantity,
      amount: purchaseForm.amount,
      reason: purchaseForm.reason
    }
  }

  submitting.value = true
  try {
    const { data } = await axios.post('/api/process-instances', {
      processDefinitionKey: processKey.value,
      initiator: userId,
      variables
    })
    result.value = { type: 'success', msg: `申請已送出，流程 ID：${data.processInstanceId}，目前審核人：${data.currentTask?.assignee || '-'}` }
    setTimeout(() => router.push('/my-applications'), 2000)
  } catch (e) {
    result.value = { type: 'error', msg: `送出失敗：${e.response?.data?.message || e.message}` }
  } finally {
    submitting.value = false
  }
}

const fmt = (d) => new Date(d).toISOString().slice(0, 10)
</script>
