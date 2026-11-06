// Executed by the isolated cutover runner. Each identity gets a separate browser context.
import assert from 'node:assert/strict'
import { readFileSync, writeFileSync } from 'node:fs'
import { chromium, expect } from '@playwright/test'

const cfg = JSON.parse(readFileSync(0, 'utf8'))
const browser = await chromium.launch()
const interview = cfg.applicationType === 'INTERVIEW'
const newDraft = interview ? '新增面談草稿' : '新增聯徵草稿'
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

async function fillInterview(page) {
  // Use the same controls a temporary applicant uses, including all required answer groups.
  await expect(page.getByRole('heading', { name: '面試紀錄', exact: true })).toBeVisible()
  for (const group of await page.locator('fieldset.choice-field').all()) {
    await group.getByRole('radio').first().check()
  }
  await page.getByLabel('同戶籍地址').check()
  await page.getByLabel('科技業').check()
  for (const [label, value] of Object.entries({
    姓名: '測試面談員', 出生日期: '1995-01-02', 身分證字號: 'T000000000', 戶籍郵遞區號: '100',
    行動電話: '0900000000', 電子郵件: 'interview@example.test', 學校: '合成學校',
    學院: '合成學院', 科系: '合成科系', 認識年數: '2', 認識月數: '1',
  })) await page.getByLabel(label).fill(value)
  await page.getByLabel('性別').selectOption('女')
  await page.getByLabel('戶籍縣市').selectOption('臺北市')
  await page.getByLabel('戶籍行政區').selectOption('中正區')
  await page.locator('select').filter({ has: page.getByRole('option', { name: '公立' }) }).selectOption('公立')
  for (const answer of await page.getByPlaceholder('輸入內容').all()) await answer.fill('面談驗收回答')
  await page.getByLabel('選擇面談照片').setInputFiles({
    name: 'interview.png', mimeType: 'image/png',
    buffer: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a5WQAAAAASUVORK5CYII=', 'base64'),
  })
}

async function submit() {
  const page = await portal('talent')
  const loading = page.waitForResponse(r => r.url().endsWith('/talent/applications'))
  await page.goto(cfg.field + '/talent/interview')
  const initial = await loading
  const list = await initial.json()
  assert.equal(initial.status(), 200, 'TALENT application list: ' + list.code)
  assert.equal(list.code, '0000')
  await button(page, newDraft).click()
  if (interview) {
    await fillInterview(page)
  } else {
    await page.getByLabel('報告日期').fill('2026-01-02')
    await page.getByLabel('申請聲明').fill('合成的第六項聯徵驗收')
    await page.getByLabel('選擇聯徵報告').setInputFiles({ name: 'cutover.pdf', mimeType: 'application/pdf',
      buffer: Buffer.from('%PDF-1.4\n1 0 obj << /Type /Catalog >> endobj\n%%EOF\n') })
  }
  await button(page, '保存附件').click()
  await expect(page.getByText('附件已保存並引用於草稿。')).toBeVisible()
  await button(page, '保存草稿').click()
  await expect(page.getByText('草稿已保存，可重新整理後恢復。')).toBeVisible()
  await page.getByRole('checkbox', { name: /我已閱讀並同意/ }).check()
  await button(page, '正式送件').click()
  const accepted = page.waitForResponse(r => r.url().endsWith('/submit') && r.request().method() === 'POST')
  await button(page, '確認送出').click()
  const response = await accepted
  assert.equal(response.ok(), true)
  const { data } = await response.json()
  assert.equal(data.application_type, cfg.applicationType)
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
  await button(page, newDraft).click()
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
  const login = button(staff, '內勤 SSO 登入')
  await expect(login).toBeVisible({ timeout: 30_000 })
  const [signedIn] = await Promise.all([
    staff.waitForResponse(r => r.url().endsWith('/api/me/session'), { timeout: 30_000 }),
    login.click(),
  ])
  assert.equal(signedIn.status(), 200)
  assert.equal((await signedIn.json()).userId, 'W9000001')
  await expect(staff.getByText('W9000001 登出')).toBeVisible({ timeout: 15_000 })
  const taskRow = staff.getByRole('row').filter({ hasText: interview ? '準增員面談審核' : '內勤案件審核' })
  const [formResponse] = await Promise.all([
    staff.waitForResponse(r => /\/api\/tasks\/[^/]+\/form$/.test(r.url())),
    taskRow.click(),
  ])
  assert.equal(formResponse.status(), 200)
  const form = await formResponse.json()
  assert.equal(form.schemaRef.version, 1)
  assert.equal(form.data.submissionId, c.submission)
  if (interview) {
    assert.match(form.schemaRef.key, /^interview-review-/)
    await expect(staff.getByText('面談送件編號', { exact: true })).toBeVisible()
  }
  await expect(button(staff, '同意')).toBeVisible()
  async function confirmApproval() {
    const response = staff.waitForResponse(r => r.url().includes('/api/tasks/') && r.request().method() === 'PUT')
    await button(staff.getByRole('dialog', { name: '同意' }), '確認').click()
    return response
  }
  assert.ok((await request.post(cfg.provider + (interview ? '/testing/deny-interview' : '/testing/deny'))).ok())
  await button(staff, '同意').click()
  const denied = await confirmApproval()
  assert.equal(denied.status(), 403)
  assert.equal((await denied.json()).code, 'BPM_PERMISSION_DENIED')
  await expect(staff.getByText('操作失敗：未取得此操作的權限')).toBeVisible()
  assert.ok((await request.post(cfg.provider + (interview ? '/testing/regrant-interview' : '/testing/regrant'))).ok())
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
  assert.equal(detail.application_type, cfg.applicationType)
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
  if (interview) {
    const snapshot = items[0].payload
    assert.equal(items[0].form_version, 'dev-interview-v1')
    assert.equal(items[0].consent.document_id, 'dev-interview-consent')
    assert.equal(snapshot.interview.profile.name, '測試面談員')
    assert.equal(snapshot.interview.self_assessment.length, 10)
    assert.equal(snapshot.interview.interview_answers.length, 12)
    assert.equal(snapshot.documents[0].purpose, 'INTERVIEW_PHOTO')
  }
  assert.equal(items[0].review.outcome, 'APPROVED')
}
try {
  await ({ submit, approve, revoked, resume }[cfg.step])()
} finally {
  await browser.close()
}
