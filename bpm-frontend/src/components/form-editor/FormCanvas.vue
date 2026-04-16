<template>
  <div class="form-canvas">
    <draggable v-model="fields" group="fields" item-key="id" @change="dirty = true"
      class="canvas-drop" :class="{ empty: !fields.length }">
      <template #item="{ element }">
        <div class="canvas-field" :class="{ selected: selectedFieldId === element.id }"
          @click="selectField(element.id)">
          <div class="field-header">
            <span class="field-label">
              {{ element.label }} <el-tag size="small" type="info">{{ element.type }}</el-tag>
              <span v-if="element.required" style="color: #f56c6c"> *</span>
            </span>
            <el-button class="delete-btn" type="danger" size="small" circle
              @click.stop="removeField(element.id)">
              <el-icon><Delete /></el-icon>
            </el-button>
          </div>
          <div class="field-preview">
            <el-input v-if="element.type === 'text'" disabled :placeholder="element.placeholder || element.label" />
            <el-input v-else-if="element.type === 'textarea'" type="textarea" disabled :rows="2" />
            <el-input-number v-else-if="['number', 'amount'].includes(element.type)" disabled />
            <el-date-picker v-else-if="element.type === 'date'" disabled />
            <el-date-picker v-else-if="element.type === 'dateRange'" type="daterange" disabled />
            <el-select v-else-if="element.type === 'select'" disabled placeholder="請選擇" />
            <el-radio-group v-else-if="element.type === 'radio'" disabled>
              <el-radio v-for="o in (element.options || [])" :key="o.value" :value="o.value">{{ o.label }}</el-radio>
            </el-radio-group>
            <el-checkbox-group v-else-if="element.type === 'checkbox'" disabled>
              <el-checkbox v-for="o in (element.options || [])" :key="o.value" :value="o.value">{{ o.label }}</el-checkbox>
            </el-checkbox-group>
            <el-button v-else-if="element.type === 'file'" disabled>上傳檔案</el-button>
            <el-button v-else-if="element.type === 'orgSelector'" disabled>選擇人員</el-button>
            <div v-else-if="element.type === 'richtext'" style="color:#909399">說明文字區塊</div>
            <el-link v-else-if="element.type === 'link'" disabled>{{ element.label }}</el-link>
          </div>
        </div>
      </template>
    </draggable>
    <div v-if="!fields.length" class="empty-hint">
      <el-icon size="48" color="#dcdfe6"><Plus /></el-icon>
      <p>拖拉左側元件到此處</p>
    </div>
  </div>
</template>

<script setup>
import draggable from 'vuedraggable'
import { Delete, Plus } from '@element-plus/icons-vue'
import { useFormEditor } from '../../composables/useFormEditor.js'

const { fields, selectedFieldId, selectField, removeField, dirty } = useFormEditor()
</script>

<style scoped>
.form-canvas { padding: 16px; min-height: 400px; position: relative; }
.canvas-drop { min-height: 300px; }
.canvas-field {
  padding: 12px; margin-bottom: 8px; border: 2px solid #e4e7ed;
  border-radius: 6px; background: #fff; cursor: pointer; position: relative;
}
.canvas-field:hover { border-color: #c0c4cc; }
.canvas-field.selected { border-color: #409eff; box-shadow: 0 0 0 2px rgba(64,158,255,0.2); }
.field-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.field-label { font-weight: 500; }
.delete-btn { opacity: 0; transition: opacity 0.2s; }
.canvas-field:hover .delete-btn { opacity: 1; }
.field-preview { pointer-events: none; }
.empty-hint {
  position: absolute; top: 50%; left: 50%; transform: translate(-50%, -50%);
  text-align: center; color: #c0c4cc;
}
</style>
