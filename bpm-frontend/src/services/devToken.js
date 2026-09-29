/**
 * 開發用 JWT 簽發。
 *
 * ⚠️ 整個模組只在 Vite dev server 下有作用。
 *
 * `import.meta.env.DEV` 在 production build 是常量 false，Vite 會把整段
 * 分支連同密鑰一起從產出移除 —— 也就是靜態部署（nginx 服 dist/）
 * 根本沒有簽發能力。這一點很重要：一個能簽出任何身分的前端函式若進了
 * production bundle，整套認證就等於不存在。
 *
 * 為什麼前端要簽：後端<b>只驗證</b> JWT，不簽發也不處理 OIDC 流程
 * （那由另一個服務負責）。若把簽發做成 bpm-core 的端點，那就是一個
 * 可以簽出任何身分的 API，誤留在 production 的後果無法挽回。
 *
 * prod 的流程是：使用者在 IdP 登入 → 取得 JWT → 前端帶 Bearer。
 * 本檔案在那條路徑上完全不存在。
 */

/** 與 application.yml 的 bpm.security.jwt.dev-secret 一致（dev 專用）。 */
const DEV_SECRET = 'bpm-dev-jwt-secret-do-not-use-in-production'
const TTL_SECONDS = 3600

function base64Url(bytes) {
  let s = ''
  for (const b of bytes) s += String.fromCharCode(b)
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

function base64UrlJson(obj) {
  return base64Url(new TextEncoder().encode(JSON.stringify(obj)))
}

/**
 * 簽出一個開發用 JWT。
 *
 * 刻意<b>不帶 roles claim</b>：後端在 claim 沒有 roles 時會回頭查權限中心
 * （政策決策 2026-09-29）。讓 dev 走那條路，才會測到權限中心的映射 ——
 * 前端自己填 roles 會繞過它，於是「這個人到底有沒有權限」在 dev
 * 與 prod 表現不同。
 *
 * @param {string} userId 要扮演的身分
 * @returns {Promise<string>} 簽好的 JWT
 * @throws {Error} 在 production build 呼叫時
 */
export async function mintDevToken(userId) {
  if (!import.meta.env.DEV) {
    throw new Error(
      '開發用簽發只在 Vite dev server 下可用。' +
        '正式環境的 JWT 由企業 IdP 簽發，本應用只驗證。'
    )
  }

  const now = Math.floor(Date.now() / 1000)
  const header = base64UrlJson({ alg: 'HS256', typ: 'JWT' })
  const payload = base64UrlJson({ sub: userId, iat: now, exp: now + TTL_SECONDS })
  const signingInput = `${header}.${payload}`

  const key = await crypto.subtle.importKey(
    'raw',
    new TextEncoder().encode(DEV_SECRET),
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign']
  )
  const sig = await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(signingInput))
  return `${signingInput}.${base64Url(new Uint8Array(sig))}`
}

/** 這個環境能不能簽發（用來決定登入畫面顯示選單還是要求貼上 token）。 */
export const devSigningAvailable = Boolean(import.meta.env.DEV)
