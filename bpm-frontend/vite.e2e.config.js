import { mergeConfig } from 'vite'
import base from './vite.config.js'
import { e2eEnvironment } from './e2e/environment.js'

const { apiURL, uiPort } = e2eEnvironment()
export default mergeConfig(base, {
  // Concurrent localenv Vite must not invalidate this run's optimized imports.
  cacheDir: `node_modules/.vite-browser-e2e-${uiPort}`,
  server: { host: '127.0.0.1', port: uiPort, strictPort: true,
    proxy: { '/api': { target: apiURL, changeOrigin: true } } },
})
