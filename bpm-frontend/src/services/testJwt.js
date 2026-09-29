/**
 * 測試用的 JWT 組裝。
 *
 * ⚠️ 不簽章 —— 簽章部分放任意字串。
 *
 * 這不是偷懶：`decodeToken()` 刻意<b>不驗簽章</b>（驗證是後端的事），
 * 所以用假簽章測它是正確的做法。如果測試在這裡簽了名，反而會讓人
 * 以為前端有在驗 —— 而那個誤解比沒測更危險。
 *
 * 後端的驗證由 AuthenticationTest 用真實密鑰測，那才是簽章該被驗的地方。
 */
function base64Url(str) {
  return btoa(unescape(encodeURIComponent(str)))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '')
}

/**
 * @param {string|null} sub 主體識別；null 代表不帶 sub
 * @param {object} [extra] 額外的 claims（roles、exp…）
 */
export function testJwt(sub, extra = {}) {
  const claims = { ...extra }
  if (sub !== null && sub !== undefined) claims.sub = sub
  if (claims.exp === undefined) claims.exp = Math.floor(Date.now() / 1000) + 3600
  return [
    base64Url(JSON.stringify({ alg: 'HS256', typ: 'JWT' })),
    base64Url(JSON.stringify(claims)),
    'not-a-real-signature',
  ].join('.')
}
