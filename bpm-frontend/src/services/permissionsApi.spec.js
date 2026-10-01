import { describe, it, expect, vi, beforeEach } from 'vitest'
import http from './http.js'
import { fetchMyPermissions } from './permissionsApi.js'

/**
 * permissionsApi（#82）。
 *
 * 這組測試守的是「只打對那一次請求、路徑與方法都對」——
 * 形式上很基本，但它是前端唯一的權限資料來源：
 * 打錯路徑的症狀是「權限永遠載不到」，而 store 會安靜地降級，
 * 畫面上看起來只是「選單少幾項」，不會有任何人報錯。
 */
vi.mock('./http.js', () => ({
  default: { get: vi.fn() },
}))

describe('permissionsApi', () => {
  beforeEach(() => {
    http.get.mockReset()
  })

  it('GET /api/me/permissions —— 不得帶任何「要看誰」的參數', async () => {
    // 🔴 這條對應後端 MeController 的同款約束：端點只回呼叫者自己的權限。
    //
    // 前端也不能送 ?userId= —— 後端不接受，但如果送了，
    // 將來有人加上參數時前端就會變成那條枚舉通道的客戶端。
    // 而且送了也沒用（後端忽略它），只會讓 log 與瀏覽器 history 留下
    // 「這個前端試圖查別人」的可疑紀錄。
    http.get.mockResolvedValue({ data: { userId: 'mgr001', permissions: [], admin: false } })

    await fetchMyPermissions()

    expect(http.get).toHaveBeenCalledTimes(1)
    const [path, config] = http.get.mock.calls[0]
    expect(path).toBe('/api/me/permissions')
    expect(config?.params, '前端不得指定「要看誰」').toBeUndefined()
    expect(http.get.mock.calls[0].join(' '))
      .not.toContain('userId')
  })

  it('回傳後端的回應，不要自行展開或過濾', async () => {
    // admin001 的回應是 permissions: [] + admin: true（`*` 不展開成權限碼）。
    // 前端若自己把 `*` 展開成全部權限碼，admin001 就會看到稽核入口，
    // 而後端 /api/audit-logs/** 刻意不接受 ROLE_ADMIN → 403。
    const payload = { userId: 'admin001', permissions: [], admin: true }
    http.get.mockResolvedValue({ data: payload })

    expect(await fetchMyPermissions()).toEqual(payload)
  })

  it('走共用 axios instance（身分由 interceptor 附上）', async () => {
    // 這裡不能直接 import axios —— 身分 header 是 services/http.js 的
    // request interceptor 附的，繞過它等於沒有身分。
    http.get.mockResolvedValue({ data: { userId: 'user001', permissions: [], admin: false } })

    await fetchMyPermissions()

    // 整個模組只引用 ./http.js，不引用全域 axios。
    // 這條斷言的實作意義：若有人改成 import axios from 'axios'，
    // 它不會立刻壞（vitest 只 mock 了 ./http.js），但線上會 401。
    // 所以真正的防線在 review；這裡記下這個依賴方向。
    expect(http.get).toHaveBeenCalled()
  })

  it('後端失敗時讓錯誤往上傳（降級由 store 決定，不由這裡吞掉）', async () => {
    // 降級策略（用 claim、不採納 acceptsAdmin）是政策，屬於 store 的判斷。
    // service 層若在這裡 catch 並回傳空清單，store 就分不出
    // 「後端說我沒有權限」與「後端掛了」—— 而那兩件事該做的處置不同。
    http.get.mockRejectedValue(new Error('Network Error'))

    await expect(fetchMyPermissions()).rejects.toThrow('Network Error')
  })
})