<template>
  <div id="app">
    <!-- 登入 overlay -->
    <div v-if="!auth.isAuthenticated" style="display:flex;align-items:center;justify-content:center;height:100vh;flex-direction:column;gap:12px">
      <h2>BPM Platform — 測試登入</h2>
      <select v-model="selectedUser" style="padding:8px;font-size:14px">
        <option value="">選擇測試用戶</option>
        <option value="user001">user001 — 王小明（員工）</option>
        <option value="user002">user002 — 李小華（員工）</option>
        <option value="user003">user003 — 張小芳（員工）</option>
        <option value="mgr001">mgr001 — 李主管</option>
        <option value="mgr002">mgr002 — 陳主管</option>
        <option value="dir001">dir001 — 王總監</option>
        <option value="admin001">admin001 — 系統管理員</option>
      </select>
      <button v-if="devSigningAvailable" @click="login" :disabled="!selectedUser" style="padding:8px 24px;cursor:pointer">登入</button>
      <!-- 靜態部署（nginx 服 dist/）沒有簽發能力 —— 那是刻意的，
           一個能簽出任何身分的前端函式不該進 production bundle。
           此時改為貼上由 IdP 或 scripts/dev-token.sh 取得的 JWT。 -->
      <template v-else>
        <input v-model="pastedToken" placeholder="貼上 JWT（scripts/dev-token.sh 可產生）"
               style="padding:8px;width:420px;font-size:12px"/>
        <button @click="loginWithPastedToken" style="padding:8px 24px;cursor:pointer">以 token 登入</button>
      </template>
      <p v-if="loginError" style="color:#c00;font-size:13px;max-width:460px;text-align:center">{{ loginError }}</p>
    </div>

    <!-- 主畫面 -->
    <template v-else>
      <el-menu mode="horizontal" router>
        <el-menu-item index="/">儀表板</el-menu-item>
        <el-menu-item index="/start">發起申請</el-menu-item>
        <el-menu-item index="/tasks">待辦清單</el-menu-item>
        <el-menu-item index="/my-applications">我的申請</el-menu-item>
        <!-- 與 router 的 requiresRole: auditor 一致。不加 v-if 的話，
             非稽核人員會看到一個點下去馬上被守衛彈回首頁的選項。 -->
        <el-menu-item v-if="isAuditor" index="/audit-log">稽核 Log</el-menu-item>
        <el-sub-menu v-if="isAdmin" index="/admin">
          <template #title>管理</template>
          <el-menu-item index="/admin/processes">流程管理</el-menu-item>
          <el-menu-item index="/admin/bpmn-editor">BPMN 編輯器</el-menu-item>
          <el-menu-item index="/admin/forms">表單管理</el-menu-item>
          <el-menu-item index="/admin/form-editor">表單編輯器</el-menu-item>
          <el-menu-item index="/admin/external-systems">外部系統</el-menu-item>
        </el-sub-menu>
        <el-menu-item style="margin-left:auto" @click="logout">
          {{ auth.userId }} 登出
        </el-menu-item>
      </el-menu>
      <router-view />
    </template>
  </div>
</template>

<script setup>
import { devSigningAvailable, mintDevToken } from './services/devToken'
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from './stores/auth'

// 身分 header 由 services/http.js 的 request interceptor 統一附上，
// 這裡不再操作 axios.defaults（改動前 store 與 App.vue 兩處都在塞 header）。
const auth = useAuthStore()
const router = useRouter()
const selectedUser = ref('')
const pastedToken = ref('')
const loginError = ref('')
const isAdmin = computed(() => auth.isAdmin)
const isAuditor = computed(() => auth.isAuditor)

/**
 * dev 登入。
 *
 * R-01 之後 token 必須是簽章過的 JWT —— 直接把 userId 當 token 送出去，
 * 後端會回 401。所以這裡先簽一個開發用 JWT。
 *
 * 簽發只在 Vite dev server 下可用（見 devToken.js）。靜態部署時
 * devSigningAvailable 為 false，登入畫面會改為要求貼上 token。
 */
async function login() {
  loginError.value = ''
  try {
    auth.setToken(await mintDevToken(selectedUser.value))
  } catch (e) {
    loginError.value = e.message
  }
}

function loginWithPastedToken() {
  loginError.value = ''
  if (!pastedToken.value.trim()) {
    loginError.value = '請貼上 JWT'
    return
  }
  auth.setToken(pastedToken.value.trim())
  if (!auth.isAuthenticated) {
    loginError.value = 'token 無法解析或已過期'
  }
}

function logout() {
  auth.logout()
  // 登出後可能停在需要角色的頁面上，退回首頁避免畫面殘留
  router.replace('/')
}
</script>
