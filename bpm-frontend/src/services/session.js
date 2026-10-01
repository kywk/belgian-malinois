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
 *
 * ── #82 之後：這段「不推測」的態度有了資料來源 ──
 * 上面那段取捨是在<b>前端沒有權限資料</b>的前提下寫的：唯一能讀的
 * JWT roles claim 只有在 IdP 願意簽權限碼時才有用，而 dev 的
 * mintDevToken() 刻意不簽（見 devToken.js），於是前端一律什麼都不知道。
 *
 * 現在 {@code GET /api/me/permissions}（services/permissionsApi.js）
 * 回傳呼叫者實際被授權的權限碼，後端零外部相依 —— 資料本來就在手上。
 * 所以「不推測」不再是無奈的讓步，而是<b>正確的預設值</b>：
 * 有權限資料就用它，沒有（後端不可用）才退回 roles claim 裡
 * <b>形狀像權限碼</b>的那些字串 —— 並且標記為未確認。
 *
 * ⚠️ 這份清單是 UX 層防線，不是安全邊界（見 permissionsApi.js）。
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

/**
 * 角色判斷（讀 JWT 的 roles claim）。
 *
 * ⚠️ **這是權限碼體系落地前的退路，不是權限判斷。** 真正的權限判斷
 * 走 {@code /api/me/permissions}（見 permissionsApi.js）與 auth store 的
 * {@code hasPermission}。
 *
 * 保留這個函式的理由是它仍然如實回報「JWT 帶了什麼角色」，而那是
 * session.js 唯一能知道的東西 —— roles claim 沒有帶就是空陣列，
 * 前端不推測（見檔案註解）。
 */
export function hasRole(role) {
  if (!role) return true
  return currentIdentity().roles.includes(role)
}

/**
 * 權限碼形狀：至少一段冒號，且每段皆為小寫字母、數字、底線或連字號。
 *
 * <p>⚠️ 這是<b>形狀</b>判斷，不是<b>授權</b>判斷。它只用來回答
 * 「這個字串看起來像不像權限碼」，讓 {@link permissionCodesFromRoles}
 * 能把 roles claim 裡的權限碼撈出來。
 *
 * <p>為什麼要判形狀：正式環境的 IdP 可能直接以權限碼簽 roles claim
 * （後端 {@code AuthorityResolver.fromJwtRoles} 刻意保留原字串，
 * 讓 {@code hasAuthority("bpm:form:design")} 也能命中）。
 * 那時前端可以省下那一次請求。
 *
 * <p>為什麼不能用「看起來像」當授權依據：這是前端猜的，形狀判斷擋不住
 * 一個權限中心已撤銷但仍留在 token 裡的碼。真正一致的答案來自後端 ——
 * 所以這條路徑只在拿不到後端回應時作為降級（見 stores/auth.js）。
 */
const PERMISSION_CODE = /^[a-z0-9_]+(:[a-z0-9_-]+)+$/

/**
 * 從 roles claim 裡撈出權限碼。
 *
 * <p>⚠️ **這不是授權判斷**，理由見 {@link PERMISSION_CODE}。
 * 而且回傳的結果一律被視為「未經確認」—— 呼叫端必須知道它是猜的。
 *
 * @returns {string[]}
 */
export function permissionCodesFromRoles(roles) {
  if (!Array.isArray(roles)) return []
  return roles.filter(r => typeof r === 'string' && PERMISSION_CODE.test(r))
}
