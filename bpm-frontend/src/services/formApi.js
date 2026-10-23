import http from './http'

// 後端回 Spring Page；選取器與列表需要完整版本清單，不能只看到第一頁。
export const listForms = async () => {
  const forms = []
  let page = 0
  let totalPages = 1
  do {
    const { data } = await http.get('/api/forms', { params: { page, size: 100 } })
    if (Array.isArray(data)) return data
    if (!Array.isArray(data.content)) throw new Error('表單清單格式不正確，請重新載入')
    forms.push(...data.content)
    totalPages = data.totalPages ?? 1
    page += 1
  } while (page < totalPages)
  return forms
}

export const getFormSchema = (formKey, version) => {
  const params = version ? { version } : {}
  return http.get(`/api/forms/${encodeURIComponent(formKey)}`, { params }).then(r => r.data)
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

/** 已發布版本不可改寫；以相同 formKey 建立下一版草稿。 */
export const createFormRevision = (formKey) =>
  http.post(`/api/forms/${encodeURIComponent(formKey)}/revisions`).then(r => r.data)

// 表單資料的寫入路徑已整併：
//   申請／補件 → POST /api/process-instances 與 PUT /api/tasks/{id} 的 formData
//                payload（DocumentDetail 直接組 request，不經 formApi）。
//   讀取      → getFormData（稽核／其他頁面用）。
// 舊的 POST /api/form-data 與 PUT /api/form-data/{id} 已隨 uni-form 重構移除，
// 這裡刻意不再提供對應函式（避免有新的呼叫端接上空端點）。
export const getFormData = (processInstanceId) =>
  http.get(`/api/form-data/${processInstanceId}`).then(r => r.data)
