import { createRouter, createWebHistory } from 'vue-router'
import Dashboard from '../views/Dashboard.vue'
import TaskInbox from '../views/TaskInbox.vue'
import DocumentDetail from '../views/DocumentDetail.vue'
import MyApplications from '../views/MyApplications.vue'
import AuditLog from '../views/AuditLog.vue'

const routes = [
  { path: '/', component: Dashboard },
  { path: '/tasks', component: TaskInbox },
  { path: '/tasks/:taskId', component: DocumentDetail },
  { path: '/my-applications', component: MyApplications },
  { path: '/audit-log', component: AuditLog, meta: { requiresRole: 'auditor' } }
]

export default createRouter({
  history: createWebHistory(),
  routes
})
