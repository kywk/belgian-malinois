// Executed by the isolated cutover runner. Each identity gets a separate browser context.
import assert from 'node:assert/strict'
import { readFileSync, writeFileSync } from 'node:fs'
import { chromium, expect } from '@playwright/test'

const cfg = JSON.parse(readFileSync(0, 'utf8'))
const browser = await chromium.launch()
const api = cfg.agent.url + '/v4/recruitment/talent/applications'
const headers = who => ({ 'X-Api-Key': cfg.agent.key, Authorization: 'Bearer ' + cfg.agent[who] })

async function portal(who) {
  const context = await browser.newContext()
  await context.addInitScript(({ origin, token, apiKey }) => {
    if (location.origin === origin) window.flashlightAuth = { getToken: () => token, getApiKey: () => apiKey }
  }, { origin: cfg.field, token: cfg.agent[who], apiKey: cfg.agent.key })
  const page = await context.newPage()
  page.setDefaultTimeout(15_000)
  return page
}
const button = (page, name) => page.getByRole('button', { name, exact: true })

async function submit() {
  const page = await portal('talent')
  const loading = page.waitForResponse(r => r.url().endsWith('/talent/applications'))
  await page.goto(cfg.field + '/talent/interview')
  const initial = await loading
  const list = await initial.json()
  assert.equal(initial.status(), 200, 'TALENT application list: ' + list.code)
  assert.equal(list.code, '0000')
  await button(page, '新增聯徵草稿').click()
  await page.getByLabel('報告日期').fill('2026-01-02')
  await page.getByLabel('申請聲明').fill('合成的第六項聯徵驗收')
  await page.getByLabel('選擇聯徵報告').setInputFiles({ name: 'cutover.pdf', mimeType: 'application/pdf',
    buffer: Buffer.from('%PDF-1.4\n1 0 obj << /Type /Catalog >> endobj\n%%EOF\n') })
  await button(page, '保存附件').click()
  await expect(page.getByText('附件已保存並引用於草稿。')).toBeVisible()
  await button(page, '保存草稿').click()
  await expect(page.getByText('草稿已保存，可重新整理後恢復。')).toBeVisible()
  await page.getByRole('checkbox').check()
  await button(page, '正式送件').click()
  const accepted = page.waitForResponse(r => r.url().endsWith('/submit') && r.request().method() === 'POST')
  await button(page, '確認送出').click()
  const response = await accepted
  assert.equal(response.ok(), true)
  const { data } = await response.json()
  assert.ok(data.current_submission_id)
  await expect(page.getByText('送件版本 1', { exact: true })).toBeVisible()
  writeFileSync(cfg.resultFile, JSON.stringify({ app: data.id, submission: data.current_submission_id,
    original: response.request().postDataJSON() }))
}

