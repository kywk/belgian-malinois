import { describe, it, expect, beforeAll, afterAll } from 'vitest'
import { formatDateTime, formatDate, toEpochMillis } from './datetime.js'

/**
 * 日期時間格式化 —— 升級計畫 Stage 5 的前置防線。
 *
 * Flowable 8 的日期屬性會改回傳 ISO 8601 UTC，這是整個 Boot 4 升級唯一會
 * 外溢到前端的破壞性變更。這組測試把「ISO 8601 UTC 進來要顯示成什麼」
 * 釘死，讓 Stage 5 動手時有東西擋著。
 *
 * 測試固定在 UTC+8 執行（process.env.TZ），否則同一組斷言在不同機器／CI
 * 上會有不同結果 —— 而時區正是這次要驗的東西。
 */
describe('datetime', () => {
  let originalTZ
  beforeAll(() => {
    originalTZ = process.env.TZ
    process.env.TZ = 'Asia/Taipei'
  })
  afterAll(() => { process.env.TZ = originalTZ })

  describe('formatDateTime', () => {
    it('ISO 8601 UTC 必須顯示為當地時間（Stage 5 的核心情境）', () => {
      // 2026-09-28T13:45:00Z 在 UTC+8 是 2026/9/28 21:45
      const out = formatDateTime('2026-09-28T13:45:00.000Z')
      expect(out).toContain('2026')
      expect(out).toMatch(/21:45|下午9:45|晚上9:45/)
    })

    it('跨日邊界必須用當地時間判斷', () => {
      // UTC 的 9/28 16:30 在 UTC+8 已經是 9/29 00:30
      expect(formatDateTime('2026-09-28T16:30:00.000Z')).toContain('9/29')
    })

    it('無值回傳 fallback，預設為空字串', () => {
      expect(formatDateTime(null)).toBe('')
      expect(formatDateTime(undefined)).toBe('')
      expect(formatDateTime('')).toBe('')
      expect(formatDateTime(null, '-')).toBe('-')
    })

    it('無效日期回傳 fallback，不得顯示 Invalid Date', () => {
      // 顯示 "Invalid Date" 會讓使用者以為資料壞了
      expect(formatDateTime('not-a-date')).toBe('')
      expect(formatDateTime('not-a-date', '-')).toBe('-')
    })

    it('接受 Date 物件與 epoch 毫秒', () => {
      expect(formatDateTime(new Date('2026-09-28T13:45:00Z'))).toContain('2026')
      expect(formatDateTime(Date.parse('2026-09-28T13:45:00Z'))).toContain('2026')
    })
  })

  describe('formatDate', () => {
    it('用當地時間的年月日，不是 UTC 日期', () => {
      // 這是原本 StartProcess.vue 用 toISOString().slice(0,10) 的缺陷：
      // 當地 9/29 00:30（UTC 9/28 16:30）會被算成 9/28。
      expect(formatDate('2026-09-28T16:30:00.000Z')).toBe('2026-09-29')
      expect(formatDate('2026-09-28T13:45:00.000Z')).toBe('2026-09-28')
    })

    it('月與日補零', () => {
      expect(formatDate('2026-01-05T04:00:00.000Z')).toBe('2026-01-05')
    })

    it('無效值回傳 fallback', () => {
      expect(formatDate(null)).toBe('')
      expect(formatDate('garbage', '-')).toBe('-')
    })
  })

  describe('toEpochMillis', () => {
    it('可用於排序', () => {
      const older = toEpochMillis('2026-09-27T00:00:00Z')
      const newer = toEpochMillis('2026-09-28T00:00:00Z')
      expect(newer).toBeGreaterThan(older)
    })

    it('無效值回傳 0，不得讓排序結果變成不可預測', () => {
      // new Date(bad) - new Date(good) 是 NaN，comparator 回 NaN 時
      // Array.sort 的行為未定義 —— 一筆壞資料會弄亂整串。
      expect(toEpochMillis('garbage')).toBe(0)
      expect(toEpochMillis(null)).toBe(0)
      const cmp = toEpochMillis('2026-09-28T00:00:00Z') - toEpochMillis('garbage')
      expect(Number.isNaN(cmp)).toBe(false)
    })
  })
})
