import axios from 'axios'

export const getFormSchema = (formKey, version) => {
  const params = version ? { version } : {}
  return axios.get(`/api/forms/${formKey}`, { params }).then(r => r.data)
}

export const submitFormData = (data) =>
  axios.post('/api/form-data', data).then(r => r.data)

export const getFormData = (processInstanceId) =>
  axios.get(`/api/form-data/${processInstanceId}`).then(r => r.data)

export const updateFormData = (id, data) =>
  axios.put(`/api/form-data/${id}`, data).then(r => r.data)