async function approve() {
  const c = JSON.parse(readFileSync(cfg.resultFile))
  const page = await portal('talent')
  const request = page.request
  const auth = headers('talent')
  // A paused intake still returns the saved receipt for an exact replay.
  const replay = await request.post(api + '/' + c.app + '/submit', { headers: auth, data: c.original })
  assert.equal((await replay.json()).data.current_submission_id, c.submission)
  await page.goto(cfg.field + '/talent/interview')
  const created = page.waitForResponse(r => r.url().endsWith('/talent/applications') && r.request().method() === 'POST')
  await button(page, '新增聯徵草稿').click()
  const draft = (await (await created).json()).data
  assert.equal(draft.capabilities.can_submit, false)
  await expect(button(page, '正式送件')).toBeDisabled()
  const blocked = await request.post(api + '/' + draft.id + '/submit', { headers: auth,
    data: { ...c.original, expected_version: draft.version } })
  assert.equal(blocked.status(), 503)
  assert.equal((await blocked.json()).code, 'INTEGRATION_UNAVAILABLE')
  assert.equal((await request.get(api, { headers: headers('manager') })).status(), 403)
  const summary = cfg.agent.url + '/v4/workflow-cases/recruitment/' + c.app + '/submissions/' + c.submission + '/review'
  // A stale pooled connection on the first read after the owner restart can return 503.
  // Only retry a read. Other errors (including a lost grant) fail this cutover check.
  await expect.poll(async () => {
    const response = await request.get(summary, { headers: headers('manager') })
    const body = await response.json()
    if (response.status() === 503 && body.code === 'BPM_UPSTREAM_UNAVAILABLE') return null
    assert.equal(response.status(), 200, 'AGENT summary: ' + body.code)
    return body.data.source_revision
  }, { timeout: 15_000 }).toBe(1)
  assert.ok([401, 403].includes((await request.get(summary, { headers: auth })).status()))

  const staff = await browser.newPage({ viewport: { width: 1440, height: 1000 } })
  staff.setDefaultTimeout(15_000)
  await staff.goto(cfg.staff)
  const config = await (await staff.request.get(cfg.staff + '/api/auth/staff/config')).json()
  assert.equal(config.callbackUrl, cfg.staff + '/')
  const session = staff.waitForResponse(r => r.url().endsWith('/api/me/session'))
  await button(staff, '內勤 SSO 登入').click()
  const signedIn = await session
  assert.equal(signedIn.status(), 200)
  assert.equal((await signedIn.json()).userId, 'W9000001')
  await expect(staff.getByText('W9000001 登出')).toBeVisible({ timeout: 15_000 })
  await staff.getByRole('row').filter({ hasText: '內勤案件審核' }).click()
  await expect(button(staff, '同意')).toBeVisible()
  async function confirmApproval() {
    const response = staff.waitForResponse(r => r.url().includes('/api/tasks/') && r.request().method() === 'PUT')
    await button(staff.getByRole('dialog', { name: '同意' }), '確認').click()
    return response
  }
  assert.ok((await request.post(cfg.provider + '/testing/deny')).ok())
  await button(staff, '同意').click()
  const denied = await confirmApproval()
  assert.equal(denied.status(), 403)
  assert.equal((await denied.json()).code, 'BPM_PERMISSION_DENIED')
  await expect(staff.getByText('操作失敗：未取得此操作的權限')).toBeVisible()
  assert.ok((await request.post(cfg.provider + '/testing/regrant')).ok())
  assert.equal((await confirmApproval()).status(), 200)
  await expect.poll(async () => {
    const r = await request.get(summary, { headers: headers('manager') })
    const data = (await r.json()).data
    return [data?.outcome, data?.source_revision]
  }, { timeout: 25_000 }).toEqual(['APPROVED', 2])

  const manager = await portal('manager')
  const history = manager.waitForResponse(r => r.url().endsWith('/candidates/600000001/review-history'))
  await manager.goto(cfg.field + '/organization/candidates/600000001')
  const detail = (await (await history).json()).data.find(it => it.application_id === c.app)
  assert.equal(detail.submissions.length, 1)
  assert.equal(detail.submissions[0].submission_id, c.submission)
  assert.equal('payload' in detail.submissions[0], false)
  assert.equal('consent' in detail.submissions[0], false)
  await expect(manager.locator('article').filter({ hasText: c.app })).toContainText('APPROVED')
  await page.goto(cfg.field + '/talent/interview/' + c.app)
  await expect(page.locator('.history li').first()).toContainText('APPROVED')
  writeFileSync(cfg.resultFile, JSON.stringify({ ...c, draft: draft.id }))
}

async function revoked() {
  const c = JSON.parse(readFileSync(cfg.resultFile))
  const manager = await portal('manager')
  const history = manager.waitForResponse(r => r.url().endsWith('/candidates/600000001/review-history'))
  await manager.goto(cfg.field + '/organization/candidates/600000001')
  const response = await history
  assert.equal(response.status(), 403)
  assert.equal((await response.json()).code, 'ACCOUNT_INACTIVE')
  await expect(manager.locator('article').filter({ hasText: c.app })).toHaveCount(0)
  const single = await manager.request.get(cfg.agent.url + '/v4/recruitment/applications/' + c.app + '/review-history',
    { headers: headers('manager') })
  assert.equal(single.status(), 403)
}
async function resume() {
  const c = JSON.parse(readFileSync(cfg.resultFile))
  const page = await portal('talent')
  const response = await page.request.get(api + '/' + c.draft, { headers: headers('talent') })
  assert.equal((await response.json()).data.capabilities.can_submit, true)
  const history = await page.request.get(api + '/' + c.app + '/submissions', { headers: headers('talent') })
  const items = (await history.json()).data
  assert.equal(items.length, 1)
  assert.equal(items[0].id, c.submission)
  assert.equal(items[0].review.outcome, 'APPROVED')
}
try {
  await ({ submit, approve, revoked, resume }[cfg.step])()
} finally {
  await browser.close()
}
