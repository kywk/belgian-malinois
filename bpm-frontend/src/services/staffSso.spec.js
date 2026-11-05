import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
vi.mock('./http', () => ({ default: { get: vi.fn(), post: vi.fn() } }))
import http from './http'
import { beginStaffSso, consumeStaffCallback } from './staffSso'

const stateKey = 'bpm.staff.sso.state'
beforeEach(() => {
  vi.clearAllMocks()
  sessionStorage.clear()
  vi.stubGlobal('window', { location: { origin: 'https://bpm.example.test', href: 'https://bpm.example.test/', assign: vi.fn() }, history: { replaceState: vi.fn() } })
})
afterEach(() => vi.unstubAllGlobals())

it('uses only the configured callback and a tab-bound random state', async () => {
  http.get.mockResolvedValue({ data: { loginUrl: 'https://sso.example.test/saml/v1/get-jwt', callbackUrl: 'https://bpm.example.test/' } })
  await beginStaffSso()
  const login = new URL(window.location.assign.mock.calls[0][0])
  const callback = new URL(login.searchParams.get('returnUrl'))
  const stored = JSON.parse(sessionStorage.getItem(stateKey))
  expect(callback.origin).toBe(window.location.origin)
  expect(callback.searchParams.get('bpm_sso_state')).toBe(stored.state)
  expect(stored.state).toHaveLength(64)
})
it('rejects another callback origin before navigation', async () => {
  http.get.mockResolvedValue({ data: { loginUrl: 'https://sso.example.test', callbackUrl: 'https://other.example.test/' } })
  await expect(beginStaffSso()).rejects.toThrow('回呼網址')
  expect(window.location.assign).not.toHaveBeenCalled()
})
it('removes code from history and consumes state before exchanging once', async () => {
  sessionStorage.setItem(stateKey, JSON.stringify({ state: 'expected', createdAt: Date.now() }))
  window.location.href = 'https://bpm.example.test/?code=one-use-code&bpm_sso_state=expected'
  http.post.mockImplementation(async () => {
    expect(window.history.replaceState).toHaveBeenCalledWith({}, '', '/')
    expect(sessionStorage.getItem(stateKey)).toBeNull()
    return { data: { token: 'verified-later' } }
  })
  expect(await consumeStaffCallback()).toBe('verified-later')
  await expect(consumeStaffCallback()).rejects.toThrow('登入狀態')
  expect(http.post).toHaveBeenCalledTimes(1)
})
describe.each(['wrong', 'expired', 'legacy', 'missing'])('rejects %s callback', kind => {
  it('never sends the code to the server', async () => {
    if (kind !== 'missing') sessionStorage.setItem(stateKey, JSON.stringify({ state: 'expected', createdAt: Date.now() - (kind === 'expired' ? 400000 : 0) }))
    window.location.href = `https://bpm.example.test/?code=one-use&bpm_sso_state=${kind === 'wrong' ? 'forged' : 'expected'}${kind === 'legacy' ? '&token=secret' : ''}`
    await expect(consumeStaffCallback()).rejects.toThrow('登入狀態')
    expect(http.post).not.toHaveBeenCalled()
    expect(window.history.replaceState).toHaveBeenCalledWith({}, '', '/')
  })
})

it('preserves router history metadata while stripping the callback code', async () => {
  window.history.state = { position: 4, back: '/tasks' }
  window.location.href = 'https://bpm.example.test/?code=one-use&bpm_sso_state=expected'
  sessionStorage.setItem(stateKey, JSON.stringify({ state: 'expected', createdAt: Date.now() }))
  http.post.mockResolvedValue({ data: { token: 'verified-later' } })
  await consumeStaffCallback()
  expect(window.history.replaceState).toHaveBeenCalledWith({ position: 4, back: '/tasks' }, '', '/')
})
