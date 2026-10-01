/**
 * 呼叫者自己的權限（backlog #82）。
 *
 * 資料來自後端 {@code GET /api/me/permissions}。那個端點只回呼叫者自己的
 * 權限碼，沒有任何「要看誰」的參數 —— 後端的理由寫在 MeController 的
 * 類別註解（加參數等於開一條組織結構的枚舉通道）。
 *
 * ── 為什麼前端需要這個 ──
 * 後端授權寫在權限碼上（{@code bpm:form:design}、{@code audit:log:read}），
 * 而 session.js 的 decodeToken() 只讀 JWT 的 roles claim。於是：
 *   mgr001（有 bpm:form:design）後端放行、前端擋掉
 *   dir001（有 audit:log:read）後端放行、前端擋掉
 *   admin001（* → ROLE_ADMIN）前端看得見所有管理頁、點下去全部 403
 * session.js 的檔案註解原本就寫著「前端猜錯時選單少顯示一項比顯示一個
 * 點不動的項目好」—— 那是沒有資料來源時的降級策略。有了這個端點，
 * 前端不必再猜。
 *
 * ⚠️ **這份清單是 UX 層防線，不是安全邊界。** 它與既有的 router guard
 * 同級：都是「避免使用者誤入沒有權限的畫面」。真正的授權全在後端，
 * 而且前端<b>不得</b>因為「我有這個權限碼所以可以」而去存取資料 ——
 * 每個 API 的授權都由後端獨立判定，前端這邊的判斷不影響它。
 */
import http from './http'

/**
 * 讀取目前登入者的權限。
 *
 * ⚠️ **不做任何前端端的「補完」或「推論」**：後端回什麼就是什麼。
 * 特別是不把 {@code *} 展開成全部權限碼 —— 那是後端
 * {@code AuthorityResolver} 的政策（`*` → ROLE_ADMIN，而 ROLE_ADMIN
 * 刻意不等於 audit:log:read）。前端若自己展開，就會讓 admin001 看到
 * 稽核入口，而後端一律 403。
 *
 * @returns {Promise<{userId: string, permissions: string[], admin: boolean}>}
 */
export function fetchMyPermissions() {
  return http.get('/api/me/permissions').then(r => r.data)
}