import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * 前端測試設定。
 *
 * 為什麼需要它（backlog #64 / CLAUDE.md 技術債 #2）：前端先前完全沒有測試
 * 框架，因此「axios instance 是否附上身分 header」、「router 角色守衛是否
 * 真的擋得住」這類問題只能靠人工點擊驗證 —— 而那件事一直沒做完。
 *
 * 更急迫的理由是升級計畫 Stage 5：Flowable 8 的日期屬性改回傳 ISO 8601 UTC，
 * 這是整個升級**唯一會外溢到前端**的破壞性變更，而時間顯示散落在 7 個檔案。
 * 沒有自動化防線就動它，等於閉著眼睛改時區。
 *
 * environment 用 jsdom：http.js 的 401 處理會碰 window.location，
 * session.js 會碰 localStorage，兩者都需要瀏覽器環境。
 */
export default defineConfig({
  plugins: [vue()],
  test: {
    environment: 'jsdom',
    globals: true,
    include: ['src/**/*.spec.js'],
    restoreMocks: true,
  },
})
