import axios from 'axios'

export const searchUsers = (keyword) =>
  axios.get('/api/org/users/search', { params: { keyword } }).then(r => r.data)

export const getDepartments = () =>
  axios.get('/api/org/departments').then(r => r.data)

export const getDeptMembers = (deptId) =>
  axios.get(`/api/org/departments/${deptId}/members`).then(r => r.data)
