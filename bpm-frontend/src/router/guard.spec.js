import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setToken } from '../services/session.js'

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

const router = (await import('./index.js')).default

/**
 * Router 角色守衛（commit b5fd0b3 / R-03）。
 *
 * 這是<b>先前只能靠人工點擊驗證</b>的另一半 —— 而那次手動走查一直沒完成。
 * 改動前路由上宣告了 meta.requiresRole，但整個 src/ 沒有任何 beforeEach，
 * 也就是 meta 是死資料、直接輸入 /admin/* 的 URL 就進得去。
 *
 * ⚠️ 這只是 UX 層防線，不是安全邊界 —— 後端 /api/** 目前仍全開放
 * （R-01 / R-18）。這組測試驗的是「使用者不會誤入沒有權限的畫面」，
 * 不是「攻擊者進不去」。
 */
describe('router 角色守衛', () => {
  beforeEach(() => localStorage.clear())

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

  it('未登入時放行（由 App.vue 的登入 overlay 接手）', async () => {
    // 刻意不轉導到 /login —— 本專案沒有 /login 路由。
    expect(await guard('/admin/forms')).toBe('/admin/forms')
  })

  it('已登入但角色不足時擋回首頁', async () => {
    setToken('user001')
    expect(await guard('/admin/forms')).toBe('/')
    expect(await guard('/admin/processes')).toBe('/')
    expect(await guard('/admin/external-systems')).toBe('/')
  })

  it('admin 可進入管理頁面', async () => {
    setToken('admin001')
    expect(await guard('/admin/forms')).toBe('/admin/forms')
    expect(await guard('/admin/processes')).toBe('/admin/processes')
  })

  it('稽核頁面需要 auditor —— 一般使用者被擋（2026-09-28 政策收斂）', async () => {
    setToken('user001')
    expect(await guard('/audit-log')).toBe('/')
  })

  it('dir001 具 auditor，可進入稽核頁面', async () => {
    setToken('dir001')
    expect(await guard('/audit-log')).toBe('/audit-log')
  })

  it('dir001 沒有 admin，不得進入管理頁面', async () => {
    setToken('dir001')
    expect(await guard('/admin/forms')).toBe('/')
  })

  it('無角色要求的路由一律放行', async () => {
    setToken('user001')
    expect(await guard('/tasks')).toBe('/tasks')
    expect(await guard('/my-applications')).toBe('/my-applications')
    expect(await guard('/start')).toBe('/start')
  })
})
