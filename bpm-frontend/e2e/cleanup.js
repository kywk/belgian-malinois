import { rm } from 'node:fs/promises'
import { e2eEnvironment } from './environment.js'

export default async function cleanup() {
  const { uiPort } = e2eEnvironment()
  await rm(new URL(`../node_modules/.vite-browser-e2e-${uiPort}`, import.meta.url), { recursive: true, force: true })
}
