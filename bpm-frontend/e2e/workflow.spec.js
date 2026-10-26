import { test, expect } from './workflow.js'

async function start(workflow, scenario, options) {
  await workflow.createForm(scenario, options)
  await workflow.submit({ validate: true })
}

test('設計、發布、預覽、必填與範圍驗證、批註、催辦及核准', async ({ workflow: w, page }) => {
  await start(w, 'approve')
  const row = page.getByRole('row').filter({ hasText: w.key })
  const urged = page.waitForResponse(response => {
    const url = new URL(response.url())
    return response.request().method() === 'POST' && url.pathname === '/api/tasks/urge'
      && url.searchParams.get('processInstanceId') === w.processId
  })
  await row.getByRole('button', { name: '催辦', exact: true }).click()
  expect((await urged).ok()).toBeTruthy()
  await expect(page.getByText(/已發送催辦通知/)).toBeVisible()
  const taskId = await w.openTask('mgr001')
  await page.getByRole('textbox', { name: '輸入批註...' }).fill('E2E 審核批註')
  await page.getByRole('button', { name: '送出', exact: true }).click()
  await expect(page.getByText('E2E 審核批註', { exact: true })).toBeVisible()
  await w.expectComment(taskId, 'E2E 審核批註')
  // Read-only negative authorization check: no task writes are performed by API.
  expect((await w.tasks('user003')).some(task => task.taskId === taskId)).toBe(false)
  await w.action('同意', 'E2E 核准完成')
  await w.expectComment(taskId, 'E2E 核准完成', { historical: true })
  await w.completed()
})

test('退件、申請人限定欄位補件、重送及核准', async ({ workflow: w, page }) => {
  await start(w, 'return')
  const reviewId = await w.openTask('mgr001')
  await page.getByRole('button', { name: '退件', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '退件', exact: true })
  await expect(dialog.getByRole('button', { name: '確認', exact: true })).toBeDisabled()
  await dialog.getByRole('textbox').fill('E2E 請補充說明')
  await dialog.getByRole('button', { name: '確認', exact: true }).click()
  await expect(page).toHaveURL(/\/tasks$/)
  await w.expectComment(reviewId, 'E2E 請補充說明', { historical: true })
  await w.login('user001')
  await w.menu('待辦清單')
  await page.getByRole('row').filter({ hasText: w.key }).click()
  const reason = page.getByRole('textbox', { name: '驗證說明', exact: true })
  await expect(reason).toBeEditable()
  await expect(page.getByRole('spinbutton', { name: '數量', exact: true })).not.toBeEditable()
  await expect(page.getByRole('spinbutton', { name: '數量', exact: true })).toHaveValue('3')
  await expect(page.getByRole('radio', { name: '一般', exact: true })).toBeDisabled()
  await expect(page.getByRole('radio', { name: '一般', exact: true })).toBeChecked()
  await expect(page.getByRole('button', { name: '同意', exact: true })).toHaveCount(0)
  await reason.fill(`補件完成 ${w.key}`)
  await page.getByRole('button', { name: '補件重送', exact: true }).click()
  await expect(page).toHaveURL(/\/tasks$/)
  // Parent task is new after the return loop; verify the persisted answer in UI.
  await w.login('mgr001')
  await w.menu('待辦清單')
  await page.getByRole('row').filter({ hasText: w.key }).click()
  await expect(page.getByText(`補件完成 ${w.key}`, { exact: true })).toBeVisible()
  await expect(page.getByRole('textbox', { name: '驗證說明', exact: true })).toHaveCount(0)
  await w.action('同意', 'E2E 補件後核准')
  await w.completed()
})

test('拒絕須填原因，終止案件並保留拒絕意見', async ({ workflow: w, page }) => {
  await start(w, 'reject')
  const taskId = await w.openTask('mgr001')
  await page.getByRole('button', { name: '拒絕', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '拒絕', exact: true })
  await expect(dialog.getByRole('button', { name: '確認', exact: true })).toBeDisabled()
  await dialog.getByRole('textbox').fill('E2E 不符合申請條件')
  await dialog.getByRole('button', { name: '確認', exact: true }).click()
  await expect(page).toHaveURL(/\/tasks$/)
  await w.expectComment(taskId, 'E2E 不符合申請條件', { historical: true })
  expect((await w.tasks('mgr001')).some(task => task.taskId === taskId)).toBe(false)
  await w.completed('rejected')
})

