import axios from 'axios'

export function searchAuditLogs(params) {
  return axios.get('/api/audit-logs', { params }).then(r => r.data)
}

export function integrityCheck(startDate, endDate) {
  return axios.get('/api/audit-logs/integrity-check', {
    params: { startDate, endDate }
  }).then(r => r.data)
}
