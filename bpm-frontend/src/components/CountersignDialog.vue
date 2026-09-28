<template>
  <el-dialog v-model="visible" title="加簽" width="500px">
    <p>選擇加簽人員（可多選）：</p>
    <OrgSelector v-model="selectedUsers" :multiple="true" />
    <el-input v-model="description" type="textarea" :rows="2" placeholder="加簽說明（選填）"
      style="margin-top: 12px" />
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" @click="submit" :disabled="!selectedUsers.length">確認加簽</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { createSubtask } from '../services/flowableApi.js'
import OrgSelector from './OrgSelector.vue'

const props = defineProps({ modelValue: Boolean, taskId: { type: String, required: true } })
const emit = defineEmits(['update:modelValue', 'done'])
const visible = ref(props.modelValue)
const selectedUsers = ref([])
const description = ref('')

watch(() => props.modelValue, v => { visible.value = v })
watch(visible, v => emit('update:modelValue', v))

async function submit() {
  const users = Array.isArray(selectedUsers.value) ? selectedUsers.value : [selectedUsers.value]
  try {
    for (const userId of users) {
      // createSubtask 的簽章是 (taskId, data) —— 改動前這裡只傳一個物件，
      // 導致實際發出 POST /api/countersign/[object Object] 且 body 為 undefined，
      // @RequestBody 直接 400。這是 TC-A01 驗收不過的直接原因。
      // 欄位名也必須對齊後端：countersignUserId / message（不是 assignee / description）。
      await createSubtask(props.taskId, {
        countersignUserId: userId,
        message: description.value,
      })
    }
    ElMessage.success(`已發送加簽請求給 ${users.length} 人`)
    selectedUsers.value = []
    description.value = ''
    emit('done')
    visible.value = false
  } catch (e) {
    ElMessage.error('加簽失敗：' + (e.response?.data?.message || e.message))
  }
}
</script>
