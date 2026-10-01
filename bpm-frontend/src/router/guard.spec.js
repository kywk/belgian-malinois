import { describe, it, expect, beforeEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { testJwt } from '../services/testJwt.js'

// router/index.js 會 import 全部的 view（每個 view 又拉 element-plus、
// bpmn-js…），在單元測試裡載入它們既慢又脆弱。因此把 view 全部 stub 掉 ——
// 這組測試要驗的是「守衛的判斷」，不是元件本身。
vi.mock('../views/Dashboard.vue', () => ({ default: {} }))
vi.mock('../views/TaskInbox.vue', () => ({ default: {} }))
vi.mock('../views/DocumentDetail.vue', () => ({ default: {} }))
vi.mock('../views/MyApplications.vue', () => ({ default: {} }))
vi.mock('../views/AuditLog.vue', () => ({ default: {} }))
vi.mock('../views/FormEditor.vue', () => ({ default: {} }))
vi.mock('../views/BpmnEditor.vue', () => ({ default: {} }))
vi.mock('../views/ExternalSystemAdmin.vue', () => ({ default: {} }))
vi.mock('../views/ProcessVariableSpecAdmin.vue', () => ({ default: {} }))
vi.mock('../views/ProcessList.vue', () => ({ default: {} }))
vi.mock('../views/FormList.vue', () => ({ default: {} }))
vi.mock('../views/StartProcess.vue', () => ({ default: {} }))

/**
 * 權限中心的 fixture —— **必須與後端的 MockPermController 一致**。
 *
 * 這不是複製品，而是「後端會這樣回」的樣本。守衛的價值全在於
 * 它對後端回應的反應正確，所以測試要餵真實形狀的資料。
 *
 * admin001 刻意只有 `admin: true` 且 permissions 為空：
 * 權限中心給的是 `["*"]`，後端 AuthorityResolver 把它轉成 ROLE_ADMIN，
 * **不會**展開成 audit:log:read（見 SecurityConfig 的對照表）。
 */
const PERMISSION_FIXTURE = {
  user001: { userId: 'user001', permissions: [], admin: false },
  mgr001: {
    userId: 'mgr001',
    permissions: ['hr:leave:approve', 'finance:payment:approve', 'bpm:form:design'],
    admin: false,
  },
  dir001: {
    userId: 'dir001',
    permissions: ['hr:leave:approve', 'audit:log:read', 'bpm:external:revision'],
    admin: false,
  },
  // 通配持有者：ROLE_ADMIN，沒有任何具名權限碼
  admin001: { userId: 'admin001', permissions: [], admin: true },
}

let backendDown = false

/**
 * 人為延遲權限查詢的回應時間。
 *
 * 真實的 GET /api/me/permissions 要打一次網路（後端還要回頭查權限中心，
 * Redis 快取 5 分鐘），所以權限在第一個畫面之後才到是正常情況。
 * 這裡用一個可控的 deferred promise 重現那個時間差。
 */
let delayMs = 0

vi.mock('../services/permissionsApi.js', () => ({
  fetchMyPermissions: async () => {
    if (delayMs > 0) await new Promise(r => setTimeout(r, delayMs))
    if (backendDown) throw new Error('連線失敗')
    // pinia 的 store 會在 request 當下讀 localStorage，所以這裡取現況
    const token = localStorage.getItem('token')
    const userId = JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')))
      .sub
    const fixture = PERMISSION_FIXTURE[userId]
    if (!fixture) throw new Error(`測試沒有 ${userId} 的權限 fixture`)
    return fixture
  },
}))

const router = (await import('./index.js')).default

/**
 * Router 權限守衛（commit b5fd0b3 / R-03，權限碼部分 #82）。
 *
 * 這是<b>先前只能靠人工點擊驗證</b>的另一半 —— 而那次手動走查一直沒完成。
 * 改動前路由上宣告了 meta.requiresRole，但整個 src/ 沒有任何 beforeEach，
 * 也就是 meta 是死資料、直接輸入 /admin/* 的 URL 就進得去。
 *
 * ── #82 改了什麼 ──
 *
 * 改動前守衛讀 JWT 的 roles claim，於是三種身分的 UI 與後端互相矛盾：
 *   mgr001（有 bpm:form:design）後端放行 POST /api/forms、前端擋掉
 *   dir001（有 audit:log:read）後端放行 /api/audit-logs、前端擋掉
 *   admin001（* → ROLE_ADMIN）看得見所有管理頁、點下去全部 403
 *
 * 現在守衛讀 GET /api/me/permissions，那是後端實際用來授權的 authorities。
 *
 * ⚠️ 這只是 UX 層防線，不是安全邊界 —— 繞過前端直接打 API 時，
 * 每一條規則仍由後端獨立判定（SecurityConfig + ProcessAccessGuard）。
 *
 * ⚠️ 測試的 token **不帶 roles claim**，與 dev 的 mintDevToken() 一致。
 * 帶了 claim 的話這組測試會測到降級路徑而不是主要路徑
 * —— 那是另一組測試的事（見下方「降級路徑」describe）。
 *
 * ── 負向控制組（2026-10-01）──
 *
 * 逐項撤掉修正後重跑，觀察哪些測試轉紅：
 *
 *   撤掉 /audit-log 的 requiresPermission、改回 requiresRole: 'auditor'
 *     → 🔴 6 紅（表單設計可進入 ×2、稽核可進入、auditor 角色不放行、
 *                admin 進管理頁、claim 降級放行表單設計）
 *   把 /audit-log 的 acceptsAdmin 設為 true（admin 萬能化）
 *     → 🔴 1 紅（admin001 看不到稽核入口）
 *   拿掉 await auth.loadPermissions()
 *     → 🔴 1 紅（權限還沒回來時必須等）
 *
 * ⚠️ 第一列的 6 紅最關鍵：它證明「路由改回角色判斷」真的會被這組測試抓到，
 * 而不是測試自己配合著壞掉。後兩列各只有 1 條紅 —— 那正是它們該有的樣子
 * （若紅了 5 條，代表測試之間有隱性耦合）。
 *
 * 對照組（刻意保持綠）：未登入放行、無權限要求的路由放行、
 * /admin/forms 維持角色要求。
 */
describe('router 權限守衛', () => {
  beforeEach(() => {
    localStorage.clear()
    backendDown = false
    delayMs = 0
    // ⚠️ 每個測試都要全新的 pinia：auth store 的 state 是
    // `token: getToken()` 建立時讀一次，而 pinia store 是單例 ——
    // 沿用前一個測試的實例會讓「這個測試登入的是誰」取決於執行順序。
    setActivePinia(createPinia())
  })

  /**
   * 導航到 path 並回傳「最後停在哪裡」。
   *
   * ⚠️ 必須先離開目標路由再 push。vue-router 對「push 到當前路由」會視為
   * 重複導航而直接忽略 —— 守衛根本不會執行。我第一版就踩到這個坑：
   * 前一個測試已經停在 /admin/forms，下一個測試對同一路徑 push 就成了
   * no-op，於是測試「通過」了一個實際上沒被驗證的斷言。
   */
  const guard = async (path) => {
    if (router.currentRoute.value.fullPath !== '/') {
      try { await router.push('/') } catch { /* ignore */ }
    }
    try {
      await router.push(path)
    } catch { /* 導航被中止也算結果 */ }
    return router.currentRoute.value.fullPath
  }

  /**
   * 以某個身分登入。
   *
   * ⚠️ 走 store 的 setToken（App.vue 實際呼叫的那個），不是只寫 localStorage：
   * store 的 state 是 token 建立時讀一次，繞過它去寫 localStorage
   * 會讓 store 與 session.js 分歧 —— 而那正是「身分單一來源」要防的事。
   * 順帶也會 resetPermissions()，換人登入時不會留著上一個人的權限。
   */
  const login = async (userId, claims = {}) => {
    const { useAuthStore } = await import('../stores/auth.js')
    useAuthStore().setToken(testJwt(userId, claims))
  }

  it('未登入時放行（由 App.vue 的登入 overlay 接手）', async () => {
    // 刻意不轉導到 /login —— 本專案沒有 /login 路由。
    expect(await guard('/admin/forms')).toBe('/admin/forms')
  })

  // ── 表單設計：bpm:form:design **或** ROLE_ADMIN ────────────────

  it('持有 bpm:form:design 的身分看得到表單設計入口', async () => {
    // mgr001 是部門主管，不是管理員。後端 POST/PUT/DELETE /api/forms/**
    // 對他放行（bpm:form:design），前端必須一致。
    // 這一條是本工項的原始缺陷：改動前這裡是 '/'。
    await login('mgr001')
    expect(await guard('/admin/form-editor')).toBe('/admin/form-editor')
  })

  it('沒有 bpm:form:design 的身分看不到表單設計入口', async () => {
    await login('user001')
    expect(await guard('/admin/form-editor')).toBe('/')
  })

  it('admin001（* 通配）看得到表單設計入口 —— 後端刻意接受 ROLE_ADMIN', async () => {
    // 後端 /api/forms/** 的寫入是
    //   hasAuthority(FORM_DESIGN) || hasRole(ADMIN)
    // 這條 admin 旁路是刻意保留的：表單 schema 是設定資產，不是個人資料。
    await login('admin001')
    expect(await guard('/admin/form-editor')).toBe('/admin/form-editor')
  })

  it('admin001 進不了表單編輯器以外的管理頁以外的路徑 —— 那些仍是角色要求', async () => {
    // 對照組：acceptsAdmin 只加在那條路由上，不是全域設定。
    await login('mgr001')
    expect(await guard('/admin/processes')).toBe('/')
    expect(await guard('/admin/bpmn-editor')).toBe('/')
  })

  // ── 稽核：只認 audit:log:read，**不**認 ROLE_ADMIN ─────────────

  it('🔴 admin001 看不到稽核入口 —— 後端刻意不給 admin 稽核權', async () => {
    // 這是最容易做錯的一條。後端 /api/audit-logs/** 是
    //   hasAuthority("audit:log:read")
    // 刻意不接受 ROLE_ADMIN：稽核紀錄含全公司薪資與簽核意見，且
    // AuditEvent.detail 會帶整包流程變數 —— 放行就等於繞過
    // ProcessAccessGuard.requireReadAccess 對 ROLE_ADMIN 的明確拒絕。
    //
    // 「簡化成 admin 萬能」在前端看起來像無害的整理，實際上會讓
    // 管理員看到頁面卻吃 403 —— 正是本工項要修的那個症狀。
    await login('admin001')
    expect(await guard('/audit-log')).toBe('/')
  })

  it('持有 audit:log:read 的身分可進入稽核頁面', async () => {
    await login('dir001')
    expect(await guard('/audit-log')).toBe('/audit-log')
  })

  it('沒有 audit:log:read 的一般使用者看不到稽核頁面', async () => {
    await login('user001')
    expect(await guard('/audit-log')).toBe('/')
  })

  it('JWT 帶了 auditor 角色也不等於 audit:log:read', async () => {
    // 這條很容易漏：roles claim 裡的「auditor」是前端自己發明的角色名，
    // 後端從來不檢查它。憑角色放行會讓 dir001 以外宣告 auditor 的人
    // 進得去頁面、然後吃 403。
    await login('user001', { roles: ['auditor'] })
    expect(await guard('/audit-log')).toBe('/')
  })

  // ── 維持角色要求的路由：不得放寬 ────────────────────────────────

  it('🔴 /admin/forms 維持 requiresRole: admin —— 前端比後端嚴是允許的狀態', async () => {
    // 後端 GET /api/forms 只要登入（讀開放是刻意的：業務人員要看到
    // 同事做了哪些表單）。前端比後端嚴只是選單少顯示一項；
    // 放寬是**產品決定**，不該在這個工項裡順手做掉。
    // 這條測試的作用是把「維持原判斷」寫成契約。
    await login('mgr001')
    expect(await guard('/admin/forms')).toBe('/')
  })

  it('admin 可進入角色要求的管理頁面', async () => {
    await login('admin001')
    expect(await guard('/admin/forms')).toBe('/admin/forms')
    expect(await guard('/admin/processes')).toBe('/admin/processes')
    expect(await guard('/admin/external-systems')).toBe('/admin/external-systems')
  })

  it('無權限要求的路由一律放行', async () => {
    await login('user001')
    expect(await guard('/tasks')).toBe('/tasks')
    expect(await guard('/my-applications')).toBe('/my-applications')
    expect(await guard('/start')).toBe('/start')
  })

  // ── 不閃 ──────────────────────────────────────────────────────

  it('🔴 權限還沒回來時必須等，不得先放行再踢走（畫面閃一下）', async () => {
    // 這是 guard 改成 async 的唯一理由。若拿掉 await 讓它先放行，
    // 使用者會看到頁面跳出來、又被彈回首頁 —— 那個閃爍會被讀成
    // 「功能壞了」，而且他無從知道自己在等什麼。
    delayMs = 30
    await login('dir001')

    let settled = false
    const nav = guard('/audit-log').then((r) => { settled = true; return r })

    // 權限還沒到 —— 導航必須尚未結束。
    await new Promise(r => setTimeout(r, 5))
    expect(settled, '權限尚未載入就結束導航 → 畫面會閃一下再被踢走').toBe(false)

    expect(await nav).toBe('/audit-log')
  })

  it('權限還沒回來時不得放行無權限的頁面', async () => {
    // 與上面成組：只驗「會等」不驗「等完之後判斷正確」，
    // 那個等待可能只是把所有人無條件放行。
    delayMs = 30
    await login('user001')

    expect(await guard('/audit-log')).toBe('/')
    expect(await guard('/admin/form-editor')).toBe('/')
  })

  // ── 降級路徑：後端不可用 ──────────────────────────────────────

  it('後端不可用時不卡住：不帶 roles claim 的人看不到權限頁，但一般頁面照用', async () => {
    backendDown = true
    await login('user001')

    // 關鍵：guard 必須**結束**導航。若它在這裡無限等待，
    // 後端故障就會讓整個 app 卡在白畫面 —— 比少顯示選項嚴重得多。
    expect(await guard('/admin/form-editor')).toBe('/')
    expect(await guard('/audit-log')).toBe('/')
    expect(await guard('/tasks')).toBe('/tasks')
  })

  it('後端不可用但 JWT 帶了權限碼時，以 claim 降級放行表單設計（標記未確認）', async () => {
    // 正式環境的 IdP 可能直接以權限碼簽 roles claim
    // （後端 AuthorityResolver 刻意保留原字串）。
    //
    // ⚠️ 這個降級只用在「具名權限碼」，而不用在 acceptsAdmin ——
    // 稽核與表單設計的 admin 旁路都建立在後端算出的 admin 旗標上，
    // 前端推測自己是管理員等於自己發明權限。
    backendDown = true
    await login('idp-001', { roles: ['bpm:form:design'] })
    expect(await guard('/admin/form-editor')).toBe('/admin/form-editor')

    // 只有角色名、沒有具名權限碼 → 稽核進不去（ROLE_ADMIN 不等於
    // audit:log:read，見 SecurityConfig 的對照表）。
    await login('idp-002', { roles: ['admin'] })
    expect(await guard('/audit-log')).toBe('/')
  })

  it('後端不可用時，admin 角色的管理頁沿用改動前的判斷', async () => {
    // ⚠️ 刻意維持**改動前**的行為，不是新增的寬容：改動前 isAdmin 就是
    // roles.includes('admin')。降級時突然改掉角色判斷會讓情況更難排查
    // —— 使用者會看到「剛才還能進的頁面現在進不去」。
    // 而且放行的後果沒有變化：寫入時後端照樣用 authorities 擋。
    backendDown = true
    await login('idp-002', { roles: ['admin'] })
    expect(await guard('/admin/forms')).toBe('/admin/forms')
  })
})