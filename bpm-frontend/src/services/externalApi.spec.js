import { describe, it, expect, vi, beforeEach } from 'vitest'
import http from './http.js'
import { rotateCallbackSecret } from './externalApi.js'

/**
 * externalApi 的回呼密鑰輪換（#21）。
 *
 * 這組測試守的是「只打對那一次請求、路徑與方法都對」——
 * 與 permissionsApi.spec.js 同一個理由：端點路徑寫錯的症狀是
 * 「輪換按鈕看起來成功，實際上打去別的地方」。⚠️ 管理頁的 view 測試
 * （ExternalSystemAdmin.spec.js）把本模組 mock 掉了，所以路徑錯誤在
 * 那裡不會紅 —— 這條防線刻意放在這裡。
 *
 * 輪換是破壞性的：舊密鑰立刻失效。打錯 systemId／路徑會讓另一個
 * （或不存在的）系統的回呼中斷，而呼叫端拿到的是新明文。
 */
vi.mock('./http.js', () => ({
  default: { post: vi.fn() },
}))

describe('externalApi 的 rotateCallbackSecret（#21）', () => {
  beforeEach(() => {
    http.post.mockReset()
  })

  it('POST /api/admin/external-systems/{systemId}/rotate-callback-secret', async () => {
    http.post.mockResolvedValue({ data: { systemId: 'erp', callbackSecret: 'cs-1' } })

    await rotateCallbackSecret('erp')

    expect(http.post).toHaveBeenCalledTimes(1)
    expect(http.post.mock.calls[0][0])
      .toBe('/api/admin/external-systems/erp/rotate-callback-secret')
    expect(http.post.mock.calls[0][1], '輪換不需要 body —— 明文由後端產生')
      .toBeUndefined()
  })

  it('回傳後端回應的明文 callbackSecret，不自行加工或快取', async () => {
    // 明文只在這個回應出現一次；service 若自己生成／改寫，
    // 呼叫端看到的就不是後端真正存下來的那把密鑰（HMAC 會對不上）。
    const payload = { systemId: 'erp', callbackSecret: 'cs-1' }
    http.post.mockResolvedValue({ data: payload })

    expect(await rotateCallbackSecret('erp')).toEqual(payload)
  })
})
