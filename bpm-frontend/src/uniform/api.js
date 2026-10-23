/**
 * uni-form 相關的 REST 呼叫（凍結契約見
 * docs/plan/2026-10-12-uniform-form-refactor.md §五 A）。
 *
 * 一律經 services/http.js 的共用 axios instance（CLAUDE.md 慣例：
 * 身分 header 與錯誤處理集中在 http.js，view 不直接碰 axios）。
 * 後端尚未合併時這些端點不存在 —— 呼叫端必須自己處理失敗並顯示錯誤，
 * 不得讓畫面停在空白。
 */
import http from '../services/http'

/** GET /api/tasks/{taskId}/form → Envelope（includeSchema=true、locale=zh-TW、frame=form）。 */
export const fetchTaskForm = (taskId) =>
  http.get(`/api/tasks/${encodeURIComponent(taskId)}/form`).then(r => r.data)

/** GET /api/process-definitions/{key}/start-form → mode=fill 的 Envelope。 */
export const fetchStartForm = (processKey) =>
  http.get(`/api/process-definitions/${encodeURIComponent(processKey)}/start-form`).then(r => r.data)

/** POST /api/forms/preview → 以 DSL 字串換一份 fill Envelope（設計器預覽用）。 */
export const previewForm = (schemaJson) =>
  http.post('/api/forms/preview', { schemaJson }).then(r => r.data)

/** GET /api/process-definitions → 流程定義清單（列表項含 startFormKey）。 */
export const fetchProcessDefinitions = (params) =>
  http.get('/api/process-definitions', { params }).then(r => r.data)

/** 清冊只供設計器選取已註冊來源，不接受任意 URL 或使用者身分參數。 */
export const fetchOptionProviders = (signal) =>
  http.get('/api/forms/option-providers', { signal }).then(r => r.data)

export const fetchStartOptions = (key, fieldId, applicationContext, data, signal) =>
  http.post(`/api/process-definitions/${encodeURIComponent(key)}/start-form/options`,
    { fieldId, applicationContext, data }, { signal }).then(r => r.data)

export const fetchTaskOptions = (taskId, fieldId, data, signal) =>
  http.post(`/api/tasks/${encodeURIComponent(taskId)}/form/options`, { fieldId, data }, { signal }).then(r => r.data)
