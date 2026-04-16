<template>
  <div class="field-palette">
    <div v-for="group in groups" :key="group.name" style="margin-bottom: 12px">
      <div style="font-weight: bold; margin-bottom: 6px; color: #606266">{{ group.name }}</div>
      <draggable :list="group.items" :group="{ name: 'fields', pull: 'clone', put: false }"
        :clone="cloneField" :sort="false" item-key="type">
        <template #item="{ element }">
          <div class="palette-item">
            <el-icon style="margin-right: 6px"><component :is="element.icon || 'EditPen'" /></el-icon>
            {{ element.label }}
          </div>
        </template>
      </draggable>
    </div>
  </div>
</template>

<script setup>
import draggable from 'vuedraggable'
import { useFormEditor } from '../../composables/useFormEditor.js'

const { addField } = useFormEditor()

const groups = [
  {
    name: '基礎',
    items: [
      { type: 'text', label: '單行文字' },
      { type: 'textarea', label: '多行文字' },
      { type: 'number', label: '數字' },
      { type: 'date', label: '日期' },
      { type: 'dateRange', label: '日期區間' }
    ]
  },
  {
    name: '選擇',
    items: [
      { type: 'select', label: '下拉選單' },
      { type: 'radio', label: '單選' },
      { type: 'checkbox', label: '多選' }
    ]
  },
  {
    name: '進階',
    items: [
      { type: 'file', label: '檔案上傳' },
      { type: 'orgSelector', label: '人員選擇' },
      { type: 'amount', label: '金額' }
    ]
  },
  {
    name: '展示',
    items: [
      { type: 'richtext', label: '說明文字' },
      { type: 'link', label: '連結' }
    ]
  }
]

function cloneField(item) {
  return addField(item.type)
}
</script>

<style scoped>
.field-palette { padding: 12px; }
.palette-item {
  padding: 8px 12px; margin-bottom: 4px; background: #f5f7fa;
  border: 1px solid #e4e7ed; border-radius: 4px; cursor: grab;
  display: flex; align-items: center; font-size: 13px;
}
.palette-item:hover { background: #ecf5ff; border-color: #409eff; }
</style>
