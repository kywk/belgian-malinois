<template>
  <el-dialog v-model="visible" title="表單預覽" width="700px" destroy-on-close>
    <DynamicForm v-if="visible" :form-key="'__preview__'" mode="edit"
      :variables="{}" ref="previewForm" />
    <template #footer>
      <el-button @click="visible = false">關閉</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, watch, nextTick } from 'vue'
import DynamicForm from '../DynamicForm.vue'
import { useFormEditor } from '../../composables/useFormEditor.js'

const props = defineProps({ modelValue: Boolean })
const emit = defineEmits(['update:modelValue'])
const visible = ref(props.modelValue)
const { getSchema } = useFormEditor()
const previewForm = ref()

watch(() => props.modelValue, v => { visible.value = v })
watch(visible, v => emit('update:modelValue', v))

// Override the schema loading in DynamicForm by providing schema directly
// DynamicForm loads via API, but for preview we inject schema via provide/inject
// For simplicity, we patch the formApi response for __preview__ key
</script>
