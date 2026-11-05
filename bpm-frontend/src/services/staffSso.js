import http from './http'

export const staffSsoEnabled = import.meta.env.VITE_STAFF_SSO === 'true'
const STATE_KEY = 'bpm.staff.sso.state'
const MAX_AGE = 5 * 60 * 1000

/** State belongs to this tab. Only a configured same-origin callback is accepted. */
export async function beginStaffSso() {
  const { data } = await http.get('/api/auth/staff/config', { anonymous: true, silentAuth: true })
  const callback = new URL(data.callbackUrl)
  if (callback.origin !== window.location.origin) throw new Error('SSO 回呼網址與目前網站不符，請聯絡管理員')
  const state = Array.from(crypto.getRandomValues(new Uint8Array(32)), n => n.toString(16).padStart(2, '0')).join('')
  sessionStorage.setItem(STATE_KEY, JSON.stringify({ state, createdAt: Date.now() }))
  callback.searchParams.set('bpm_sso_state', state)
  const login = new URL(data.loginUrl)
  login.searchParams.set('returnUrl', callback.href)
  window.location.assign(login.href)
}

export async function consumeStaffCallback() {
  const url = new URL(window.location.href)
  if (!url.searchParams.has('code') && !url.searchParams.has('token') && !url.searchParams.has('bpm_sso_state')) return null
  const code = url.searchParams.get('code')
  const state = url.searchParams.get('bpm_sso_state')
  const legacyToken = url.searchParams.has('token')
  // Remove credentials from history before network activity or rendering any business page.
  for (const key of ['code', 'token', 'bpm_sso_state', 'sso']) url.searchParams.delete(key)
  window.history.replaceState(window.history.state || {}, '', url.pathname + url.search + url.hash)
  const stored = sessionStorage.getItem(STATE_KEY)
  sessionStorage.removeItem(STATE_KEY)
  let expected
  try { expected = JSON.parse(stored) } catch { /* rejected below */ }
  if (legacyToken || !code || !state || expected?.state !== state
      || !Number.isFinite(expected?.createdAt) || expected.createdAt > Date.now()
      || Date.now() - expected.createdAt > MAX_AGE) {
    throw new Error('SSO 登入狀態無效或已逾期，請重新登入')
  }
  const { data } = await http.post('/api/auth/staff/token', { code }, { anonymous: true, silentAuth: true })
  if (typeof data?.token !== 'string') throw new Error('SSO 未回傳有效憑證')
  return data.token
}

export function fetchStaffSession() {
  return http.get('/api/me/session', { silentAuth: true }).then(r => r.data)
}
