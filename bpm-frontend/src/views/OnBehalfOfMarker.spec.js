import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'

/**
 * #68b：審核人端的代發標示。
 *
 * <h3>產品缺口，不是授權缺陷</h3>
 *
 * <p>R-20 規定外部系統發起時 {@code initiator} 一律是 {@code system:<id>}，
 * 員工記在 {@code onBehalfOf}。結果主管在待辦清單看到的是「主管審核」，
 * 而這張單<b>沒有申請人</b> —— 他不知道該問誰補件。
 *
 * <p>後端早已把 {@code onBehalfOf} 放進 {@code GET /api/tasks} 與
 * {@code GET /api/history/tasks}（見 {@code OnBehalfOfLookup}），
 * 所以本檔驗的是<b>前端有沒有把它顯示出來</b>。
 * 後端那一半由 {@code OnBehalfOfVisibilityTest} 負責。
 *
 * <h3>⚠️ 這裡驗的是「有沒有畫出來」，不是「按鈕會不會動」</h3>
 *
 * <p>兩個頁面的資料都是 {@code onMounted} 從 API 拿的，而本檔把 service
 * 整個 mock 掉 —— 這是刻意的：那些請求路徑與授權規則都不在 #68b 的範圍，
 * 在這裡重做一次只會得到「測試看起來在跑、實際上沒驗到本工項」的假綠燈。
 *
 * <h3>⚠️ 為什麼用完整 mount 而不是 shallow</h3>
 *
 * <p>{@code shallow: true} 之下 {@code el-table}／{@code el-card} 整個被 stub，
 * slot 不渲染 —— {@code wrapper.text()} 會是空字串，那樣的斷言
 * 「{@code toContain('代 user001 發起')} 綠了」與「畫面上什麼都沒有」
 * 變成同一件事。
 */

/** 兩筆共用同一個 pid 的待辦：一筆代發、一筆人工發起（負向對照）。 */
const ON_BEHALF_TASK = {
  taskId: 'task-1',
  taskName: '主管審核',
  assignee: 'mgr001',
  processInstanceId: 'pid-68b',
  processDefinitionKey: 'leave-approval',
  formKey: 'leave-review',
  createTime: '2026-09-30T00:00:00Z',
  onBehalfOf: 'user001',
}
const PLAIN_TASK = {
  taskId: 'task-2',
  taskName: '主管審核',
  assignee: 'mgr001',
  processInstanceId: 'pid-plain',
  processDefinitionKey: 'leave-approval',
  formKey: 'leave-review',
  createTime: '2026-09-30T01:00:00Z',
  onBehalfOf: null,
}

const flush = () => new Promise((resolve) => setTimeout(resolve, 0))

// ⚠️ 刻意列出元件「會用到」的那些 export，而不是用 importOriginal 展開整個模組。
// DocumentDetail 會連帶渲染 CommentPanel 與 ApprovalTimeline，它們各自會呼叫
// getTaskComments / getHistoricTaskComments；漏一個就是「測試檔報錯但斷言全綠」，
// 那種失敗形狀比紅掉更麻煩。
vi.mock('../services/flowableApi.js', () => ({
  getTasks: vi.fn(async () => []),
  getSubtasks: vi.fn(async () => []),
  updateTask: vi.fn(async () => ({})),
  getTaskComments: vi.fn(async () => []),
  addTaskComment: vi.fn(async () => ({})),
  getHistoricTasks: vi.fn(async () => []),
  getHistoricTaskComments: vi.fn(async () => []),
  getProcessInstances: vi.fn(async () => []),
  getHistoricProcessInstances: vi.fn(async () => []),
  getProcessDefinitionXml: vi.fn(async () => ''),
}))

vi.mock('../services/http', () => ({
  default: { get: vi.fn(async () => ({ data: {} })) },
}))

