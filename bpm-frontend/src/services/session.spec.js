import { describe, it, expect, beforeEach } from 'vitest'
import { decodeToken, getToken, setToken, clearToken, currentIdentity, hasRole } from './session.js'

/**
 * 身分與角色（services/session.js）。
 *
 * 這個檔案是整個前端唯一的身分來源，也是 2026-09-28 那次角色政策決策
 * （auditor 收斂為明確清單）唯一改動的地方。它同時是接上真實 JWT 的縫線
 * —— decodeToken() 換掉就等於換掉整套身分來源，因此它的行為必須被釘死。
 */
describe('session', () => {
  beforeEach(() => localStorage.clear())

  describe('decodeToken', () => {
    it('未登入時回傳空身分', () => {
      expect(decodeToken(null)).toEqual({ userId: null, roles: [], raw: null })
      expect(decodeToken('')).toEqual({ userId: null, roles: [], raw: null })
    })

    it('mock 階段 token 的值就是 userId', () => {
      expect(decodeToken('user001').userId).toBe('user001')
      expect(decodeToken('user001').raw).toBe('user001')
    })

    it('admin001 同時具備 admin 與 auditor', () => {
      expect(decodeToken('admin001').roles).toEqual(['admin', 'auditor'])
    })

    it('dir001 具備 auditor 但不具備 admin', () => {
      const roles = decodeToken('dir001').roles
      expect(roles).toContain('auditor')
      expect(roles).not.toContain('admin')
    })

    it('一般使用者不得取得任何角色', () => {
      // 這是 2026-09-28 的政策決策：auditor 先前是「所有登入者皆有」，
      // 讓 router 的 auditor 守衛形同虛設。收斂後預設必須是空陣列。
      for (const u of ['user001', 'user005', 'mgr001', 'mgr002']) {
        expect(decodeToken(u).roles, `${u} 不應有角色`).toEqual([])
      }
    })

    it('不得以字串前綴取得管理權限', () => {
      // 改動前是 token.startsWith('admin')，因此 admin999 也會拿到 admin。
      expect(decodeToken('admin999').roles).toEqual([])
      expect(decodeToken('administrator').roles).toEqual([])
    })
  })

  describe('token 存取', () => {
    it('寫入後可讀回，清除後為 null', () => {
      setToken('user001')
      expect(getToken()).toBe('user001')
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
        expect(() => setToken('user001')).not.toThrow()
      } finally {
        localStorage.setItem = orig
      }
    })
  })

  describe('hasRole', () => {
    it('依當前身分判斷', () => {
      setToken('admin001')
      expect(hasRole('admin')).toBe(true)
      expect(hasRole('auditor')).toBe(true)
      setToken('user001')
      expect(hasRole('admin')).toBe(false)
      expect(hasRole('auditor')).toBe(false)
    })

    it('未指定角色時視為不需要角色', () => {
      expect(hasRole(null)).toBe(true)
      expect(hasRole('')).toBe(true)
    })
  })
})
