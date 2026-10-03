import http from './http'

// External Systems
export const getExternalSystems = () =>
  http.get('/api/admin/external-systems').then(r => r.data)

export const getExternalSystem = (systemId) =>
  http.get(`/api/admin/external-systems/${systemId}`).then(r => r.data)

export const createExternalSystem = (data) =>
  http.post('/api/admin/external-systems', data).then(r => r.data)

export const updateExternalSystem = (systemId, data) =>
  http.put(`/api/admin/external-systems/${systemId}`, data).then(r => r.data)

export const deleteExternalSystem = (systemId) =>
  http.delete(`/api/admin/external-systems/${systemId}`).then(r => r.data)

export const rotateKey = (systemId) =>
  http.post(`/api/admin/external-systems/${systemId}/rotate-key`).then(r => r.data)

// #21：輪換回呼密鑰。後端回傳的 callbackSecret 是明文，僅此一次
// （列表／詳情的同名欄位一律是 *** 或 null），呼叫端必須立刻呈現給人保存。
export const rotateCallbackSecret = (systemId) =>
  http.post(`/api/admin/external-systems/${systemId}/rotate-callback-secret`).then(r => r.data)

// Process Variable Spec
export const getVariableSpec = (key) =>
  http.get(`/api/admin/process-definitions/${key}/variable-spec`).then(r => r.data)

export const saveVariableSpec = (key, specs) =>
  http.post(`/api/admin/process-definitions/${key}/variable-spec`, specs).then(r => r.data)

export const updateVariableSpec = (key, id, spec) =>
  http.put(`/api/admin/process-definitions/${key}/variable-spec/${id}`, spec).then(r => r.data)
