import { defineConfig, devices } from '@playwright/test'
import { e2eEnvironment } from './e2e/environment.js'

const { apiURL, uiPort, uiURL } = e2eEnvironment()
export default defineConfig({
  testDir: './e2e',
  testMatch: '**/*.spec.js',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  maxFailures: 1,
  forbidOnly: true,
  timeout: 180_000,
  globalTimeout: 12 * 60_000,
  globalTeardown: './e2e/cleanup.js',
  expect: { timeout: 15_000 },
  outputDir: 'test-results/browser-e2e',
  reporter: [['list'], ['html', { outputFolder: 'playwright-report', open: 'never' }],
    ['junit', { outputFile: 'test-results/browser-e2e.xml' }]],
  use: {
    baseURL: uiURL,
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
    ...devices['Desktop Chrome'],
    viewport: { width: 1440, height: 1000 },
    trace: 'off', // No traces/HAR: authentication headers must not enter artifacts.
    video: 'off',
    screenshot: 'only-on-failure',
  },
  webServer: {
    command: 'npm run dev:e2e',
    url: uiURL,
    reuseExistingServer: false,
    timeout: 90_000,
    env: { E2E_API_URL: apiURL, E2E_UI_PORT: String(uiPort) },
    gracefulShutdown: { signal: 'SIGTERM', timeout: 5000 },
  },
})
