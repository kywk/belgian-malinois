import http from './http'

export const searchUsers = (keyword, signal) =>
  http.get('/api/org/users/search', { params: { keyword }, signal }).then(r => r.data)

export const getDepartments = () =>
  http.get('/api/org/departments').then(r => r.data)

export const getDeptMembers = (deptId) =>
  http.get(`/api/org/departments/${deptId}/members`).then(r => r.data)
