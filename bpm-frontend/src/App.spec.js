import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus from 'element-plus'
import { testJwt } from './services/testJwt.js'

/**
 * #82：主選單的權限顯示。
 *
 * 這是使用者實際看到的畫面 —— 工單 #82 的描述是
 * 「`bpm:form:design` 目前在 UI 上完全看不到效果」，所以只測 router guard
 * 不足以回答那個問題：<b>守衛放行了但選單看不到，功能仍然不存在</b>。
 *
 * ⚠️ 這組測試防的是兩個方向：
 *   1. 該顯示的沒顯示（原始缺陷：mgr001 進不去表單設計）
 *   2. 不該顯示的顯示了（「admin 萬能」：admin001 看到稽核 Log）
 * 只測第 1 方向的話，第 2 方向的錯誤完全不會被發現。
 *
 * ── 負向控制組（2026-10-01）──
 *
 * <table border="1">
 *   <caption>7 條測試 × 2 種缺陷</caption>
 *   <tr><th>撤掉的修正</th><th>結果</th><th>轉紅的測試</th></tr>
 *   <tr><td>表單編輯器移回管理子選單內（恢復改動前的位置）</td>
 *       <td>🔴 3 紅</td>
 *       <td>mgr001 看不到入口、admin001 看不到入口、登出後仍顯示</td></tr>
 *   <tr><td>{@code isAuditor} 改成 admin 萬能化</td>
 *       <td>🔴 2 紅</td>
 *       <td>admin001 看到稽核 Log、store 的 admin001 不是稽核人員</td></tr>
 * </table>
 *
 * ⚠️ 第一列的 3 紅證明一件事：<b>光修 router guard 不夠。</b>
 * 守衛放行了、路由進得去，但選單上看不到入口 —— 功能在使用者眼裡
 * 仍然不存在，而所有 router 層的測試都會是綠的。這就是為什麼需要
 * 這組元件測試。
 *
 * ⚠️ 第二列的 2 紅是同一件事的另外兩半：它們紅在 store 與元件，
 * <b>router guard 的測試完全沒紅</b> —— 因為 guard 走路由 meta，
 * 不走 isAuditor。也就是說「admin 萬能」這個錯誤可以只在選單層發生。
 * 兩層都測是必要的。
 */

vi.mock('./services/permissionsApi.js', () => ({
  fetchMyPermissions: async () => {
    const token = localStorage.getItem('token')
    const sub = JSON.parse(
      atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/'))
    ).sub
    return {
      mgr001: {
        userId: 'mgr001',
        permissions: ['hr:leave:approve', 'bpm:form:design'],
        admin: false,
      },
      admin001: { userId: 'admin001', permissions: [], admin: true },
      dir001: { userId: 'dir001', permissions: ['audit:log:read'], admin: false },
      user001: { userId: 'user001', permissions: [], admin: false },
    }[sub] ?? { userId: sub, permissions: [], admin: false }
  },
}))

vi.mock('./router', () => ({
  default: { push: vi.fn(), replace: vi.fn() },
}))

// App.vue 透過 <router-view> 與 useRouter() 使用 router；測試只關心選單，
// 所以把 router-view stub 掉，省掉載入全部 view 的成本。
const App = (await import('./App.vue')).default
const { useAuthStore } = await import('./stores/auth.js')

// 只 stub router-view。el-menu / el-sub-menu **不能** stub：
// el-menu-item 透過 provide/inject 找到 root menu，stub 掉父層會讓
// 每個 el-menu-item 拋 "can not inject root menu"（我第一版就這樣炸掉）。
// 註冊 ElementPlus 後它們在 jsdom 下可以正常 render。
const GlobalStubs = {
  'router-view': true,
}

async function mountAs(userId, claims = {}) {
  setActivePinia(createPinia())
  useAuthStore().setToken(testJwt(userId, claims))
  const wrapper = mount(App, {
    global: { plugins: [ElementPlus], stubs: GlobalStubs },
  })
  // 等權限載入（setToken 是 fire-and-forget，見 stores/auth.js）
  await useAuthStore().loadPermissions()
  await wrapper.vm.$nextTick()
  return wrapper
}

