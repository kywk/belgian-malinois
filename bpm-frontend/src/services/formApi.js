import http from './http'

export const listForms = () =>
  http.get('/api/forms').then(r => r.data)

export const getFormSchema = (formKey, version) => {
  const params = version ? { version } : {}
  return http.get(`/api/forms/${formKey}`, { params }).then(r => r.data)
}

export const createForm = (data) =>
  http.post('/api/forms', data).then(r => r.data)

export const updateForm = (id, data) =>
  http.put(`/api/forms/${id}`, data).then(r => r.data)

export const publishForm = (id) =>
  http.post(`/api/forms/${id}/publish`).then(r => r.data)

export const submitFormData = (data) =>
  http.post('/api/form-data', data).then(r => r.data)

export const getFormData = (processInstanceId) =>
  http.get(`/api/form-data/${processInstanceId}`).then(r => r.data)

export const updateFormData = (id, data) =>
  http.put(`/api/form-data/${id}`, data).then(r => r.data)
