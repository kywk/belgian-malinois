import { defineStore } from 'pinia'
import {
  clearToken,
  decodeToken,
  getToken,
  setToken as persistToken,
} from '../services/session'

/**
 * 身分狀態。
 *
 * 改動前這個 store 直接操作 axios.defaults.headers，導致「誰負責附上身分」
 * 散落在 store 與 App.vue 兩處。現在 header 一律由 services/http.js 的
 * request interceptor 統一附上，store 只負責狀態。
 *
 * 角色判斷一律走 roles（來自 services/session.js 的 decodeToken），
 * 不要再用 token.startsWith('admin') 這種字串前綴判斷。
 */
export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: getToken(),
  }),
  getters: {
    isAuthenticated: (state) => !!state.token,
    userId: (state) => decodeToken(state.token).userId,
    roles: (state) => decodeToken(state.token).roles,
    isAdmin: (state) => decodeToken(state.token).roles.includes('admin'),
    isAuditor: (state) => decodeToken(state.token).roles.includes('auditor'),
  },
  actions: {
    setToken(token) {
      this.token = token
      persistToken(token)
    },
    logout() {
      this.token = null
      clearToken()
    },
  },
})
