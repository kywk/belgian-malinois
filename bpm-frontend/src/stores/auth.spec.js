import { describe, it, expect, beforeEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from './auth.js'
import { testJwt } from '../services/testJwt.js'

/**
 * 權限碼狀態（stores/auth.js）—— #82。
 *
 * 這組測試守的是「前端怎麼用後端回報的權限」，而不是權限本身對不對
 * （那是後端 {@code MePermissionsEndpointTest} 的事）。
 *
 * ⚠️ **這份清單是 UX 層防線，不是安全邊界。** 每個 API 的授權都由後端
 * 獨立判定；這裡的 getter 只決定畫面要不要顯示入口。
 *
 * ── 負向控制組（2026-10-01）──
 *
 * 把 {@code isAuditor} 改成 {@code state.admin || permissions.includes(...)}
 * （也就是最常見的「admin 萬能」簡化）後重跑：
 * <ul>
 *   <li>🔴 紅：{@code isAuditor / canDesignForms › 🔴 admin001 不是稽核人員}</li>
 *   <li>🔴 紅：{@code App.spec.js › 🔴 admin001 ... 看不到稽核 Log}</li>
 *   <li>⚠️ <b>router guard 的測試一條都沒紅</b> —— guard 走路由 meta，
 *       不走 isAuditor。也就是說「admin 萬能」可以只發生在選單層，
 *       而使用者看到的正是選單。這是為什麼 store 與元件層都要有測試。</li>
 * </ul>
 */
const FIXTURE = {
  // 有權限碼、不是管理員：業務人員 mgr001
  mgr001: {
    userId: 'mgr001',
    permissions: ['hr:leave:approve', 'bpm:form:design'],
    admin: false,
  },
  // 通配持有者：ROLE_ADMIN，沒有具名權限碼（後端刻意不展開 *）
  admin001: { userId: 'admin001', permissions: [], admin: true },
  // 稽核職能
  dir001: { userId: 'dir001', permissions: ['audit:log:read'], admin: false },
  user001: { userId: 'user001', permissions: [], admin: false },
}

let backendDown = false

vi.mock('../services/permissionsApi.js', () => ({
  fetchMyPermissions: async () => {
    if (backendDown) throw new Error('連線失敗')
    const token = localStorage.getItem('token')
    const sub = JSON.parse(
      atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/'))
    ).sub
    const f = FIXTURE[sub]
    if (!f) throw new Error(`測試沒有 ${sub} 的 fixture`)
    return f
  },
}))

describe('auth store 的權限狀態', () => {
  let auth

  beforeEach(() => {
    localStorage.clear()
    backendDown = false
    setActivePinia(createPinia())
    auth = useAuthStore()
  })

  // ── 載入 ──────────────────────────────────────────────────────

  describe('loadPermissions', () => {
    it('登入後載入後端回報的權限', async () => {
      auth.setToken(testJwt('mgr001'))
      await auth.loadPermissions()

      expect(auth.permissions).toEqual(['hr:leave:approve', 'bpm:form:design'])
      expect(auth.permissionsUserId).toBe('mgr001')
      expect(auth.permissionsUnverified).toBe(false)
      expect(auth.canDesignForms).toBe(true)
    })

    it('並行呼叫只發一個請求', async () => {
      const api = await import('../services/permissionsApi.js')
      const spy = vi.spyOn(api, 'fetchMyPermissions')

      auth.setToken(testJwt('mgr001'))
      await Promise.all([auth.loadPermissions(), auth.loadPermissions()])
      await auth.loadPermissions()

      // setToken 觸發一次、guard 又會 await 一次 —— 若沒有去重「進行中」
      // 的載入，兩個呼叫都會在第一次 await 前看到 permissionsLoaded=false，
      // 於是發兩個請求（後端每次都要回頭查權限中心）。
      expect(spy).toHaveBeenCalledTimes(1)
    })

    it('後端回應欄位缺漏時不得讓前端崩掉', async () => {
      const api = await import('../services/permissionsApi.js')
      vi.spyOn(api, 'fetchMyPermissions').mockResolvedValue(undefined)

      auth.setToken(testJwt('mgr001'))
      await auth.loadPermissions()

      expect(auth.permissions).toEqual([])
      expect(auth.admin).toBe(false)
      expect(
        auth.permissionsLoaded,
        '欄位缺漏必須視為「沒有權限」，不是讓 guard 永遠等待'
      ).toBe(true)
    })

    it('permissions 不是陣列時降級成空陣列', async () => {
      const api = await import('../services/permissionsApi.js')
      vi.spyOn(api, 'fetchMyPermissions').mockResolvedValue({ permissions: 'oops' })

      auth.setToken(testJwt('mgr001'))
      await auth.loadPermissions()

      expect(auth.permissions).toEqual([])
    })
  })

  // ── 切換身分 ──────────────────────────────────────────────────

  describe('換人登入', () => {
    it('不得留著上一個人的權限', async () => {
      auth.setToken(testJwt('admin001'))
      await auth.loadPermissions()
      expect(auth.isAdmin).toBe(true)

      // 換成沒有任何權限的人 —— 若沿用舊狀態，他會看得到管理選單。
      auth.setToken(testJwt('user001'))
      await auth.loadPermissions()

      expect(
        auth.isAdmin,
        "admin001 的權限留給 user001 —— 這是「看到選項、點下去被擋」"
      ).toBe(false)
      expect(auth.canDesignForms).toBe(false)
    })

    it('logout 必須清掉權限', async () => {
      auth.setToken(testJwt('admin001'))
      await auth.loadPermissions()

      auth.logout()

      expect(auth.permissions).toEqual([])
      expect(auth.admin).toBe(false)
      expect(
        auth.permissionsLoaded,
        '登出後若仍是 true，下一個登入者不會重新載入 —— 會帶著舊權限'
      ).toBe(false)
    })
  })

  // ── getter 與後端規則對齊 ─────────────────────────────────────

  describe('isAuditor / canDesignForms 必須複製後端的差異', () => {
    it('🔴 admin001 不是稽核人員', async () => {
      auth.setToken(testJwt('admin001'))
      await auth.loadPermissions()

      expect(auth.admin).toBe(true)
      expect(
        auth.canDesignForms,
        '後端 /api/forms/** 的寫入接受 ROLE_ADMIN'
      ).toBe(true)
      expect(
        auth.isAuditor,
        '🔴 後端 /api/audit-logs/** 刻意不接受 ROLE_ADMIN —— '
          + '「admin 萬能」是最容易犯的簡化，會產生看得到頁面卻吃 403'
      ).toBe(false)
    })

    it('dir001 是稽核人員但不是管理員', async () => {
      auth.setToken(testJwt('dir001'))
      await auth.loadPermissions()

      expect(auth.isAuditor).toBe(true)
      expect(auth.canDesignForms).toBe(false)
      expect(auth.isAdmin).toBe(false)
    })

    it('mgr001 能設計表單但不是管理員', async () => {
      auth.setToken(testJwt('mgr001'))
      await auth.loadPermissions()

      expect(auth.canDesignForms).toBe(true)
      expect(auth.isAdmin).toBe(false)
    })
  })

  describe('hasPermission', () => {
    it('只回答「這個權限碼在不在清單裡」', async () => {
      // ⚠️ 這裡刻意**沒有** acceptsAdmin 參數。
      //
      // 改動前有一個 `opts.acceptsAdmin`，而它是個危險的開關：傳錯
      // true，稽核入口就對管理員開放了，而後端刻意不接受。那種錯誤
      // 型別合法、畫面看不出來，只有回頭讀路由宣告才會發現。
      //
      // 所以「這條規則接不接受 ROLE_ADMIN」只表達在路由 meta 上
      // （router/index.js），那是後端 SecurityConfig 的鏡像，
      // 旁邊就寫著理由。store 這層只回答清單有沒有那個碼。
      auth.setToken(testJwt('admin001'))
      await auth.loadPermissions()

      expect(auth.hasPermission('audit:log:read'))
        .toBe(false)
      expect(auth.hasPermission('bpm:form:design'))
        .toBe(false)
      expect(auth.isAdmin).toBe(true)
    })

    it('有權限碼時為 true，admin001 的空清單不會讓它變成 false 的反面', async () => {
      auth.setToken(testJwt('dir001'))
      await auth.loadPermissions()

      expect(auth.hasPermission('audit:log:read')).toBe(true)
      expect(auth.hasPermission('bpm:form:design')).toBe(false)
    })

    it('未指定權限碼時視為不需要權限', () => {
      expect(auth.hasPermission(null)).toBe(true)
      expect(auth.hasPermission('')).toBe(true)
    })
  })

  // ── 降級路徑 ──────────────────────────────────────────────────

  describe('後端不可用時的降級', () => {
    it('降級到 roles claim 裡形狀像權限碼的項目，並標記未確認', async () => {
      backendDown = true
      auth.setToken(testJwt('idp-001', { roles: ['bpm:form:design', 'auditor'] }))
      await auth.loadPermissions()

      expect(
        auth.permissions,
        '只撈形狀像權限碼的 —— 「auditor」是前端自己發明的角色名'
      ).toEqual(['bpm:form:design'])
      expect(auth.permissionsUnverified).toBe(true)
    })

    it('🔴 降級時不把 * 展開成管理權限', async () => {
      backendDown = true
      auth.setToken(testJwt('idp-002', { roles: ['*'] }))
      await auth.loadPermissions()

      // 「*」在後端是 → ROLE_ADMIN。前端不展開成全部權限碼 ——
      // 那會讓他看到稽核入口，而後端一律 403。
      expect(auth.permissions).toEqual([])
      expect(auth.isAdmin).toBe(false)
      expect(auth.permissionsUnverified).toBe(true)
    })

    it('permissionsLoaded 必須為 true，否則 guard 會永遠等待', async () => {
      backendDown = true
      auth.setToken(testJwt('idp-003'))
      await auth.loadPermissions()

      expect(
        auth.permissionsLoaded,
        '後端故障時若不標記，router guard 會無限 await，整個 app 卡住'
      ).toBe(true)
    })
  })
})