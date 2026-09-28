/**
 * 單一的身分來源（single source of truth for identity）。
 *
 * auth store、http instance、router guard 三者都從這裡讀身分，
 * 避免各自去碰 localStorage 或 axios.defaults 而產生不一致。
 *
 * ⚠️ 目前是 mock 身分：token 的值就是 userId（例如 "admin001"），
 * 後端也沒有任何驗證（見 CLAUDE.md 已知技術債 #1）。
 * 真正的 JWT 接上來時，唯一需要改的是 decodeToken()。
 */

const TOKEN_KEY = 'token'

/**
 * Mock 角色對照表。
 *
 * 這張表是刻意集中在一處的「政策決策點」，不要把角色判斷散落到元件裡。
 *
 * - admin：維持與改動前相同的行為（原本是 token.startsWith('admin')），
 *   因此只有 admin001 具備，不會讓任何人多拿到管理權限。
 * - auditor：**暫時授予所有登入者**。原因是改動前 /audit-log 對所有人開放
 *   （App.vue 的導覽列對所有人顯示「稽核 Log」），若在此處直接收緊，
 *   會在沒有任何政策決策的情況下把功能從現有使用者手上拿走。
 *   稽核檢視權限該給誰，屬於待決定的業務政策 —— 等真實權限中心接上後，
 *   roles 會直接來自 JWT claim，這張表即可刪除。
 */
const MOCK_ROLE_MAP = {
  admin001: ['admin', 'auditor'],
}
const MOCK_DEFAULT_ROLES = ['auditor']

/**
 * 把 token 解析成身分資訊。
 *
 * ── 這就是接上真實 JWT 的唯一縫線 ──
 * 屆時改為解析 JWT payload，例如：
 *   const claims = JSON.parse(atob(token.split('.')[1]))
 *   return { userId: claims.sub, roles: claims.roles ?? [], raw: token }
 * 呼叫端（auth store / http / router guard）都不需要改。
 *
 * @returns {{userId: string|null, roles: string[], raw: string|null}}
 */
export function decodeToken(token) {
  if (!token) return { userId: null, roles: [], raw: null }
  return {
    userId: token,
    roles: MOCK_ROLE_MAP[token] ?? MOCK_DEFAULT_ROLES,
    raw: token,
  }
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
