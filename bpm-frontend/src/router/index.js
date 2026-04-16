import { createRouter, createWebHistory } from 'vue-router'
import Home from '../views/Home.vue'
import AuditLog from '../views/AuditLog.vue'

const routes = [
  { path: '/', component: Home },
  { path: '/audit-log', component: AuditLog, meta: { requiresRole: 'auditor' } }
]

export default createRouter({
  history: createWebHistory(),
  routes
})
