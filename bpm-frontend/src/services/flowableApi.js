import http from './http'

// Tasks
export const getTasks = (params) =>
  http.get('/api/tasks', { params }).then(r => r.data)

export const updateTask = (id, data) =>
  http.put(`/api/tasks/${id}`, data).then(r => r.data)

export const getTaskComments = (taskId) =>
  http.get(`/api/tasks/${taskId}/comments`).then(r => r.data)

export const addTaskComment = (taskId, data) =>
  http.post(`/api/tasks/${taskId}/comments`, data).then(r => r.data)

// 催辦（#6）。用案件 id 而不是 taskId：MyApplications 的 row 只有
// currentTask.taskName／assignee，沒有 taskId；申請人的收件匣也查不到
// 審核人的任務（TaskHolderGuard 正確地不讓非持有者列出）。
export const urgeProcess = (processInstanceId) =>
  http.post('/api/tasks/urge', null, { params: { processInstanceId } }).then(r => r.data)

// Subtasks (countersign)
export const createSubtask = (taskId, data) =>
  http.post(`/api/countersign/${taskId}`, data).then(r => r.data)

export const getSubtasks = (taskId) =>
  http.get(`/api/countersign/${taskId}`).then(r => r.data)

export const completeSubtask = (taskId, subtaskId, data) =>
  http.put(`/api/countersign/${taskId}/${subtaskId}/complete`, data).then(r => r.data)

// Process Instances
export const startProcess = (data) =>
  http.post('/api/process-instances', data).then(r => r.data)

export const getProcessInstances = (params) =>
  http.get('/api/process-instances', { params }).then(r => r.data)

// Process Definitions (Admin)
export const getProcessDefinitions = (params) =>
  http.get('/api/process-definitions', { params }).then(r => r.data)

export const getProcessDefinitionXml = (id) =>
  http.get(`/api/process-definitions/${id}/resourcedata`, { responseType: 'text' }).then(r => r.data)

// History
export const getHistoricTasks = (params) =>
  http.get('/api/history/tasks', { params }).then(r => r.data)

export const getHistoricTaskComments = (taskId) =>
  http.get(`/api/history/tasks/${taskId}/comments`).then(r => r.data)

export const getHistoricProcessInstances = (params) =>
  http.get('/api/history/process-instances', { params }).then(r => r.data)
