import { beforeEach, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
vi.mock('../services/staffSso', () => ({ staffSsoEnabled: true, fetchStaffSession: vi.fn() }))
vi.mock('../services/permissionsApi', () => ({ fetchMyPermissions: vi.fn() }))
import { fetchStaffSession } from '../services/staffSso'
import { useAuthStore } from './auth'
const token = subject => `e30.${btoa(JSON.stringify({ sub: subject, exp: Date.now() / 1000 + 600, roles: ['admin', 'audit:log:read'] }))}.signature`
const session = userId => ({ userId, userType: 'STAFF', deptId: '50310001', permissions: ['bpm:console:access:use', 'bpm:task:work:read'] })
beforeEach(() => { setActivePinia(createPinia()); localStorage.clear(); sessionStorage.clear(); vi.clearAllMocks() })
it('waits for server identity and uses canonical user id instead of JWT alias', async () => {
  let resolve
  fetchStaffSession.mockImplementation(() => new Promise(r => { resolve = r }))
  const auth = useAuthStore()
  const pending = auth.setToken(token('alias'))
  expect(auth.isAuthenticated).toBe(false)
  expect(auth.isAdmin).toBe(false)
  resolve(session('W9000001'))
  await pending
  expect(auth.isAuthenticated).toBe(true)
  expect(auth.userId).toBe('W9000001')
  expect(auth.roles).toEqual([])
  expect(auth.canActTasks).toBe(false)
  expect(auth.isAdmin).toBe(false)
})
it('fails closed on 503 and never falls back to signed roles', async () => {
  fetchStaffSession.mockRejectedValue({ response: { status: 503, data: { message: '權限來源中斷' } } })
  const auth = useAuthStore(); await auth.setToken(token('alias'))
  expect(auth.isAuthenticated).toBe(false)
  expect(auth.isAdmin).toBe(false)
  expect(auth.permissions).toEqual([])
  expect(auth.sessionError).toBe('權限來源中斷')
})
it('does not let a stale response replace a newer login', async () => {
  let resolveFirst
  fetchStaffSession.mockImplementationOnce(() => new Promise(r => { resolveFirst = r }))
    .mockResolvedValueOnce(session('W9000002'))
  const auth = useAuthStore(); const first = auth.setToken(token('first'))
  await auth.setToken(token('second'))
  resolveFirst(session('W9000001')); await first
  expect(auth.userId).toBe('W9000002')
})
it('system management does not imply form design or audit access', async () => {
  const data = session('W9000001'); data.permissions.push('bpm:system:config:manage')
  fetchStaffSession.mockResolvedValue(data)
  const auth = useAuthStore(); await auth.setToken(token('alias'))
  expect(auth.isAdmin).toBe(true)
  expect(auth.canDesignForms).toBe(false)
  expect(auth.isAuditor).toBe(false)
})

it('shows task actions only with an explicit task act grant', async () => {
  const data = session('W9000001'); data.permissions.push('bpm:task:work:act')
  fetchStaffSession.mockResolvedValue(data)
  const auth = useAuthStore(); await auth.setToken(token('alias'))
  expect(auth.canActTasks).toBe(true)
})
