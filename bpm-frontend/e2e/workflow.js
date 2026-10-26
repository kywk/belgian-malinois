import { createHmac, randomUUID } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import { expect, test as base } from '@playwright/test'
import { e2eEnvironment } from './environment.js'

// Matches App's dev signer and BrowserE2eIT's explicit test-only override.
const DEV_SECRET = 'bpm-dev-jwt-secret-do-not-use-in-production'
function token(user) {
  const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url')
  const now = Math.floor(Date.now() / 1000)
  const unsigned = `${encode({ alg: 'HS256', typ: 'JWT' })}.${encode({ sub: user, iat: now, exp: now + 3600 })}`
  return `${unsigned}.${createHmac('sha256', DEV_SECRET).update(unsigned).digest('base64url')}`
}

export const test = base.extend({
  // A fresh page/context is supplied by Playwright for each test; no storageState.
  workflow: async ({ page, playwright }, use) => {
    const faults = []
    page.on('pageerror', error => faults.push(`pageerror: ${error.message}`))
    page.on('console', message => {
      if (message.type() === 'error') faults.push(`console.error: ${message.text()}`)
      if (message.type() === 'warning' && message.text().includes('Invalid event arguments')) faults.push(message.text())
    })
    const api = await playwright.request.newContext({ baseURL: e2eEnvironment().apiURL })
    const workflow = new Workflow(page, api)
    try { await use(workflow) } finally {
      await api.dispose()
      expect(faults, 'Browser must not report page errors or console errors').toEqual([])
    }
  },
})
export { expect }

export class Workflow {
  constructor(page, api) { this.page = page; this.api = api; this.user = null }

  async read(user, path, params) {
    const response = await this.api.get(path, { params, headers: { Authorization: `Bearer ${token(user)}` } })
    expect(response.ok(), `GET ${path}: ${response.status()}`).toBeTruthy()
    return response.json()
  }

  async login(user) {
    const page = this.page
    if (!this.user) await page.goto('/')
    else {
      await this.menu(`${this.user} 登出`)
    }
    await expect(page.getByRole('heading', { name: 'BPM Platform — 測試登入' })).toBeVisible()
    await page.locator('select').selectOption(user)
    const permissions = page.waitForResponse(response => new URL(response.url()).pathname === '/api/me/permissions')
    await page.getByRole('button', { name: '登入', exact: true }).click()
    expect((await permissions).ok()).toBeTruthy()
    await expect(page.getByRole('menubar')).toBeVisible()
    this.user = user
  }

  async menu(name) {
    const item = this.page.getByRole('menuitem', { name, exact: true })
    if (!await item.isVisible()) {
      // Element Plus renders horizontal overflow as the last direct submenu.
      // Restrict to direct children so nested management entries cannot be clicked.
      const overflow = this.page.getByRole('menubar').locator(':scope > .el-sub-menu').last()
        .locator(':scope > .el-sub-menu__title')
      await expect(overflow).toBeVisible()
      await overflow.click()
      await expect(item).toBeVisible()
    }
    await item.click()
  }

