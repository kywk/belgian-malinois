import { describe, it, expect, beforeEach } from 'vitest'
import { decodeToken, getToken, setToken, clearToken, currentIdentity, hasRole } from './session.js'
import { testJwt } from './testJwt.js'

/**
 * 身分與角色（services/session.js）。
 *
 * 這個檔案是整個前端唯一的身分來源。
 *
 * ── R-01 之後 ──
 * 後端已啟用認證，token 是簽章過的 JWT。先前的測試斷言「token 的值就是
 * userId」與「前端的 MOCK_ROLE_MAP 決定角色」—— 兩者都已不成立，
 * 而且必須不成立：前端自訂的角色表是一份會與權限中心漂移的政策複本。
 *
 * roles 現在只來自 JWT 的 roles claim。claim 沒帶時前端<b>不推測</b> ——
 * 後端會回頭查權限中心，前端猜錯只會產生「看得到選項但點下去被擋」。
 */
describe('session', () => {
  beforeEach(() => localStorage.clear())

  describe('decodeToken', () => {
    it('未登入時回傳空身分', () => {
      expect(decodeToken(null)).toEqual({ userId: null, roles: [], raw: null })
      expect(decodeToken('')).toEqual({ userId: null, roles: [], raw: null })
    })

    it('sub 成為 userId', () => {
      const t = testJwt('user001')
      expect(decodeToken(t).userId).toBe('user001')
      expect(decodeToken(t).raw).toBe(t)
    })

    it('roles claim 成為角色', () => {
      expect(decodeToken(testJwt('admin001', { roles: ['admin', 'auditor'] })).roles)
        .toEqual(['admin', 'auditor'])
    })

    it('沒有 roles claim 時前端不得自行推測角色', () => {
      // R-01 之前這裡是前端的 MOCK_ROLE_MAP 在發角色 —— 一份與權限中心
      // 平行維護的政策複本。現在 claim 沒帶就是空陣列，由後端向權限中心查。
      for (const u of ['admin001', 'dir001', 'user001', 'mgr001']) {
        expect(decodeToken(testJwt(u)).roles, `${u} 不該被前端推測出角色`).toEqual([])
      }
    })

    it('裸 userId（R-01 之前的 token）必須視為未登入', () => {
      // 舊的 localStorage 值會落到這裡。若當成有效身分，使用者會看到一個
      // 「已登入但每個請求都 401」的畫面，而且完全不知道為什麼。
      expect(decodeToken('admin001').userId).toBeNull()
      expect(decodeToken('user001').userId).toBeNull()
    })

    it('不得以字串前綴取得管理權限', () => {
      // 改動前是 token.startsWith('admin')，因此 admin999 也會拿到 admin。
      expect(decodeToken(testJwt('admin999')).roles).toEqual([])
      expect(decodeToken(testJwt('administrator')).roles).toEqual([])
    })

    it('過期的 token 視為未登入', () => {
      const expired = testJwt('admin001', {
        roles: ['admin'],
        exp: Math.floor(Date.now() / 1000) - 60,
      })
      expect(decodeToken(expired).userId).toBeNull()
    })

    it('沒有 sub 的 token 視為未登入', () => {
      expect(decodeToken(testJwt(null, { roles: ['admin'] })).userId).toBeNull()
    })

    it('無法解析的 payload 視為未登入，不得拋出', () => {
      expect(() => decodeToken('aaa.!!!not-base64!!!.ccc')).not.toThrow()
      expect(decodeToken('aaa.!!!not-base64!!!.ccc').userId).toBeNull()
    })

    it('UTF-8 內容必須正確還原', () => {
      // atob 只處理 latin1，中文姓名等內容需要額外轉換。
      expect(decodeToken(testJwt('王小明')).userId).toBe('王小明')
    })
  })

  describe('token 存取', () => {
    it('寫入後可讀回，清除後為 null', () => {
      const t = testJwt('user001')
      setToken(t)
      expect(getToken()).toBe(t)
      clearToken()
      expect(getToken()).toBeNull()
    })

    it('localStorage 讀取失敗時視為未登入，不得讓 app 崩掉', () => {
      // 私密視窗、站台資料被封鎖等情況下 localStorage 會拋錯。
      const orig = localStorage.getItem
      localStorage.getItem = () => { throw new Error('SecurityError') }
      try {
        expect(getToken()).toBeNull()
        expect(currentIdentity().userId).toBeNull()
      } finally {
        localStorage.getItem = orig
      }
    })

    it('localStorage 寫入失敗不得拋出', () => {
      const orig = localStorage.setItem
      localStorage.setItem = () => { throw new Error('QuotaExceeded') }
      try {
        expect(() => setToken(testJwt('user001'))).not.toThrow()
      } finally {
        localStorage.setItem = orig
      }
    })
  })

  describe('hasRole', () => {
    it('依當前身分判斷', () => {
      setToken(testJwt('admin001', { roles: ['admin', 'auditor'] }))
      expect(hasRole('admin')).toBe(true)
      expect(hasRole('auditor')).toBe(true)
      setToken(testJwt('user001'))
      expect(hasRole('admin')).toBe(false)
      expect(hasRole('auditor')).toBe(false)
    })

    it('未指定角色時視為不需要角色', () => {
      expect(hasRole(null)).toBe(true)
      expect(hasRole('')).toBe(true)
    })
  })
})
