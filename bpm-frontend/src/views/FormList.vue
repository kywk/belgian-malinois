<template>
  <div style="padding:20px">
    <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:16px">
      <h2 style="margin:0">表單管理</h2>
      <el-button type="primary" @click="$router.push('/admin/form-editor')">新建表單</el-button>
    </div>

    <el-table :data="forms" v-loading="loading" stripe>
      <el-table-column prop="formKey" label="Form Key" min-width="160" />
      <el-table-column prop="name" label="名稱" min-width="180" />
      <el-table-column prop="version" label="版本" width="70" />
      <el-table-column prop="status" label="狀態" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 'published' ? 'success' : 'warning'" size="small">
            {{ row.status === 'published' ? '已發佈' : '草稿' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="80">
        <template #default="{ row }">
          <el-button size="small" @click="$router.push(`/admin/form-editor/${row.id}`)">編輯</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { listForms } from '../services/formApi.js'

const forms = ref([])
const loading = ref(false)

onMounted(async () => {
  loading.value = true
  try {
    const data = await listForms()
    forms.value = data.data ?? data
  } catch (e) {
    ElMessage.error('載入表單列表失敗')
  } finally {
    loading.value = false
  }
})
</script>