  async createForm(scenario, { candidates = false } = {}) {
    this.key = `e2e-${scenario}-${randomUUID()}`
    this.name = `E2E ${scenario} ${this.key.slice(-8)}`
    const page = this.page
    await this.login('admin001')
    await this.menu('表單編輯器')
    await page.getByRole('textbox', { name: '表單名稱', exact: true }).fill(this.name)
    await page.getByRole('textbox', { name: '表單代碼', exact: true }).fill(this.key)
    await page.getByRole('button', { name: '文字', exact: true }).click()
    await page.getByRole('textbox', { name: '題目文字', exact: true }).fill('驗證說明')
    await page.getByRole('switch', { name: '必填', exact: true }).check()
    await page.getByRole('button', { name: '在區塊 1尾端新增題目', exact: true }).click()
    await page.getByRole('button', { name: '數字', exact: true }).click()
    await page.getByRole('textbox', { name: '題目文字', exact: true }).fill('數量')
    await page.getByRole('spinbutton', { name: '最小值', exact: true }).fill('1')
    await page.getByRole('spinbutton', { name: '最大值', exact: true }).fill('10')
    await page.getByRole('button', { name: '在區塊 1尾端新增題目', exact: true }).click()
    await page.getByRole('button', { name: '選擇', exact: true }).click()
    await page.getByRole('textbox', { name: '題目文字', exact: true }).fill('處理類別')
    await page.getByRole('textbox', { name: '選項 1', exact: true }).fill('一般')
    await page.getByRole('textbox', { name: '選項 2', exact: true }).fill('急件')
    await page.getByRole('switch', { name: '必填', exact: true }).check()
    await page.getByRole('button', { name: '儲存草稿', exact: true }).click()
    await expect(page.getByText('第 1 版・草稿', { exact: true })).toBeVisible()
    const draft = await this.read('admin001', `/api/forms/${this.key}`, { version: 1 })
    expect(draft.status).toBe('draft')
    const schema = JSON.parse(draft.schemaJson)
    const flatten = nodes => nodes.flatMap(node => ['grid', 'section'].includes(node.type)
      ? flatten(node.children ?? []) : [node])
    const fields = flatten(schema.root)
    expect(fields.map(({ type, label }) => ({ type, label }))).toEqual([
      { type: 'text', label: '驗證說明' },
      { type: 'number', label: '數量' },
      { type: 'radio', label: '處理類別' },
    ])
    expect(fields.every(node => typeof node.key === 'string' && node.key.length > 0)).toBe(true)
    expect(new Set(fields.map(node => node.key)).size).toBe(3)
    await page.getByRole('button', { name: '儲存並發布', exact: true }).click()
    await expect(page.getByText('第 1 版・已發布', { exact: true })).toBeVisible()
    await expect(page.getByRole('textbox', { name: '表單名稱', exact: true })).toBeDisabled()
    await page.getByRole('button', { name: '預覽填寫', exact: true }).click()
    const preview = page.getByRole('dialog', { name: '表單填寫預覽', exact: true })
    await expect(preview.getByRole('textbox', { name: '驗證說明', exact: true })).toBeVisible()
    await expect(preview.getByRole('spinbutton', { name: '數量', exact: true })).toBeVisible()
    await expect(preview.getByRole('radiogroup', { name: '處理類別' })).toBeVisible()
    await preview.getByRole('button', { name: 'Close this dialog', exact: true }).click()
    await expect(preview).not.toBeVisible()
    const published = await this.read('admin001', `/api/forms/${this.key}`)
    expect(published.status).toBe('published')
    // Only infrastructure setup uses a write API: deploy a UUID-bound workflow.
    let xml = await readFile(new URL('../../bpmn-definitions/leave-approval.bpmn20.xml', import.meta.url), 'utf8')
    xml = xml.replaceAll('leave-approval', this.key).replaceAll('請假審核流程', this.name)
      .replaceAll('leave-request', this.key).replace('key="reason"', `key="${fields[0].key}"`)
    if (candidates) xml = xml.replace('flowable:assignee="${assigneeResolver.resolve(execution)}"', 'flowable:candidateUsers="mgr001,mgr002"')
    const deployed = await this.api.post('/api/deployments', {
      headers: { Authorization: `Bearer ${token('admin001')}` },
      multipart: { file: { name: `${this.key}.bpmn20.xml`, mimeType: 'text/xml', buffer: Buffer.from(xml) } },
    })
    expect(deployed.ok(), `Deploy isolated workflow: ${deployed.status()}`).toBeTruthy()
  }

