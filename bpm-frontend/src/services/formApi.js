import http from './http'

export const listForms = () =>
  http.get('/api/forms').then(r => r.data)

export const getFormSchema = (formKey, version) => {
  const params = version ? { version } : {}
  return http.get(`/api/forms/${formKey}`, { params }).then(r => r.data)
}

/**
 * select 欄位的動態選項（#56）。
 *
 * 前端不直接連外部選項來源：由後端代理（SSRF 閘門、快取、逾時都在那裡）。
 * 回傳正規化後的 [{label,value}] 陣列。
 */
export const getFormOptions = (url) =>
  http.get('/api/forms/options', { params: { url } }).then(r => r.data.options)

export const createForm = (data) =>
  http.post('/api/forms', data).then(r => r.data)

export const updateForm = (id, data) =>
  http.put(`/api/forms/${id}`, data).then(r => r.data)

export const publishForm = (id) =>
  http.post(`/api/forms/${id}/publish`).then(r => r.data)

// 表單資料的寫入路徑已整併：
//   申請／補件 → POST /api/process-instances 與 PUT /api/tasks/{id} 的 formData
//                payload（DocumentDetail 直接組 request，不經 formApi）。
//   讀取      → getFormData（稽核／其他頁面用）。
// 舊的 POST /api/form-data 與 PUT /api/form-data/{id} 已隨 uni-form 重構移除，
// 這裡刻意不再提供對應函式（避免有新的呼叫端接上空端點）。
export const getFormData = (processInstanceId) =>
  http.get(`/api/form-data/${processInstanceId}`).then(r => r.data)
