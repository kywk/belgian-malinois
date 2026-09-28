import http from './http'

export function searchAuditLogs(params) {
  return http.get('/api/audit-logs', { params }).then(r => r.data)
}

export function integrityCheck(startDate, endDate) {
  return http.get('/api/audit-logs/integrity-check', {
    params: { startDate, endDate }
  }).then(r => r.data)
}
