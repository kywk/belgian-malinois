/**
 * uni-form plugin 安裝（凍結介面見
 * docs/plan/2026-10-12-uniform-form-refactor.md §五 C）。
 *
 * 三份資源的來源與邊界：
 *   - manifests：只載入 `spec/types/core/*.manifest.json` 的**頂層**檔案。
 *     v2 子目錄（core/v2/richtext.manifest.json）是同 type 的 opt-in 版本，
 *     但本專案的 value kind 由 v1 manifest 決定，混入 v2 會讓 type→manifest
 *     的對應取決於載入順序；上一輪 uni-form 的 jackson3 fixture 迴歸就是
 *     誤收 v2 造成的（計畫 §二）。`*.manifest.json` 的 glob 只匹配頂層。
 *   - value schemas：`spec/values/*.schema.json` 全數註冊。
 *     compile 要 stem → `$id`（valueSchemas），validate 的 ajv 要
 *     stem → schema 內容（valueSchemaResources），兩者同一份檔案的兩種視圖。
 *   - widgets：Element Plus pack（text／textarea／number／boolean）
 *     ＋ SDK native 的版面與顯示型別（grid／section／divider／freeText；
 *     與 UI 庫無關，直接重用 SDK 實作）
 *     ＋ greyhound 覆寫（date／dateRange／select／radio／checkbox）。
 *     Map 建構時後者覆蓋同名 type。
 *
 * 後端仍以同一份 spec 做 validateV1 重驗（計畫 §三決策 6）；
 * 這裡的 createCoreValidation 只提供前端即時提示。
 */
import { createCoreValidation, createUniform, nativeWidgets } from '@nanshan/uniform-vue'
import { elementPlusWidgets } from '@nanshan/uniform-element-plus'
import { greyhoundWidgets } from './widgets.js'

const manifestModules = import.meta.glob(
  '@nanshan/uniform-spec/spec/types/core/*.manifest.json',
  { eager: true, import: 'default' },
)

const valueSchemaModules = import.meta.glob(
  '@nanshan/uniform-spec/spec/values/*.schema.json',
  { eager: true, import: 'default' },
)

export const manifests = Object.values(manifestModules)

const valueSchemas = {}
const valueSchemaResources = {}
for (const [path, schema] of Object.entries(valueSchemaModules)) {
  const stem = path.split('/').pop().replace(/\.schema\.json$/, '')
  valueSchemas[stem] = schema.$id
  valueSchemaResources[stem] = schema
}

// 版面（grid／section）與顯示（divider／freeText）型別沿用 SDK native 實作。
// 設計器 palette 會產出 section／grid 容器與 divider／freeText 題目；
// 少了這幾個註冊，任何設計器存出的表單都會被 renderer 判成
// typeUnsupported（「此元件或設定尚未支援」）。
const nativeById = new Map(nativeWidgets.map(widget => [widget.type, widget]))
const layoutAndDisplayWidgets = ['grid', 'section', 'divider', 'freeText']
  .map(type => nativeById.get(type))

export function installUniformPlugin(app) {
  app.use(createUniform({
    manifests,
    widgets: [...elementPlusWidgets, ...layoutAndDisplayWidgets, ...greyhoundWidgets],
    validation: createCoreValidation(manifests, { valueSchemas, valueSchemaResources }),
  }))
}