const menuText = (wrapper) => wrapper.text()

/**
 * 管理子選單的數量。
 *
 * ⚠️ 刻意不斷言「表單管理」等子選單項目的**文字**：el-menu 在
 * horizontal 模式下把子選單內容收進彈出層，未展開時不在 text() 裡。
 * 那樣的斷言會因為「文字不存在」而變綠 —— 而它無法區分
 * 「項目沒有 render」與「項目在別的層」。要斷言就斷言元件數量。
 */
const adminSubMenus = (wrapper) =>
  wrapper.findAllComponents({ name: 'ElSubMenu' }).length

describe('App 主選單的權限顯示', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('持有 bpm:form:design 的業務人員看得到表單編輯器入口', async () => {
    const w = await mountAs('mgr001')
    expect(menuText(w), 'mgr001 是部門主管、持有權限碼，後端也放行他設計表單')
      .toContain('表單編輯器')
    // 用元件計數而非文字：管理子選單的內容在 horizontal 模式下收進彈出層，
    // 不會出現在 text() 裡 —— 斷言文字會得到一個理由錯誤的綠燈。
    expect(adminSubMenus(w), '他不是管理員，不該看到管理子選單').toBe(0)
  })

  it('沒有 bpm:form:design 的一般使用者看不到表單編輯器', async () => {
    const w = await mountAs('user001')
    expect(menuText(w)).not.toContain('表單編輯器')
  })

  it('🔴 admin001 看得到表單編輯器，但看不到稽核 Log', async () => {
    const w = await mountAs('admin001')
    // 後端 /api/forms/** 的寫入接受 ROLE_ADMIN（表單 schema 是設定資產）
    expect(menuText(w)).toContain('表單編輯器')
    // 後端 /api/audit-logs/** 刻意不接受 ROLE_ADMIN（稽核含全公司薪資）
    expect(
      menuText(w),
      '🔴 ROLE_ADMIN 不等於 audit:log:read —— 顯示這一項會讓 admin001 ' +
        '看到頁面卻吃 403，正是 #82 要修的症狀'
    ).not.toContain('稽核 Log')
  })

  it('持有 audit:log:read 的稽核職能看得到稽核 Log，但不是管理員', async () => {
    const w = await mountAs('dir001')
    expect(menuText(w)).toContain('稽核 Log')
    expect(adminSubMenus(w)).toBe(0)
  })

  it('一般使用者看不到稽核 Log，也看不到表單編輯器', async () => {
    const w = await mountAs('user001')
    expect(menuText(w)).not.toContain('稽核 Log')
    expect(menuText(w)).not.toContain('表單編輯器')
  })

  it('⚠️ 表單管理維持只有管理員看得到 —— 不得順手放寬', async () => {
    // 後端 GET /api/forms 只要登入，但前端比後端嚴是允許的狀態：
    // 選單少顯示一項 vs. 開放一個產品決定。放寬要回 PM 裁決。
    //
    // ⚠️ 這裡斷言的是 el-sub-menu（管理子選單）而不是「表單管理」文字 ——
    // el-menu 在 horizontal 模式下把子選單的內容收進彈出層，未展開時
    // 不在 wrapper.text() 裡。斷言文字會得到一個「測試通過但理由是錯的」
    // 的假結果：文字不存在與「項目沒有 render」是同一件事。
    const designer = await mountAs('mgr001')
    expect(adminSubMenus(designer), 'mgr001 有表單設計權，但不該看到管理子選單').toBe(0)

    const admin = await mountAs('admin001')
    expect(adminSubMenus(admin)).toBe(1)
    expect(menuText(admin)).toContain('管理')
  })

  it('登出後畫面回到登入 overlay，選單不再顯示', async () => {
    const w = await mountAs('admin001')
    expect(menuText(w)).toContain('表單編輯器')

    useAuthStore().logout()
    await w.vm.$nextTick()

    expect(menuText(w), '登出後仍顯示管理選單').not.toContain('表單編輯器')
  })
})