import axios from 'axios'

export const listForms = () =>
  axios.get('/api/forms').then(r => r.data)

export const getFormSchema = (formKey, version) => {
  const params = version ? { version } : {}
  return axios.get(`/api/forms/${formKey}`, { params }).then(r => r.data)
}

export const createForm = (data) =>
  axios.post('/api/forms', data).then(r => r.data)

export const updateForm = (id, data) =>
  axios.put(`/api/forms/${id}`, data).then(r => r.data)

export const publishForm = (id) =>
  axios.post(`/api/forms/${id}/publish`).then(r => r.data)

export const submitFormData = (data) =>
  axios.post('/api/form-data', data).then(r => r.data)

export const getFormData = (processInstanceId) =>
  axios.get(`/api/form-data/${processInstanceId}`).then(r => r.data)

export const updateFormData = (id, data) =>
  axios.put(`/api/form-data/${id}`, data).then(r => r.data)
