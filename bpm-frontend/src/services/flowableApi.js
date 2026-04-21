import axios from 'axios'

// Tasks
export const getTasks = (params) =>
  axios.get('/api/tasks', { params }).then(r => r.data)

export const updateTask = (id, data) =>
  axios.put(`/api/tasks/${id}`, data).then(r => r.data)

export const getTaskComments = (taskId) =>
  axios.get(`/api/tasks/${taskId}/comments`).then(r => r.data)

export const addTaskComment = (taskId, data) =>
  axios.post(`/api/tasks/${taskId}/comments`, data).then(r => r.data)

// Subtasks (countersign)
export const createSubtask = (taskId, data) =>
  axios.post(`/api/countersign/${taskId}`, data).then(r => r.data)

export const getSubtasks = (taskId) =>
  axios.get(`/api/countersign/${taskId}`).then(r => r.data)

export const completeSubtask = (taskId, subtaskId, data) =>
  axios.put(`/api/countersign/${taskId}/${subtaskId}/complete`, data).then(r => r.data)

// Process Instances
export const startProcess = (data) =>
  axios.post('/api/process-instances', data).then(r => r.data)

export const getProcessInstances = (params) =>
  axios.get('/api/process-instances', { params }).then(r => r.data)

export const getProcessDiagram = (id) =>
  axios.get(`/api/process-instances/${id}/diagram`, { responseType: 'blob' }).then(r => r.data)

// Process Definitions (Admin)
export const getProcessDefinitions = (params) =>
  axios.get('/api/process-definitions', { params }).then(r => r.data)

export const getProcessDefinitionXml = (id) =>
  axios.get(`/api/process-definitions/${id}/resourcedata`, { responseType: 'text' }).then(r => r.data)

// History
export const getHistoricTasks = (params) =>
  axios.get('/api/history/tasks', { params }).then(r => r.data)

export const getHistoricTaskComments = (taskId) =>
  axios.get(`/api/history/tasks/${taskId}/comments`).then(r => r.data)

export const getHistoricProcessInstances = (params) =>
  axios.get('/api/history/process-instances', { params }).then(r => r.data)
