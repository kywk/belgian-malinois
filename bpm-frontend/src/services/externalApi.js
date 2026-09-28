import axios from 'axios'

// External Systems
export const getExternalSystems = () =>
  axios.get('/api/admin/external-systems').then(r => r.data)

export const getExternalSystem = (systemId) =>
  axios.get(`/api/admin/external-systems/${systemId}`).then(r => r.data)

export const createExternalSystem = (data) =>
  axios.post('/api/admin/external-systems', data).then(r => r.data)

export const updateExternalSystem = (systemId, data) =>
  axios.put(`/api/admin/external-systems/${systemId}`, data).then(r => r.data)

export const deleteExternalSystem = (systemId) =>
  axios.delete(`/api/admin/external-systems/${systemId}`).then(r => r.data)

export const rotateKey = (systemId) =>
  axios.post(`/api/admin/external-systems/${systemId}/rotate-key`).then(r => r.data)

// Process Variable Spec
export const getVariableSpec = (key) =>
  axios.get(`/api/admin/process-definitions/${key}/variable-spec`).then(r => r.data)

export const saveVariableSpec = (key, specs) =>
  axios.post(`/api/admin/process-definitions/${key}/variable-spec`, specs).then(r => r.data)

export const updateVariableSpec = (key, id, spec) =>
  axios.put(`/api/admin/process-definitions/${key}/variable-spec/${id}`, spec).then(r => r.data)
