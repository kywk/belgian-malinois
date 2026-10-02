import { defineStore } from 'pinia'
import {
  clearToken,
  decodeToken,
  getToken,
  permissionCodesFromRoles,
  setToken as persistToken,
} from '../services/session'
import { fetchMyPermissions } from '../services/permissionsApi'

/**
 * 身分狀態。
 *
 * 改動前這個 store 直接操作 axios.defaults.headers，導致「誰負責附上身分」
 * 散落在 store 與 App.vue 兩處。現在 header 一律由 services/http.js 的
 * request interceptor 統一附上，store 只負責狀態。
 *
 * 角色判斷一律走 roles（來自 services/session.js 的 decodeToken），
 * 不要再用 token.startsWith('admin') 這種字串前綴判斷。
 *
 * ── #82：權限碼狀態 ──
 *
 * 改動前只有 token 與從它推導的 getter，所以 router guard 只能用
 * `requiresRole` 表達「這個頁面要什麼權限」。但後端授權寫在權限碼上
 * （bpm:form:design、audit:log:read），而 JWT 的 roles claim 在 dev
 * 一律是空的（mintDevToken 刻意不簽），於是：
 *   mgr001（有 bpm:form:design）後端放行 POST /api/forms、前端擋掉
 *   dir001（有 audit:log:read）後端放行 /api/audit-logs、前端擋掉
 *   admin001（* → ROLE_ADMIN）後端放行管理頁與表單設計、前端擋掉
 *   （稽核是另一回事：後端刻意不給 admin，見下面 isAuditor 的說明）
 *
 * 現在權限碼來自 GET /api/me/permissions，那是後端實際用來授權的
 * authorities —— 前端看到的就是後端會做的判斷。
 *
 * ⚠️ **這是 UX 層防線，不是安全邊界。** 與 router guard 同級。
 * 前端不得因為「我有這個權限碼所以可以」而去存取任何資料 ——
 * 每個 API 的授權都由後端獨立判定。
 */
