// Required inputs are allocated by BrowserE2eIT. Never fall back to localenv.
export function e2eEnvironment() {
  const api = process.env.E2E_API_URL
  const port = Number(process.env.E2E_UI_PORT)
  if (!api || !process.env.E2E_UI_PORT) throw new Error('E2E_API_URL and E2E_UI_PORT are required; run the isolated browser-e2e Maven profile')
  const url = new URL(api)
  if (url.protocol !== 'http:' || !['127.0.0.1', 'localhost'].includes(url.hostname)
    || !url.port || url.port === '18110' || url.username || url.password || url.pathname !== '/' || url.search || url.hash) {
    throw new Error('E2E_API_URL must be a dedicated loopback HTTP origin, not localenv port 18110')
  }
  if (!Number.isInteger(port) || port < 1024 || port > 65535 || port === 3000 || port === Number(url.port)) {
    throw new Error('E2E_UI_PORT must be a dedicated port (1024–65535), not localenv 3000 or the API port')
  }
  return { apiURL: url.origin, uiPort: port, uiURL: `http://127.0.0.1:${port}` }
}