// DocumentDetail 會連帶渲染 DynamicForm，而它 onMounted 會去讀表單 schema。
// 給它一份合法的空 schema（而不是讓它失敗）—— 因為 DynamicForm 把失敗記成
// console.error 之後繼續，測試仍然是綠的，但輸出裡會有雜訊，
// 而且「這個測試是走正常路徑還是走錯誤路徑」就分辨不出來了。
vi.mock('../services/formApi.js', () => ({
  getFormSchema: vi.fn(async () => ({ schemaJson: { fields: [] } })),
}))

// DocumentDetail.vue 用 useRoute() 讀 taskId、用 useRouter() 導航。
// 刻意 mock 整個 vue-router 模組而不是只補兩個函式：這兩個 view 對 router 的
// 使用都在「資料回來之後」，而本檔要驗的是畫面文字，不需要真的導航。
let routeTaskId = 'none'
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { taskId: routeTaskId } }),
  useRouter: () => ({ push: vi.fn(), back: vi.fn() }),
}))

// 兩個 view 都用 useAuthStore() 取 userId。掛一個真的 Pinia 而不是 mock store：
// mock 掉 store 會讓「userId 是哪來的」這條相依在測試裡消失，
// 而那正是 DocumentDetail 找得到 task 的原因。
import { createPinia, setActivePinia } from 'pinia'

const Inbox = (await import('./TaskInbox.vue')).default
const Detail = (await import('./DocumentDetail.vue')).default
const { getTasks } = await import('../services/flowableApi.js')

function plugins() {
  const pinia = createPinia()
  setActivePinia(pinia)
  return [ElementPlus, pinia]
}

describe('待辦清單的代發標示（#68b）', () => {
  beforeEach(() => {
    getTasks.mockClear()
    getTasks.mockResolvedValue([])
  })

  it('代發的待辦必須標示「代 user001 發起」', async () => {
    // TaskInbox 會呼叫 getTasks 兩次（assignee 與 candidateUser）並去重，
    // 兩次都回同一筆，這是它正常使用下的形狀。
    getTasks.mockResolvedValue([ON_BEHALF_TASK, PLAIN_TASK])
    const wrapper = mount(Inbox, { global: { plugins: plugins() } })
    await flush()

    const text = wrapper.text()
    expect(text).toContain('代 user001 發起')
  })

  it('人工發起的待辦不得出現代發標籤', async () => {
    // ⚠️ 這是「不得擋太寬」的對照組。
    // 99% 的待辦是人工發起的，把每一列都標成代發等於沒有標示。
    getTasks.mockResolvedValue([PLAIN_TASK])
    const wrapper = mount(Inbox, { global: { plugins: plugins() } })
    await flush()

    expect(wrapper.text()).not.toContain('代發')
    expect(wrapper.text()).toContain('主管審核')
  })
})

describe('案件詳情頁的代發標示（#68b）', () => {
  beforeEach(() => {
    getTasks.mockClear()
    getTasks.mockResolvedValue([])
  })

  async function mountDetail(task) {
    routeTaskId = task?.taskId ?? 'none'
    getTasks.mockResolvedValue(task ? [task] : [])
    const wrapper = mount(Detail, { global: { plugins: plugins() } })
    await flush()
    return wrapper
  }

  it('代發的案件必須在表單上方顯示出處', async () => {
    const wrapper = await mountDetail(ON_BEHALF_TASK)
    const text = wrapper.text()

    expect(text).toContain('外部系統代 user001 發起')
    // 標示必須在表單內容之前：它是這一頁的解讀前提（下面的欄位都是那位員工的），
    // 放在欄位之間等於要讀到一半才發現自己看的是別人的單。
    expect(text.indexOf('外部系統代 user001 發起'))
      .toBeLessThan(text.indexOf('表單內容'))
  })

  it('人工發起的案件不得出現代發說明', async () => {
    const wrapper = await mountDetail(PLAIN_TASK)

    expect(wrapper.text()).not.toContain('外部系統代')
    expect(wrapper.text()).toContain('表單內容')
  })
})