export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: getToken(),

    /**
     * 呼叫者實際被授權的權限碼。
     *
     * ⚠️ 空陣列有兩種意義，無法區分 —— 所以才有 {@link state.permissionsLoaded}：
     * 「還沒載入」與「載入了，確實沒有」在決策上是不同的
     * （見 router guard 的 await 說明）。
     */
    permissions: [],

    /**
     * 是否已嘗試過向後端要權限。
     *
     * 為什麼需要它：router guard 必須**等**權限載入，否則會先顯示
     * 畫面再把它換掉（閃一下）。但等待要有上限 —— 後端掛掉時不能讓
     * 整個 app 卡在載入中。所以這裡記的是「已經問過了」，
     * 不是「問成功了」：失敗也會讓 guard 停止等待。
     *
     * 三態：
     *   false → 還沒問過，guard 應等待
     *   true  → 問過了（成功或失敗），guard 可以直接判斷
     */
    permissionsLoaded: false,

    /**
     * 是否持有 ROLE_ADMIN（由權限中心的 `*` 轉換而來）。
     *
     * ⚠️ **不是「什麼都能做」。** 它只對應後端 hasRole(ADMIN) 的那幾條
     * 規則。/api/audit-logs/** 刻意不接受它，所以 admin001 會拿到
     * admin=true 卻看不到稽核入口 —— 那正是後端的政策，前端照抄。
     */
    admin: false,

    /** 後端回報的 userId。用來確認這份權限是「目前這個人的」。 */
    permissionsUserId: null,

    /**
     * 這份權限碼是後端確認的，還是從 roles claim 降級來的。
     *
     * 降級時為 true —— router guard 在這種情況下會**更嚴格**
     * （見 router/index.js），因為未確認的碼可能已被撤銷。
     */
    permissionsUnverified: false,

    /**
     * 進行中的載入 promise。
     *
     * 只用於去重並行呼叫，不參與任何判斷（所以它在 state 裡是安全的 ——
     * 改變它不會改變畫面）。理由見 {@link actions.loadPermissions}。
     */
    permissionsInFlight: null,
  }),
  getters: {
    isAuthenticated: (state) => !!state.token,
    userId: (state) => decodeToken(state.token).userId,
    roles: (state) => decodeToken(state.token).roles,
    isAdmin: (state) => state.admin || decodeToken(state.token).roles.includes('admin'),

    /**
     * 是否可檢視稽核。
     *
     * ⚠️ **只看 audit:log:read，不看 isAdmin。**
     *
     * 後端 `/api/audit-logs/**` 刻意不接受 ROLE_ADMIN：稽核紀錄含
     * 全公司薪資、簽核意見與核決金額，而 ProcessAccessGuard.requireReadAccess
     * 早就明確拒絕 ROLE_ADMIN 讀案件流程變數。若這裡放行管理員，
     * 「不能讀別人案件變數」就會被「可以翻全公司稽核紀錄」從側門繞過
     * （AuditEvent.detail 會帶整包 variables）。
     *
     * 改成「admin 萬能」是最容易犯的錯：它在前端看起來像一個無害的
     * 簡化，實際上會產生「看得到頁面、點下去 403」的新症狀 ——
     * 也就是這個工項原本要修的那個問題。
     */
    isAuditor: (state) => state.permissions.includes('audit:log:read'),

    /** 是否可設計表單。後端接受 bpm:form:design **或** ROLE_ADMIN。 */
    canDesignForms: (state) => state.admin || state.permissions.includes('bpm:form:design'),
  },
  actions: {
    /**
     * 某個權限碼是否可用。
     *
     * ⚠️ **這不是安全檢查。** 它只決定畫面要不要顯示入口。
     * 真正的授權在後端 —— 前端有權限碼也不代表 API 會放行。
     *
     * ⚠️ **刻意不接受「順便也讓 admin 過」的參數。**
     *
     * 改動前這裡有 `opts.acceptsAdmin`，而它是一個很容易誤用的開關：
     * 傳錯一個 true，稽核入口就對管理員開放了 —— 而後端刻意不接受
     * （SecurityConfig 的對照表）。那種錯誤在型別上完全合法、
     * 在畫面上看不出來、只有回頭讀路由宣告才會發現。
     *
     * 所以「這條規則接不接受 ROLE_ADMIN」只表達在<b>路由的 meta</b>
     * （router/index.js），那是後端 SecurityConfig 的鏡像，
     * 而且旁邊就寫著理由。store 這一層只回答「這個權限碼在不在清單裡」。
     *
     * @param {string} code 權限碼，例如 'bpm:form:design'
     */
    hasPermission(code) {
      if (!code) return true
      return this.permissions.includes(code)
    },

    /**
     * 登入。
     *
     * ⚠️ **權限的載入刻意放在這裡 fire-and-forget，不 await。**
     *
     * 登入之後不一定會有導航（App.vue 的 overlay 直接消失、使用者停在
     * 同一頁），所以不能指望 router guard 來觸發第一次載入 —— 否則
     * 選單會永遠停在「沒有權限」的狀態。
     *
     * 不 await 是因為 App.vue 的呼叫端不做 await（登入不該被權限查詢
     * 拖慢）；這不影響正確性：router guard 會 await 同一個
     * {@link state.permissionsLoaded}，而選單是 reactive 的 ——
     * 載入完成後畫面自己會更新。
     *
     * 呼叫端不需要處理失敗：loadPermissions() 內部已經降級，
     * 這裡的 catch 只是避免 unhandled rejection。
     */
    setToken(token) {
      this.token = token
      persistToken(token)
      // 換人登入時必須重置：留著上一個人的權限會讓新使用者看得到
      // 不屬於他的選單 —— 而且那正是「看到選項、點下去被擋」的症狀。
      this.resetPermissions()
      this.loadPermissions().catch(() => {
        // loadPermissions 內部已降級，不會 reject。這裡只是不讓
        // 未處理的 promise rejection 讓 dev console 變雜訊。
      })
    },

    /**
     * 載入呼叫者自己的權限。登入後呼叫一次。
     *
     * ⚠️ **後端不可用時不讓整個 app 崩掉**：降級成從 roles claim 撈
     * 權限碼，並標記 {@link state.permissionsUnverified}。
     * 「查不到權限」時把使用者的畫面清空，比登不進來更糟。
     *
     * @returns {Promise<void>}
     */
    async loadPermissions() {
      if (this.permissionsLoaded) return
      // ⚠️ 必須去重「進行中」的載入，而不只是「已完成」的。
      //
      // setToken() 會 fire-and-forget 觸發一次，router guard 又會 await
      // 一次 —— 只有 permissionsLoaded 這道防線的話，兩個呼叫都會在
      // 第一次 await 之前看到 false，於是**發兩個請求**。後端每次都要
      // 回頭查權限中心（Redis 只快取 5 分鐘），這是不必要的負載，
      // 而且兩次回應的先後順序可能讓狀態被舊的答案蓋掉。
      if (this.permissionsInFlight) return this.permissionsInFlight

      const { roles } = decodeToken(this.token)
      this.permissionsInFlight = (async () => {
        try {
          const data = await fetchMyPermissions()
          this.permissions = Array.isArray(data?.permissions) ? data.permissions : []
          this.admin = data?.admin === true
          this.permissionsUserId = data?.userId ?? null
          this.permissionsUnverified = false
        } catch {
          // 降級：形狀像權限碼的 claim 項目。`*` 不展開 ——
          // 它在 roles claim 裡的處理是後端的政策（`*` → ROLE_ADMIN），
          // 前端展開它就是在猜（見 permissionsApi.js 的 ⚠️）。
          // admin 沿用改動前的 roles.includes('admin')，不因降級改變。
          this.permissions = permissionCodesFromRoles(roles)
          this.admin = roles.includes('admin')
          this.permissionsUserId = null
          this.permissionsUnverified = true
        } finally {
          // 無論成功失敗都要標記已問過 —— 否則後端故障時 router guard
          // 會無限等待，整個 app 卡在載入中。
          this.permissionsLoaded = true
          this.permissionsInFlight = null
        }
      })()
      return this.permissionsInFlight
    },

    resetPermissions() {
      this.permissions = []
      this.permissionsLoaded = false
      this.admin = false
      this.permissionsUserId = null
      this.permissionsUnverified = false
      this.permissionsInFlight = null
    },

    logout() {
      this.token = null
      clearToken()
      this.resetPermissions()
    },
  },
})