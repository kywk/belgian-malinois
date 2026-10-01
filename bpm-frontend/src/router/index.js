import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import Dashboard from '../views/Dashboard.vue'
import TaskInbox from '../views/TaskInbox.vue'
import DocumentDetail from '../views/DocumentDetail.vue'
import MyApplications from '../views/MyApplications.vue'
import AuditLog from '../views/AuditLog.vue'
import FormEditor from '../views/FormEditor.vue'
import BpmnEditor from '../views/BpmnEditor.vue'
import ExternalSystemAdmin from '../views/ExternalSystemAdmin.vue'
import ProcessVariableSpecAdmin from '../views/ProcessVariableSpecAdmin.vue'
import ProcessList from '../views/ProcessList.vue'
import FormList from '../views/FormList.vue'

import StartProcess from '../views/StartProcess.vue'

/**
 * 路由的權限需求。
 *
 * ⚠️ **這些宣告必須與後端 SecurityConfig 的規則一致**，兩處是同一組政策
 * 的兩份表達：
 *
 * <pre>
 * 路由                          後端規則                                 這裡
 * ───────────────────────────────────────────────────────────────────────
 * /audit-log                   audit:log:read（**不**接受 ROLE_ADMIN）  requiresPermission
 * /admin/form-editor/:id?      bpm:form:design **或** ROLE_ADMIN         requiresPermission + acceptsAdmin
 * /admin/bpmn-editor           hasRole(ADMIN)                            requiresRole
 * /admin/external-systems      hasRole(ADMIN)                            requiresRole
 * /admin/processes             hasRole(ADMIN)                            requiresRole
 * /admin/forms                 GET 只要登入（寫入才要權限碼）              requiresRole（**刻意比後端嚴**）
 * process-definitions 的 variables  hasRole(ADMIN)                       requiresRole
 * </pre>
 *
 * 兩處刻意不同的地方不能「簡化成 admin 萬能」：
 *   稽核含全公司薪資與簽核意見 → 不給 admin
 *   表單 schema 是設定資產 → 給 admin
 * 理由寫在 SecurityConfig 類別註解的對照表旁。
 *
 * ⚠️ `/admin/forms` 前端比後端嚴，這是**允許的狀態**：前端比後端嚴
 * 只是選單少顯示一項，放寬則是產品決定（表單列表要不要開放給一般員工）。
 * 要放寬請先回 PM 裁決，不要在這裡順手改掉。
 *
 * ⚠️ **這是 UX 層防線，不是安全邊界。** 繞過前端直接打 API 時，
 * 每一條規則仍由後端獨立判定。
 */
