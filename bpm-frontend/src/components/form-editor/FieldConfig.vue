<template>
  <div class="field-config" v-if="selectedField">
    <h4 style="margin: 0 0 12px">欄位屬性</h4>

    <!-- 通用 -->
    <el-form label-position="top" size="small">
      <el-form-item label="欄位 ID">
        <el-input :model-value="selectedField.id"
          @update:model-value="update('id', $event)" />
      </el-form-item>
      <el-form-item label="標籤">
        <el-input :model-value="selectedField.label"
          @update:model-value="update('label', $event)" />
      </el-form-item>
      <el-form-item>
        <el-checkbox :model-value="selectedField.required"
          @update:model-value="update('required', $event)">必填</el-checkbox>
        <el-checkbox :model-value="selectedField.readonly"
          @update:model-value="update('readonly', $event)" style="margin-left:12px">唯讀</el-checkbox>
      </el-form-item>

      <!-- text -->
      <template v-if="selectedField.type === 'text'">
        <el-form-item label="Placeholder">
          <el-input :model-value="selectedField.placeholder"
            @update:model-value="update('placeholder', $event)" />
        </el-form-item>
        <el-form-item label="最大長度">
          <el-input-number :model-value="selectedField.maxLength"
            @update:model-value="update('maxLength', $event)" :min="0" />
        </el-form-item>
        <el-form-item label="Pattern (正則)">
          <el-input :model-value="selectedField.pattern"
            @update:model-value="update('pattern', $event)" />
        </el-form-item>
      </template>

      <!-- textarea -->
      <template v-if="selectedField.type === 'textarea'">
        <el-form-item label="最大長度">
          <el-input-number :model-value="selectedField.maxLength"
            @update:model-value="update('maxLength', $event)" :min="0" />
        </el-form-item>
      </template>

      <!-- number / amount -->
      <template v-if="['number', 'amount'].includes(selectedField.type)">
        <el-form-item label="最小值">
          <el-input-number :model-value="selectedField.min"
            @update:model-value="update('min', $event)" />
        </el-form-item>
        <el-form-item label="最大值">
          <el-input-number :model-value="selectedField.max"
            @update:model-value="update('max', $event)" />
        </el-form-item>
        <el-form-item label="小數位數">
          <el-input-number :model-value="selectedField.precision"
            @update:model-value="update('precision', $event)" :min="0" :max="4" />
        </el-form-item>
      </template>

      <!-- date -->
      <template v-if="selectedField.type === 'date'">
        <el-form-item label="格式">
          <el-input :model-value="selectedField.format"
            @update:model-value="update('format', $event)" />
        </el-form-item>
      </template>

      <!-- select / radio / checkbox options -->
      <template v-if="['select', 'radio', 'checkbox'].includes(selectedField.type)">
        <el-form-item label="選項">
          <div v-for="(opt, i) in selectedField.options" :key="i"
            style="display:flex;gap:6px;margin-bottom:4px">
            <el-input v-model="opt.label" placeholder="顯示文字" size="small" />
            <el-input v-model="opt.value" placeholder="值" size="small" />
            <el-button size="small" type="danger" @click="removeOption(i)">刪除</el-button>
          </div>
          <el-button size="small" @click="addOption">新增選項</el-button>
        </el-form-item>
      </template>

      <!-- file -->
      <template v-if="selectedField.type === 'file'">
        <el-form-item label="接受檔案類型">
          <el-input :model-value="selectedField.accept"
            @update:model-value="update('accept', $event)" placeholder=".pdf,.docx" />
        </el-form-item>
        <el-form-item label="最大檔案大小 (MB)">
          <el-input-number :model-value="selectedField.maxSize"
            @update:model-value="update('maxSize', $event)" :min="1" />
        </el-form-item>
        <el-form-item label="最大檔案數">
          <el-input-number :model-value="selectedField.maxCount"
            @update:model-value="update('maxCount', $event)" :min="1" />
        </el-form-item>
      </template>

      <!-- link -->
      <template v-if="selectedField.type === 'link'">
        <el-form-item label="URL">
          <el-input :model-value="selectedField.url"
            @update:model-value="update('url', $event)"
            placeholder="https://... 支援 {processInstanceId}" />
        </el-form-item>
        <el-form-item>
          <el-checkbox :model-value="selectedField.openInNewWindow"
            @update:model-value="update('openInNewWindow', $event)">新視窗開啟</el-checkbox>
        </el-form-item>
      </template>
    </el-form>
  </div>
  <div v-else style="padding:16px;color:#c0c4cc;text-align:center">
    <p>請選擇欄位以編輯屬性</p>
  </div>
</template>

<script setup>
import { useFormEditor } from '../../composables/useFormEditor.js'

const { selectedField, updateField, dirty } = useFormEditor()

function update(prop, value) {
  if (selectedField.value) {
    updateField(selectedField.value.id, { [prop]: value })
  }
}

function addOption() {
  if (!selectedField.value) return
  const opts = [...(selectedField.value.options || [])]
  const n = opts.length + 1
  opts.push({ label: `選項${n}`, value: `opt${n}` })
  updateField(selectedField.value.id, { options: opts })
}

function removeOption(index) {
  if (!selectedField.value) return
  const opts = [...selectedField.value.options]
  opts.splice(index, 1)
  updateField(selectedField.value.id, { options: opts })
}
</script>

<style scoped>
.field-config { padding: 12px; }
</style>
