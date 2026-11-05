import { test, expect } from '@playwright/test'

test('SSO code exchange → canonical identity → todo → approve; fresh revoke denies next approval', async ({ page, request }) => {
  await page.goto('/')
  await expect(page.getByRole('heading', { name: 'BPM 內勤工作台' })).toBeVisible()
  await expect(page.getByPlaceholder('貼上 JWT')).toHaveCount(0)
  await page.getByRole('button', { name: '內勤 SSO 登入' }).click()
  await expect(page.getByText('W9000001 登出')).toBeVisible()
  await expect(page).toHaveURL(/\/tasks$/)
  expect(new URL(page.url()).searchParams.has('code')).toBe(false)
  expect(await page.evaluate(() => localStorage.getItem('token'))).toBeNull()
  const first = process.env.E2E_STAFF_TASK
  const second = process.env.E2E_STAFF_REVOKED_TASK
  await page.getByRole('row').filter({ hasText: '內勤契約簽核－成功' }).click()
  await expect(page).toHaveURL(new RegExp(`/tasks/${first}$`))
  await expect(page.getByRole('button', { name: '同意', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '同意', exact: true }).click()
  const completed = page.waitForResponse(r => r.url().endsWith(`/api/tasks/${first}`) && r.request().method() === 'PUT')
  await page.getByRole('button', { name: '確認', exact: true }).click()
  expect((await completed).status()).toBe(200)
  await page.goto(`/tasks/${second}`)
  await expect(page.getByRole('button', { name: '同意', exact: true })).toBeVisible()
  await request.post(`${process.env.E2E_STAFF_PROVIDER}/testing/deny`)
  await page.getByRole('button', { name: '同意', exact: true }).click()
  const denied = page.waitForResponse(r => r.url().endsWith(`/api/tasks/${second}`) && r.request().method() === 'PUT')
  await page.getByRole('button', { name: '確認', exact: true }).click()
  const response = await denied
  expect(response.status()).toBe(403)
  expect((await response.json()).code).toBe('BPM_PERMISSION_DENIED')
  await expect(page.getByText('操作失敗：未取得此操作的權限')).toBeVisible()
})