const routes = [
  { path: '/', component: Dashboard },
  { path: '/start', component: StartProcess },
  { path: '/tasks', component: TaskInbox },
  { path: '/tasks/:taskId', component: DocumentDetail },
  { path: '/my-applications', component: MyApplications },
  { path: '/audit-log', component: AuditLog, meta: { requiresPermission: 'audit:log:read' } },
  {
    path: '/admin/form-editor/:id?',
    component: FormEditor,
    // acceptsAdmin 對應後端 hasAuthority(FORM_DESIGN) || hasRole(ADMIN)。
    // 這條規則的 admin 旁路是後端刻意保留的（表單 schema 是設定資產）。
    meta: { requiresPermission: 'bpm:form:design', acceptsAdmin: true },
  },
  { path: '/admin/bpmn-editor/:processKey?', component: BpmnEditor, meta: { requiresRole: 'admin' } },
  { path: '/admin/external-systems', component: ExternalSystemAdmin, meta: { requiresRole: 'admin' } },
  { path: '/admin/processes', component: ProcessList, meta: { requiresRole: 'admin' } },
  { path: '/admin/forms', component: FormList, meta: { requiresRole: 'admin' } },
  { path: '/admin/process-definitions/:key/variables', component: ProcessVariableSpecAdmin, meta: { requiresRole: 'admin' } }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

/**
 * 權限守衛。
 *
 * 改動前：路由上宣告了 meta.requiresRole，但整個 src/ 沒有任何 beforeEach ——
 * meta 是死資料，/admin/* 直接輸入 URL 就進得去。
 *
 * ⚠️ 這只是 UX 層的防線，不是安全邊界。真正的授權全在後端
 * （SecurityConfig + ProcessAccessGuard），前端這邊的判斷不影響它。
 *
 * 刻意不轉導到 /login —— 本專案沒有 /login 路由，未登入時是由 App.vue
 * 渲染登入 overlay 蓋住整個畫面。因此未登入時放行（overlay 會接手），
 * 只在「已登入但權限不足」時才擋下並退回首頁。
 *
 * ── 為什麼這個 guard 是 async（#82）──
 *
 * 權限碼來自 GET /api/me/permissions，是非同步的。若在權限載入完成前
 * 就判斷，結果是「先放行、畫面閃一下、再被踢回首頁」—— 那個閃爍
 * 使用者會讀成「這功能壞了」。所以導航一律 await 載入（不只是需要權限的路由：App.vue 的選單也讀同一組 getter）。
 *
 * 但 await 不能無上限：後端掛掉時不能讓整個 app 卡在載入中。
 * store 的 permissionsLoaded 是三態的，loadPermissions() 無論成功
 * 或失敗都會把它設為 true，所以等待必然會結束。
 */
router.beforeEach(async (to) => {
  const auth = useAuthStore()

  // 未登入：放行，由 App.vue 的登入 overlay 處理。
  // 這必須在載入權限之前判斷 —— 未登入沒有權限可載，
  // 而且那個請求根本不會成功。
  if (!auth.isAuthenticated) return true

  // ⚠️ **連沒有權限要求的路由也要載入權限**，因為 App.vue 的選單
  // （isAdmin / isAuditor）讀的是同一組 getter。若只在有權限要求的
  // 路由上載入，登入後停在首頁的人會看到一個空的「管理」選單。
  //
  // **連角色要求的路由也要載入**：isAdmin 現在讀的是後端回報的 admin
  // 旗標（權限中心的 `*` → ROLE_ADMIN），不是 JWT 的 roles claim ——
  // dev 的 token 刻意不簽 claim。
  //
  // 這一次請求每個 session 只發一次（store 的 permissionsLoaded）。
  await auth.loadPermissions()

  const requiredRole = to.meta?.requiresRole
  const requiredPermission = to.meta?.requiresPermission
  if (!requiredRole && !requiredPermission) return true

  if (requiredPermission) {
    // ⚠️ acceptsAdmin 在降級路徑下一律**不**採用。
    //
    // 管理頁與稽核頁的權限完全建立在後端算出的 admin 旗標上；前端
    // 「推測自己是管理員」是自己發明權限。相對地，一個明確寫在
    // 簽章 claim 裡的具名權限碼是可以採信的降級 —— 而且寫入時
    // 後端會再擋一次，所以最壞情況只是「看得到編輯器、存不進去」。
    const acceptsAdmin = !!to.meta?.acceptsAdmin
    const granted = auth.hasPermission(requiredPermission)
      // ⚠️ acceptsAdmin 是**每條路由**的屬性，不是全域設定。
      // 同一個 meta 寫成 true 與 false，差別就是「這條規則後端接不接受
      // ROLE_ADMIN」—— 稽核刻意不接受，表單設計刻意接受。
      // 寫錯不會有任何報錯，只會讓人看到自己點不動的頁面。
      || (acceptsAdmin && auth.isAdmin)

    if (auth.permissionsUnverified) {
      // 降級路徑（後端不可用、權限來自 roles claim）：只認具名權限碼，
      // 不採納 acceptsAdmin —— 那是後端算出來的旗標，前端推測等於
      // 自己發明權限。最壞情況是使用者看不到編輯器入口，
      // 那比「以為自己能設計表單」好。
      if (!granted || !auth.hasPermission(requiredPermission)) {
        return { path: '/', replace: true }
      }
    } else if (!granted) {
      return { path: '/', replace: true }
    }
  }

  // 角色要求的路由。admin 的來源是後端回報的旗標（或 claim 裡的 admin），
  // 不在 permissions 陣列裡 —— 所以這裡不能查陣列。
  if (requiredRole === 'admin' && !auth.isAdmin) {
    return { path: '/', replace: true }
  }

  return true
})

export default router