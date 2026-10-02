import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus, { ElMessage } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'

/**
 * #6：催辦從假提示接上真端點。
 *
 * <h3>改動前的缺陷</h3>
 *
 * <p>{@code MyApplications.vue} 的 {@code urge()} 只呼叫
 * {@code ElMessage.success('已發送催辦通知：' + assignee)}，
 * <b>沒有任何 API 呼叫</b>。按下按鈕的使用者看到「已發送」，
 * 而審核人從來沒有收到任何東西 —— 這正是本專案最優先消滅的
 * 「靜默失效」形狀（畫面說成功，實際上什麼都沒發生）。
 *
 * <h3>⚠️ 為什麼錯誤提示不在 view 的斷言範圍</h3>
 *
 * <p>403／404／429／5xx 的提示集中在 {@code http.js} 的 response 攔截器
 * （由 {@code http.spec.js} 驗證）。view 若再顯示一次會變成兩個 toast。
 * 因此本檔對失敗只斷言兩件事：
 * <ol>
 *   <li>真的呼叫了端點、帶著正確的案件 id；</li>
 *   <li><b>不得</b>顯示成功訊息 —— 缺陷期間失敗也會顯示成功，
 *       這條就是那個形狀的紅燈。</li>
 * </ol>
 *
 * <h3>⚠️ 用真的 ElementPlus 完整 mount</h3>
 *
 * <p>{@code shallow} 之下 el-table 被 stub、slot 不渲染，「按鈕不存在」
 * 與「按鈕存在但沒接線」會塌成同一件事。與
 * {@code MyApplicationsOnBehalf.spec.js} 同一個理由。
 */

function fakeJwt(sub) {
  const b64 = (o) => btoa(JSON.stringify(o)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
  return `${b64({ alg: 'none' })}.${b64({ sub, roles: ['user'] })}.sig`
}

const RUNNING = {
  processInstanceId: 'pid-urge-1',
  processDefinitionKey: 'leave-approval',
  businessKey: '',
  startTime: '2026-10-02T00:00:00Z',
  status: 'running',
  onBehalf: false,
  currentTask: { taskName: '主管審核', assignee: 'mgr001' },
}

vi.mock('../services/flowableApi.js', () => ({
  getProcessInstances: vi.fn(async () => []),
  getHistoricProcessInstances: vi.fn(async () => []),
  urgeProcess: vi.fn(async () => ({ recipients: ['mgr001'], cooldownMinutes: 30 })),
}))

const MyApplications = (await import('./MyApplications.vue')).default
const { getProcessInstances, getHistoricProcessInstances, urgeProcess } =
  await import('../services/flowableApi.js')

// restoreMocks: true（vitest.config.js）會在每個測試前還原所有 spy，
// 因此 spy 必須在 beforeEach 裡重新建立，不能掛在模組層 ——
// 掛在模組層的話測試跑起來時它已經被還原成原函式。
let successSpy

const flush = () => new Promise((resolve) => setTimeout(resolve, 0))

function mountPage() {
  localStorage.setItem('token', fakeJwt('user001'))
  const pinia = createPinia()
  setActivePinia(pinia)
  return mount(MyApplications, {
    global: { plugins: [ElementPlus, pinia] },
  })
}

async function clickUrge(wrapper) {
  const button = wrapper.findAll('button').find((b) => b.text().includes('催辦'))
  expect(button, '畫面上必須有催辦按鈕（否則後面的斷言是空的）').toBeTruthy()
  await button.trigger('click')
  await flush()
  await flush()
}

beforeEach(() => {
  localStorage.clear()
  getProcessInstances.mockResolvedValue([])
  getHistoricProcessInstances.mockResolvedValue([])
  urgeProcess.mockReset()
  urgeProcess.mockResolvedValue({ recipients: ['mgr001'], cooldownMinutes: 30 })
  successSpy = vi.spyOn(ElMessage, 'success')
})

describe('#6 催辦：真的呼叫端點', () => {
  it('按下催辦以正確的案件 id 呼叫 POST /api/tasks/urge，成功才顯示已發送', async () => {
    getProcessInstances.mockResolvedValue([RUNNING])
    const wrapper = mountPage()
    await flush()

    await clickUrge(wrapper)

    expect(urgeProcess).toHaveBeenCalledTimes(1)
    expect(urgeProcess).toHaveBeenCalledWith('pid-urge-1')
    expect(successSpy).toHaveBeenCalledWith(
      expect.stringContaining('已發送催辦通知：mgr001'))
  })

  it('收件人一律取自後端回應（候選任務沒有 assignee 時才不會顯示空白）', async () => {
    getProcessInstances.mockResolvedValue([
      { ...RUNNING, currentTask: { taskName: '財務審核', assignee: '' } },
    ])
    urgeProcess.mockResolvedValue({ recipients: ['mgr001', 'dir001'], cooldownMinutes: 30 })
    const wrapper = mountPage()
    await flush()

    await clickUrge(wrapper)

    expect(successSpy).toHaveBeenCalledWith(
      expect.stringContaining('mgr001、dir001'))
  })
})

describe('#6 催辦：失敗時絕不顯示成功', () => {
  it('403（非申請人）：顯示的錯誤由 http 攔截器負責，view 不得顯示成功', async () => {
    getProcessInstances.mockResolvedValue([RUNNING])
    urgeProcess.mockRejectedValue({ response: { status: 403, data: { message: '只有申請人可以催辦' } } })
    const wrapper = mountPage()
    await flush()

    await clickUrge(wrapper)

    expect(urgeProcess).toHaveBeenCalledWith('pid-urge-1')
    expect(successSpy).not.toHaveBeenCalled()
  })

  it('429（冷卻中）：同樣不得顯示成功', async () => {
    getProcessInstances.mockResolvedValue([RUNNING])
    urgeProcess.mockRejectedValue({
      response: { status: 429, data: { message: '已於 30 分鐘內催辦過，請稍後再試' } },
    })
    const wrapper = mountPage()
    await flush()

    await clickUrge(wrapper)

    expect(urgeProcess).toHaveBeenCalledTimes(1)
    expect(successSpy).not.toHaveBeenCalled()
  })
})

describe('#6 催辦：只對進行中的單出現', () => {
  it('已完成／已拒絕的分頁不得有催辦按鈕', async () => {
    getHistoricProcessInstances.mockResolvedValue([{ ...RUNNING, status: 'completed' }])
    const wrapper = mountPage()
    await flush()

    // 切到「已完成」
    await wrapper.findAll('.el-tabs__item')[1].trigger('click')
    await flush()

    expect(wrapper.findAll('button').filter((b) => b.text().includes('催辦'))).toHaveLength(0)
  })
})
