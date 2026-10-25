<template>
  <el-dialog :model-value="visible" @update:model-value="$emit('update:visible', $event)"
    :title="titles[action]" width="500px">

    <!-- 同意 -->
    <template v-if="action === 'approve' || action === 'resolve'">
      <p>{{ action === 'resolve' ? '完成轉發處理，將任務交回原審核人？' : '確認同意此任務？' }}</p>
      <el-input v-model="comment" type="textarea" placeholder="審核意見（選填）" :rows="3" />
    </template>

    <!-- 退件 -->
    <template v-else-if="action === 'return'">
      <el-form-item label="退件原因" required>
        <el-input v-model="comment" type="textarea" placeholder="請輸入退件原因" :rows="3" />
      </el-form-item>
    </template>

    <!-- 拒絕 -->
    <template v-else-if="action === 'reject'">
      <el-alert type="error" title="流程將終止，此操作不可撤回" show-icon :closable="false" style="margin-bottom: 12px" />
      <el-form-item label="拒絕原因" required>
        <el-input v-model="comment" type="textarea" placeholder="請輸入拒絕原因" :rows="3" />
      </el-form-item>
    </template>

    <!-- 轉發 -->
    <template v-else-if="action === 'delegate'">
      <OrgSelector v-model="delegateUser" :multiple="false" />
    </template>

    <template #footer>
      <el-button @click="$emit('update:visible', false)">取消</el-button>
      <el-button :type="action === 'reject' ? 'danger' : 'primary'" @click="submit"
        :loading="submitting" :disabled="!canSubmit">確認</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { updateTask, addTaskComment } from '../services/flowableApi.js'
import OrgSelector from './OrgSelector.vue'

const props = defineProps({
  visible: Boolean,
  action: { type: String, default: '' },
  taskId: { type: String, required: true }
})
const emit = defineEmits(['update:visible', 'done'])

const titles = { approve: '同意', return: '退件', reject: '拒絕', delegate: '轉發', resolve: '完成轉發並交回' }
const comment = ref('')
const delegateUser = ref('')
const needsComment = computed(() => ['return', 'reject'].includes(props.action))

const submitting = ref(false)
const canSubmit = computed(() => !submitting.value && (!needsComment.value || Boolean(comment.value.trim()))
  && (props.action !== 'delegate' || Boolean(delegateUser.value)))
watch(() => props.visible, (visible) => {
  if (visible) { comment.value = ''; delegateUser.value = '' }
})

async function submit() {
  if (!canSubmit.value) return
  submitting.value = true
  try {
    // Add comment before completing task (task may not exist after complete)
    if (comment.value && props.action !== 'delegate') {
      await addTaskComment(props.taskId, { message: comment.value, userId: 'current_user' })
    }

    if (props.action === 'approve') {
      await updateTask(props.taskId, {
        action: 'complete',
        variables: [{ name: 'approved', value: true }]
      })
    } else if (props.action === 'return') {
      await updateTask(props.taskId, {
        action: 'complete',
        variables: [{ name: 'approved', value: false }]
      })
    } else if (props.action === 'reject') {
      await updateTask(props.taskId, {
        action: 'complete',
        variables: [
          { name: 'approved', value: false },
          { name: 'rejected', value: true }
        ]
      })
    } else if (props.action === 'resolve') {
      await updateTask(props.taskId, { action: 'resolve' })
    } else if (props.action === 'delegate') {
      await updateTask(props.taskId, { action: 'delegate', delegateUser: delegateUser.value })
    }

    ElMessage.success('操作成功')
    comment.value = ''
    delegateUser.value = ''
    emit('done')
  } catch (e) {
    ElMessage.error('操作失敗：' + (e.response?.data?.message || e.message))
  } finally {
    submitting.value = false
  }
}
</script>
