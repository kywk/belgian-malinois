import { describe, it, expect, beforeEach, vi } from 'vitest'

// ElMessage 會在 jsdom 下嘗試掛 DOM，改成 spy 以便斷言「顯示了什麼訊息」。
// vi.mock 會被提升到檔首，因此 mock 物件必須用 vi.hoisted 一起提升，
// 否則 factory 執行時變數還沒初始化。
const { elMessage } = vi.hoisted(() => ({
  elMessage: { error: vi.fn(), success: vi.fn() },
}))
vi.mock('element-plus', () => ({ ElMessage: elMessage }))

import http from './http.js'
import { setToken, getToken } from './session.js'

/**
 * 共用 axios instance（services/http.js）。
 *
 * 這是 commit 9a19fbf（R-02）的核心，也是<b>先前只能靠人工點擊驗證</b>的
 * 部分之一 —— 而那次手動走查一直沒完成。這組測試把它自動化：
 *
 *  - request interceptor 是否真的附上身分 header（不附就等於匿名呼叫後端）
 *  - 401 是否清掉 session 並導回首頁（不清就會卡在一個永遠 401 的畫面）
 *  - 錯誤是否集中處理（改動前每個呼叫端各自處理，實務上就是都沒處理）
 *
 * 用 interceptor 直接求值而非發真實請求：要驗的是 interceptor 的邏輯，
 * 起一個 mock server 只會讓測試變慢又更脆弱。
 */
describe('http instance', () => {
  beforeEach(() => {
    localStorage.clear()
    elMessage.error.mockClear()
  })

  const runRequest = (config = { headers: {} }) => {
    const handler = http.interceptors.request.handlers[0]
    return handler.fulfilled(config)
  }

  const runError = async (error) => {
    const handler = http.interceptors.response.handlers[0]
    try {
      await handler.rejected(error)
      return null
    } catch (e) {
      return e
    }
  }

  describe('身分 header', () => {
    it('已登入時附上 Authorization 與 X-User-Id', () => {
      setToken('mgr001')
      const cfg = runRequest()
      expect(cfg.headers.Authorization).toBe('Bearer mgr001')
      expect(cfg.headers['X-User-Id']).toBe('mgr001')
    })

    it('未登入時不附任何身分 header', () => {
      const cfg = runRequest()
      expect(cfg.headers.Authorization).toBeUndefined()
      expect(cfg.headers['X-User-Id']).toBeUndefined()
    })

    it('baseURL 為相對路徑（dev 走 vite proxy、prod 同源）', () => {
      expect(http.defaults.baseURL).toBe('/')
    })

    it('設有 timeout，不得無限等待', () => {
      expect(http.defaults.timeout).toBeGreaterThan(0)
    })
  })

  describe('401 處理', () => {
    // jsdom 沒有實作 navigation，因此把 location 換成可觀察的替身。
    // 這同時讓「到底導去哪裡」變成可斷言的，而不只是「沒有拋錯」。
    const stubLocation = (pathname) => {
      const calls = { assign: [], reload: 0 }
      Object.defineProperty(window, 'location', {
        configurable: true,
        value: {
          pathname,
          assign: (url) => calls.assign.push(url),
          reload: () => { calls.reload += 1 },
        },
      })
      return calls
    }

    it('清掉 session 並顯示登入失效訊息', async () => {
      const calls = stubLocation('/tasks')
      setToken('mgr001')
      await runError({ response: { status: 401, data: {} } })
      expect(getToken(), 'session 必須被清掉，否則會卡在永遠 401 的畫面').toBeNull()
      expect(elMessage.error).toHaveBeenCalledWith(expect.stringContaining('登入'))
      expect(calls.assign, '不在首頁時應導回首頁').toEqual(['/'])
    })

    it('已在首頁時重新載入而非再次導向（避免無效導航）', async () => {
      const calls = stubLocation('/')
      setToken('mgr001')
      await runError({ response: { status: 401, data: {} } })
      expect(calls.assign).toEqual([])
      expect(calls.reload).toBe(1)
    })
  })

  describe('錯誤訊息集中處理', () => {
    it('403 顯示權限不足', async () => {
      await runError({ response: { status: 403, data: {} } })
      expect(elMessage.error).toHaveBeenCalledWith(expect.stringContaining('權限'))
    })

    it('404 顯示找不到資料', async () => {
      await runError({ response: { status: 404, data: {} } })
      expect(elMessage.error).toHaveBeenCalledWith(expect.stringContaining('找不到'))
    })

    it('5xx 顯示伺服器錯誤', async () => {
      await runError({ response: { status: 500, data: {} } })
      expect(elMessage.error).toHaveBeenCalledWith(expect.stringContaining('伺服器'))
    })

    it('優先顯示後端給的 message', async () => {
      await runError({ response: { status: 409, data: { message: '有未完成的加簽子任務' } } })
      expect(elMessage.error).toHaveBeenCalledWith('有未完成的加簽子任務')
    })

    it('逾時與連線失敗有各自的訊息', async () => {
      await runError({ code: 'ECONNABORTED' })
      expect(elMessage.error).toHaveBeenCalledWith(expect.stringContaining('逾時'))
      elMessage.error.mockClear()
      await runError({ message: 'Network Error' })
      expect(elMessage.error).toHaveBeenCalledWith(expect.stringContaining('無法連線'))
    })

    it('仍然 reject，讓呼叫端保有自行處理的能力', async () => {
      const err = { response: { status: 400, data: { message: '表單驗證錯誤' } } }
      await expect(runError(err)).resolves.toBe(err)
    })
  })
})
