import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { mount, enableAutoUnmount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import FieldConfig from './FieldConfig.vue'
import { useFormEditor } from '../../composables/useFormEditor.js'

/**
 * #56：{@code FieldConfig.vue} 的「選項 API」輸入。
 *
 * 這裡驗的是**設計師填的 URL 真的寫進 schema**（{@code optionsUrl}）——
 * 沒有這條，UI 上看起來有輸入框，但存檔後 schema 裡沒有它，
 * {@code DynamicForm} 就永遠不會走遠端路徑，而畫面上不會有任何錯誤。
 *
 * <p>{@code useFormEditor} 是模組層單例（設計器三個面板共用同一份狀態），
 * 所以測試直接用它建欄位、再從 {@code getSchema()} 讀結果 —— 這正是
 * FormEditor 儲存時走的那條路。
 */

enableAutoUnmount(afterEach)

const { addField, selectField, getSchema, reset } = useFormEditor()

const optionsUrlInput = (wrapper) =>
  wrapper.find('input[placeholder="https://.../options.json"]')

function mountConfig() {
  return mount(FieldConfig, { global: { plugins: [ElementPlus] } })
}

beforeEach(() => {
  reset()
})

describe('FieldConfig 的選項 API 設定（#56）', () => {
  it('select 欄位輸入 optionsUrl → 寫入 schema 的該欄位', async () => {
    selectField(addField('select').id)
    const wrapper = mountConfig()

    const input = optionsUrlInput(wrapper)
    expect(input.exists()).toBe(true)
    await input.setValue('https://hr.example.com/options.json')

    expect(getSchema().fields[0].optionsUrl).toBe('https://hr.example.com/options.json')
  })

  it('非 select 欄位不顯示選項 API 輸入', () => {
    selectField(addField('text').id)
    const wrapper = mountConfig()

    expect(optionsUrlInput(wrapper).exists()).toBe(false)
  })

  it('清空輸入 → optionsUrl 寫成空字串（等同未設定）', async () => {
    const field = addField('select')
    selectField(field.id)
    const wrapper = mountConfig()

    await optionsUrlInput(wrapper).setValue('https://hr.example.com/options.json')
    await optionsUrlInput(wrapper).setValue('')

    expect(getSchema().fields[0].optionsUrl).toBe('')
  })
})
