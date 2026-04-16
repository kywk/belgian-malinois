<template>
  <el-card>
    <template #header>外部表單</template>
    <el-alert type="info" :closable="false" show-icon
      title="此任務需要在外部系統操作，請點擊下方按鈕開啟外部表單。" style="margin-bottom: 16px" />
    <el-button type="primary" size="large" @click="openExternal">
      開啟外部表單
    </el-button>
    <p style="color: #909399; margin-top: 8px; font-size: 13px">
      開啟後請在外部系統完成操作，再回到此頁面進行審批。
    </p>
  </el-card>
</template>

<script setup>
const props = defineProps({
  formKey: { type: String, required: true },
  processInstanceId: { type: String, default: '' },
  taskId: { type: String, default: '' }
})

function openExternal() {
  let url = props.formKey.replace('external:', '')
  url = url.replace('{processInstanceId}', props.processInstanceId)
  url = url.replace('{taskId}', props.taskId)
  window.open(url, '_blank')
}
</script>
