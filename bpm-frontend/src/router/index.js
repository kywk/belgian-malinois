import { createRouter, createWebHistory } from 'vue-router'
import { currentIdentity } from '../services/session'
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

const routes = [
  { path: '/', component: Dashboard },
  { path: '/start', component: StartProcess },
  { path: '/tasks', component: TaskInbox },
  { path: '/tasks/:taskId', component: DocumentDetail },
  { path: '/my-applications', component: MyApplications },
  { path: '/audit-log', component: AuditLog, meta: { requiresRole: 'auditor' } },
  { path: '/admin/form-editor/:id?', component: FormEditor, meta: { requiresRole: 'admin' } },
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
 * 角色守衛。
 *
 * 改動前：路由上宣告了 meta.requiresRole，但整個 src/ 沒有任何 beforeEach ——
 * meta 是死資料，/admin/* 直接輸入 URL 就進得去。
 *
 * ⚠️ 這只是 UX 層的防線，不是安全邊界。後端目前所有 /api/** 全開放
 * （CLAUDE.md 已知技術債 #1），繞過前端直接打 API 仍然暢通無阻。
 * 真正的修補是 backlog R-01：後端 JWT + 由 token 推導 operatorId。
 *
 * 刻意不轉導到 /login —— 本專案沒有 /login 路由，未登入時是由 App.vue
 * 渲染登入 overlay 蓋住整個畫面。因此未登入時放行（overlay 會接手），
 * 只在「已登入但角色不足」時才擋下並退回首頁。
 */
router.beforeEach((to) => {
  const required = to.meta?.requiresRole
  if (!required) return true

  const { userId, roles } = currentIdentity()

  // 未登入：放行，由 App.vue 的登入 overlay 處理
  if (!userId) return true

  if (!roles.includes(required)) {
    return { path: '/', replace: true }
  }
  return true
})

export default router
