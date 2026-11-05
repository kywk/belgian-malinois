import { defineConfig, devices } from '@playwright/test'
import { e2eEnvironment } from './e2e/environment.js'
const { apiURL, uiPort, uiURL } = e2eEnvironment()
export default defineConfig({
  testDir: './e2e-staff', workers: 1, retries: 0, timeout: 90_000,
  expect: { timeout: 15_000 },
  outputDir: 'test-results/staff-e2e',
  reporter: [['list'], ['junit', { outputFile: 'test-results/staff-e2e.xml' }]],
  use: { baseURL: uiURL, ...devices['Desktop Chrome'], viewport: { width: 1440, height: 1000 }, trace: 'off', video: 'off', screenshot: 'only-on-failure' },
  webServer: { command: 'npm run dev:e2e', url: uiURL, reuseExistingServer: false, timeout: 90_000,
    env: { E2E_API_URL: apiURL, E2E_UI_PORT: String(uiPort), VITE_STAFF_SSO: 'true' },
    gracefulShutdown: { signal: 'SIGTERM', timeout: 5000 } },
})
