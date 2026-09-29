/**
 * 日期時間格式化的單一來源。
 *
 * <h2>為什麼要把它集中起來</h2>
 *
 * 改動前 `new Date(t).toLocaleString('zh-TW')` 這行**重複散落在 7 個檔案**
 * （ApprovalTimeline、TaskInbox、MyApplications、AuditLog、Dashboard、
 * ExternalSystemAdmin、StartProcess），各自寫成區域 `fmt` 函式。
 *
 * 升級計畫 Stage 5 指出：**Flowable 8 的日期屬性改回傳 ISO 8601 UTC**，
 * 這是整個 Boot 4 升級「唯一會外溢到前端」的破壞性變更。散在 7 個地方時，
 * 那次變更要改 7 處而且沒有任何測試防線；集中之後是改一個函式加改它的測試。
 *
 * （計畫原本只列了 4 個檔案 —— 實際盤點是 7 個，其中 Dashboard 還有日期
 *   **運算**（逾期判定、週界計算），對表示法變更更敏感。）
 */

/** 顯示用的時區與語系。集中在此，之後要支援多語系只改這裡。 */
const LOCALE = 'zh-TW'

/**
 * 格式化為當地時間字串。
 *
 * @param {string|number|Date|null|undefined} value
 *        後端回傳的時間。目前可能是 Flowable 的格式，
 *        Stage 5 之後會是 ISO 8601 UTC（例如 `2026-09-28T13:45:00.000Z`）。
 * @param {string} fallback 無值時回傳的字串（各畫面慣例不同，故可指定）
 * @returns {string} 當地時間，或 fallback
 */
export function formatDateTime(value, fallback = '') {
  if (value === null || value === undefined || value === '') return fallback
  const d = new Date(value)
  // Invalid Date 的 getTime() 是 NaN。直接回 fallback 而不是顯示
  // "Invalid Date" —— 後者會讓使用者以為資料壞了。
  if (Number.isNaN(d.getTime())) return fallback
  return d.toLocaleString(LOCALE)
}

/**
 * 格式化為 `YYYY-MM-DD`（送給後端或顯示日期區間用）。
 *
 * ⚠️ 用當地時間的年月日，不是 `toISOString().slice(0,10)`。
 * 後者取的是 UTC 日期 —— 在 UTC+8 的台灣，當地時間 8/1 08:00 之前的時刻
 * 會被算成 7/31，使用者選了 8/1 卻送出 7/31。原本 StartProcess.vue 就是
 * 用 toISOString()，屬既有缺陷，一併修正。
 */
export function formatDate(value, fallback = '') {
  if (value === null || value === undefined || value === '') return fallback
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return fallback
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

/**
 * 解析為毫秒 epoch，供排序與時間差運算使用。
 *
 * 無效值回傳 0，讓排序不會因為一筆壞資料整串爆掉
 * （`new Date(bad) - new Date(good)` 是 NaN，會讓 sort 結果不可預測）。
 */
export function toEpochMillis(value) {
  if (value === null || value === undefined || value === '') return 0
  const t = new Date(value).getTime()
  return Number.isNaN(t) ? 0 : t
}