test('轉發需選人，受託人僅能交回，原主管再核准', async ({ workflow: w, page }) => {
  await start(w, 'delegate')
  const taskId = await w.openTask('mgr001')
  await page.getByRole('button', { name: '轉發', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '轉發', exact: true })
  await expect(dialog.getByRole('button', { name: '確認', exact: true })).toBeDisabled()
  await dialog.getByPlaceholder('搜尋人員（姓名/工號）').fill('user002')
  await dialog.getByRole('row').filter({ hasText: 'user002' }).click()
  await expect(dialog.getByText('已選擇：user002', { exact: true })).toBeVisible()
  await dialog.getByRole('button', { name: '確認', exact: true }).click()
  await expect(page).toHaveURL(/\/tasks$/)
  expect((await w.tasks('mgr001')).some(task => task.taskId === taskId)).toBe(false)
  await w.openTask('user002')
  for (const label of ['同意', '退件', '拒絕', '轉發', '加簽']) {
    await expect(page.getByRole('button', { name: label, exact: true })).toHaveCount(0)
  }
  await w.action('完成轉發並交回', 'E2E 受託確認完成')
  expect((await w.tasks('user002')).some(task => task.taskId === taskId)).toBe(false)
  await w.openTask('mgr001')
  await w.expectComment(taskId, 'E2E 受託確認完成')
  await w.action('同意', 'E2E 轉發後核准')
  await w.completed()
})

test('兩人加簽意見保存，所有子任務完成前禁止父任務結案', async ({ workflow: w, page }) => {
  await start(w, 'countersign')
  const parentId = await w.openTask('mgr001')
  await page.getByRole('button', { name: '加簽', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '加簽', exact: true })
  await expect(dialog.getByRole('button', { name: '確認加簽', exact: true })).toBeDisabled()
  for (const user of ['user002', 'user003']) {
    await dialog.getByPlaceholder('搜尋人員（姓名/工號）').fill(user)
    await dialog.getByRole('row').filter({ hasText: user }).locator('.el-checkbox__inner').click()
  }
  await dialog.getByPlaceholder('加簽說明（選填）').fill('E2E 請兩位確認內容')
  await dialog.getByRole('button', { name: '確認加簽', exact: true }).click()
  await expect(dialog).not.toBeVisible()
  await expect(page.getByText('等待加簽 2 人完成', { exact: true })).toBeVisible()
  for (const label of ['同意', '退件', '拒絕']) await expect(page.getByRole('button', { name: label, exact: true })).toBeDisabled()
  for (const [index, user] of ['user002', 'user003'].entries()) {
    const childId = await w.openTask(user, { child: true })
    const child = (await w.tasks(user)).find(task => task.taskId === childId)
    expect(child.parentTaskId).toBe(parentId)
    await expect(page.getByText('加簽說明：E2E 請兩位確認內容', { exact: true })).toBeVisible()
    await expect(page.getByPlaceholder('輸入批註...')).toHaveCount(0)
    await expect(page.getByRole('button', { name: '同意', exact: true })).toHaveCount(0)
    await expect(page.getByRole('button', { name: '完成加簽', exact: true })).toBeDisabled()
    await page.getByRole('textbox', { name: '加簽意見', exact: true }).fill(`E2E ${user} 加簽通過`)
    await page.getByRole('button', { name: '完成加簽', exact: true }).click()
    await expect(page).toHaveURL(/\/tasks$/)
    expect((await w.tasks(user)).some(task => task.taskId === childId)).toBe(false)
    await w.openTask('mgr001')
    await w.expectComment(parentId, `[加簽意見 - ${user}] E2E ${user} 加簽通過`)
    if (index === 0) {
      await expect(page.getByText('等待加簽 1 人完成', { exact: true })).toBeVisible()
      await expect(page.getByRole('button', { name: '同意', exact: true })).toBeDisabled()
    }
  }
  await expect(page.getByRole('button', { name: '同意', exact: true })).toBeEnabled()
  await w.action('同意', 'E2E 所有加簽完成後核准')
  await w.completed()
})

test('候選任務認領前唯讀，認領後其他候選人不能再處理', async ({ workflow: w, page }) => {
  await start(w, 'claim', { candidates: true })
  const taskId = await w.openTask('mgr001')
  await expect(page.getByRole('button', { name: '認領任務', exact: true })).toBeVisible()
  for (const label of ['同意', '退件', '拒絕', '轉發', '加簽']) await expect(page.getByRole('button', { name: label, exact: true })).toHaveCount(0)
  await expect(page.getByPlaceholder('輸入批註...')).toHaveCount(0)
  expect((await w.tasks('mgr002')).some(task => task.taskId === taskId)).toBe(true)
  await page.getByRole('button', { name: '認領任務', exact: true }).click()
  await expect(page.getByRole('button', { name: '認領任務', exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '同意', exact: true })).toBeEnabled()
  expect((await w.tasks('mgr001')).find(task => task.taskId === taskId)?.assignee).toBe('mgr001')
  expect((await w.tasks('mgr002')).some(task => task.taskId === taskId)).toBe(false)
  await w.action('同意', 'E2E 認領後核准')
  await w.completed()
})

// Each of the six independent cases asserts its own persisted outcome above:
// five completed + one rejected. No worker-local aggregate survives worker restart.
