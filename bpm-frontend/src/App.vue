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
      <button @click="login" :disabled="!selectedUser" style="padding:8px 24px;cursor:pointer">登入</button>
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
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from './stores/auth'

// 身分 header 由 services/http.js 的 request interceptor 統一附上，
// 這裡不再操作 axios.defaults（改動前 store 與 App.vue 兩處都在塞 header）。
const auth = useAuthStore()
const router = useRouter()
const selectedUser = ref('')
const isAdmin = computed(() => auth.isAdmin)
const isAuditor = computed(() => auth.isAuditor)

function login() {
  auth.setToken(selectedUser.value)
}

function logout() {
  auth.logout()
  // 登出後可能停在需要角色的頁面上，退回首頁避免畫面殘留
  router.replace('/')
}
</script>
