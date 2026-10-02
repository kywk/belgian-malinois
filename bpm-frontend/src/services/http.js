/**
 * 共用的 axios instance。
 *
 * 改動前：5 個 service 檔各自 `import axios from 'axios'` 裸用全域 axios，
 * 身分 header 由 App.vue 塞進 axios.defaults，沒有任何錯誤處理、沒有 401 轉導。
 * 後果是每個呼叫端都要自己處理失敗，實務上就是都沒處理。
 *
 * 改動後：所有 API 呼叫走這個 instance，身分與錯誤處理集中在此。
 * 這同時是接上真實認證（R-01 / backlog #62）的前置條件 —— 屆時 401 的
 * refresh/轉導邏輯只需改這一個檔案。
 */
import axios from 'axios'
import { ElMessage } from 'element-plus'
import { clearToken, currentIdentity } from './session'

const http = axios.create({
  // 相對路徑：dev 由 vite proxy 轉到 :80，prod 由 nginx 同源服務
  baseURL: '/',
  timeout: 30000,
})

// ── Request：統一附上身分 ──────────────────────────────────────────
http.interceptors.request.use((config) => {
  const { userId, raw } = currentIdentity()
  if (raw) {
    config.headers['Authorization'] = `Bearer ${raw}`
    // ⚠️ 不要再送 X-User-Id（R-01）。
    //
    // 後端已改為從 JWT 的 sub 取得身分；裸的 X-User-Id 只在請求同時帶了
    // 閘道密鑰時才構成身分，而瀏覽器沒有那個密鑰（也不該有）。
    // 繼續送它只會在排查時誤導 —— 讓人以為身分是從那裡來的。
  }
  return config
})

// ── Response：集中錯誤處理 ────────────────────────────────────────
http.interceptors.response.use(
  (response) => response,
  (error) => {
    const status = error.response?.status
    // 後端以 ResponseStatusException 回傳時訊息在 message，其餘情況退回 axios 訊息
    const detail = error.response?.data?.message || error.response?.data?.error

    if (status === 401) {
      // 憑證失效：清掉 session 並回首頁。
      // 這裡刻意不 import router —— router 會 import views、views import services，
      // 形成循環相依。直接用 location 導回首頁，由 App.vue 的登入 overlay 接手。
      clearToken()
      ElMessage.error('登入已失效，請重新登入')
      if (window.location.pathname !== '/') {
        window.location.assign('/')
      } else {
        window.location.reload()
      }
    } else if (status === 403) {
      ElMessage.error(detail || '權限不足，無法執行此操作')
    } else if (status === 429) {
      // #6 催辦的頻率限制（也適用未來任何限流端點）：
      // 用 warning 而不是 error —— 這不是故障，是刻意設計的冷卻，
      // 且訊息要告訴使用者「等多久」（後端會把 30 分鐘寫進 message）。
      ElMessage.warning(detail || '操作過於頻繁，請稍後再試')
    } else if (status === 404) {
      ElMessage.error(detail || '找不到指定的資料')
    } else if (status >= 500) {
      ElMessage.error(detail || `伺服器錯誤 (${status})，請稍後再試或聯絡系統管理員`)
    } else if (error.code === 'ECONNABORTED') {
      ElMessage.error('請求逾時，請確認後端服務是否正常')
    } else if (!error.response) {
      ElMessage.error('無法連線到伺服器')
    } else if (detail) {
      ElMessage.error(detail)
    }

    // 仍然 reject，讓呼叫端保有自行處理的能力（例如表單驗證錯誤）
    return Promise.reject(error)
  }
)

export default http