  async submit({ validate = false } = {}) {
    const page = this.page
    await this.login('user001')
    await this.menu('發起申請')
    // Element Plus's placeholder overlays the readonly combobox input.
    // Click the actual visible control instead of forcing a click through it.
    await page.locator('.el-select__wrapper').filter({
      has: page.getByRole('combobox', { name: '流程類型', exact: true }),
    }).click()
    await page.getByRole('option', { name: this.name, exact: true }).click()
    await expect(page.getByText('申請表第 1 版', { exact: true })).toBeVisible()
    const button = page.getByRole('button', { name: '送出申請', exact: true })
    let posts = 0
    const count = request => { if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/process-instances') posts++ }
    page.on('request', count)
    try {
      if (validate) {
        await button.click()
        await expect(page.locator('.uf-errors').first()).toBeVisible()
        expect(posts).toBe(0)
      }
      await page.getByRole('textbox', { name: '驗證說明', exact: true }).fill(`初次申請 ${this.key}`)
      await page.getByRole('spinbutton', { name: '數量', exact: true }).fill(validate ? '11' : '3')
      await page.getByText('一般', { exact: true }).click()
      await expect(page.getByRole('radio', { name: '一般', exact: true })).toBeChecked()
      if (validate) {
        await button.click()
        await expect(page.locator('.uf-errors').filter({ hasText: /10/ })).toBeVisible()
        expect(posts).toBe(0)
      }
      const started = page.waitForResponse(response => response.request().method() === 'POST'
        && new URL(response.url()).pathname === '/api/process-instances')
      // Do not Tab/blur or retry the click: this protects the fixed blur-layout regression.
      await page.getByRole('spinbutton', { name: '數量', exact: true }).fill('3')
      await button.click()
      const response = await started
      expect(response.ok()).toBeTruthy()
      this.process = await response.json()
      this.processId = this.process.processInstanceId
      expect(posts).toBe(1)
      await expect(page).toHaveURL(/\/my-applications$/)
      await expect(page.getByRole('row').filter({ hasText: this.key })).toBeVisible()
    } finally { page.off('request', count) }
  }

  async tasks(user = this.user) { return this.read(user, '/api/tasks', { assignee: user }) }
  async openTask(user, { child = false } = {}) {
    await this.login(user)
    await this.menu('待辦清單')
    const row = this.page.getByRole('row').filter({ hasText: child ? '加簽' : this.key })
    await expect(row).toHaveCount(1)
    await row.click()
    await expect(this.page).toHaveURL(/\/tasks\/[^/]+$/)
    const id = new URL(this.page.url()).pathname.split('/').pop()
    await expect(this.page.getByText(`初次申請 ${this.key}`, { exact: true })).toBeVisible()
    await expect(this.page.getByRole('textbox', { name: '驗證說明', exact: true })).toHaveCount(0)
    return id
  }

  async action(label, opinion) {
    const page = this.page
    await page.getByRole('button', { name: label, exact: true }).click()
    const dialog = page.getByRole('dialog', { name: label, exact: true })
    if (opinion) await dialog.getByRole('textbox').fill(opinion)
    await dialog.getByRole('button', { name: '確認', exact: true }).click()
    await expect(page).toHaveURL(/\/tasks$/)
  }

  async completed(status = 'completed') {
    await this.login('user001')
    await this.menu('我的申請')
    await this.page.getByRole('tab', { name: status === 'rejected' ? '已拒絕' : '已完成', exact: true }).click()
    const row = this.page.getByRole('row').filter({ hasText: this.key })
    await expect(row).toBeVisible()
    await expect(row).toContainText(status === 'rejected' ? '已拒絕' : '已完成')
    const history = await this.read('user001', '/api/history/process-instances', { initiator: 'user001', finished: true })
    const outcomes = history.filter(item => item.processDefinitionKey === this.key)
    expect(outcomes).toHaveLength(1)
    expect(outcomes[0].processInstanceId).toBe(this.processId)
    expect(outcomes[0].status).toBe(status)
    const active = await this.read('user001', '/api/process-instances', { initiator: 'user001' })
    expect(active.some(item => item.processInstanceId === this.processId)).toBe(false)
  }

  async expectComment(taskId, message, { historical = false, user = 'mgr001' } = {}) {
    // Same endpoint as flowableApi.getHistoricTaskComments; still guarded by task read access.
    const comments = await this.read(user, historical ? `/api/history/tasks/${taskId}/comments` : `/api/tasks/${taskId}/comments`)
    expect(comments.map(comment => comment.message)).toContain(message)
  }
}
