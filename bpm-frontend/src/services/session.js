/**
 * 單一的身分來源（single source of truth for identity）。
 *
 * auth store、http instance、router guard 三者都從這裡讀身分，
 * 避免各自去碰 localStorage 或 axios.defaults 而產生不一致。
 *
 * ── R-01 之後 ──
 * 後端已啟用 Spring Security，身分來自簽章過的 JWT。token 不再是 userId，
 * decodeToken() 解的是 JWT payload（那正是這個縫線預留的用途）。
 *
 * 角色來源也跟著變：不再有前端的 MOCK_ROLE_MAP。roles 來自 JWT 的 roles
 * claim；claim 沒帶時前端不自行推測 —— 後端會回頭查權限中心，
 * 而前端猜錯只會產生「看得到選項但點下去被擋」的壞體驗。
 * 那種情況下選單少顯示一項，比顯示一個點不動的項目好。
 */
const TOKEN_KEY = 'token'

/**
 * 把 JWT 解析成身分資訊。
 *
 * ⚠️ 只讀 payload，不驗簽章 —— 驗證是後端的事（R-01）。前端解 token 只為了
 * 決定要顯示哪些選單，任何安全決策都不在這裡。所以這裡解析失敗
 * 只會少顯示一些選項，不會放行任何東西。
 *
 * @returns {{userId: string|null, roles: string[], raw: string|null}}
 */
export function decodeToken(token) {
  const empty = { userId: null, roles: [], raw: null }
  if (!token) return empty

  const parts = token.split('.')
  if (parts.length !== 3) {
    // 不是 JWT。R-01 之前 token 的值就是 userId ——
    // 舊的 localStorage 值會落到這裡。視為未登入，讓使用者重新登入拿到 JWT。
    return empty
  }

  try {
    const claims = JSON.parse(decodeBase64Url(parts[1]))
    if (!claims.sub) return empty
    // exp 過期的 token 後端會拒，前端提前視為未登入，
    // 避免使用者看到一個「已登入但每個請求都 401」的畫面。
    if (typeof claims.exp === 'number' && claims.exp * 1000 <= Date.now()) return empty
    return {
      userId: claims.sub,
      roles: Array.isArray(claims.roles) ? claims.roles : [],
      raw: token,
    }
  } catch {
    return empty
  }
}

/** base64url → 字串。atob 不接受 -/_ 也不接受缺少 padding。 */
function decodeBase64Url(value) {
  const b64 = value.replace(/-/g, '+').replace(/_/g, '/')
  const padded = b64 + '='.repeat((4 - (b64.length % 4)) % 4)
  // decodeURIComponent/escape 的組合讓 UTF-8 內容（例如中文名稱）正確還原
  return decodeURIComponent(
    atob(padded)
      .split('')
      .map((c) => '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2))
      .join('')
  )
}

// localStorage 在私密視窗、被封鎖的站台資料等情況下讀寫都可能拋錯，
// 因此一律包 try/catch，並讓失敗等同「未登入」而非讓整個 app 崩掉。
export function getToken() {
  try {
    return localStorage.getItem(TOKEN_KEY)
  } catch {
    return null
  }
}

export function setToken(token) {
  try {
    localStorage.setItem(TOKEN_KEY, token)
  } catch {
    /* 無法持久化時仍可在記憶體中使用本次 session */
  }
}

export function clearToken() {
  try {
    localStorage.removeItem(TOKEN_KEY)
  } catch {
    /* ignore */
  }
}

/** 當前身分（未登入時 userId 為 null、roles 為空陣列）。 */
export function currentIdentity() {
  return decodeToken(getToken())
}

export function hasRole(role) {
  if (!role) return true
  return currentIdentity().roles.includes(role)
}
