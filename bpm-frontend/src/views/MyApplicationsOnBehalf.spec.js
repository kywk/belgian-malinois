import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'

/**
 * #90：申請人端（「我的申請」）的代發標示。
 *
 * <h3>缺陷（純前端；後端早就把資料給了）</h3>
 *
 * <p>{@code MyApplications.vue} <b>從來沒有渲染</b> {@code onBehalf}。
 * 後端兩個端點早就回傳它了（{@code GET /api/process-instances} 與
 * {@code GET /api/history/process-instances}），而且查詢本來就比對
 * {@code initiator} <b>或</b> {@code onBehalfOf}，所以代發的單一定會出現在
 * 那位員工��清單裡 —— 後端那一半由 {@code OnBehalfOfVisibilityTest} 的
 * 文件段落記載。本檔驗的是<b>前端有沒有把它畫出來</b>。
 *
 * <p>後果：員工看到一張自己沒送過的單，畫面完全不解釋那是怎麼回事。
 *
 * <h3>⚠️ 申請人端與審核人端的欄位<b>名字不同、型別也不同</b>——本檔最大的陷阱</h3>
 *
 * <p>{@code OnBehalfOfMarker.spec.js}（#68b，審核人端）的 fixture 用的是
 * {@code onBehalfOf: 'user001'}。申請人端<b>沒有</b>這個欄位，只有
 * {@code onBehalf: true|false}（{@code ProcessController} /
 * {@code HistoryController} 各自 {@code m.put("onBehalf", delegated.contains(...))}）。
 *
 * <p>所以本檔的 fixture <b>刻意不放 {@code onBehalfOf}</b>。
 * 若照抄 #68b 的 fixture，形狀會變成 {@code onBehalfOf} 存在而
 * {@code onBehalf} 不存在 —— 那時把 view 寫成 {@code row.onBehalfOf}
 * 的 bug <b>照樣全綠</b>，因為它讀的正是 fixture 裡那個欄位。
 * 讓 fixture 只帶真實 API 會回的欄位，錯寫欄位名才會紅。
 * {@code '不得因為 onBehalfOf 存在就顯示'} 那條測試就是守這個。
 *
 * <h3>⚠️ 為什麼用完整 mount 而不是 shallow</h3>
 *
 * <p>{@code shallow: true} 之下 {@code el-table} 整個被 stub、slot 不渲染，
 * {@code wrapper.text()} 會是空字串 —— 那樣「斷言綠了」與「畫面上什麼都沒有」
 * 變成同一件事（同 {@code OnBehalfOfMarker.spec.js} 的記錄）。
 *
 * <h3>⚠️ 這個測試組曾經<b>必須</b>掛 {@code config.warnHandler}，而那個需求已消失</h3>
 *
 * <p><b>記在這裡是為了讓下一個人不要重新引入它。</b>
 *
 * <p>發現過程：{@code MyApplications.vue} 的「狀態」欄是
 * {@code <el-tag :type="statusType(row.status)">}，而
 * {@code statusType('running')} 回傳 {@code ''}。element-plus 2.7.3 的
 * {@code ElTag} 對 {@code type} 宣告
 * {@code values: ['primary','success','info','warning','danger'], default: 'primary'}
 * —— {@code ''} 是<b>允許值以外</b>的 prop（實測會噴
 * {@code Invalid prop: validation failed for prop "type" ... got value ""}）。
 * 而那個警告在 jsdom 下把 Node 的 {@code util.inspect} 帶進無窮遞迴
 * （{@code formatProperty → formatValue → formatRaw → formatValue}），
 * {@code Maximum call stack size exceeded}，接著<b>整個 vitest worker 卡死</b>
 * —— 不是測試失敗，是 run 沒有終止。
 *
 * <p>實測對照（每一個都是完整 mount，沒有 stub 任何元件）：
 * {@code :type="statusType(row.status)"} → 掛住（120 秒無輸出）；
 * 換成 {@code type="warning"} 或 {@code type="primary"} → 26ms 正常；
 * 拿掉 {@code el-tag} → 正常。
 *
 * <p><b>現況：{@code statusType('running')} 已改成 {@code 'primary'}，
 * 所以本檔不再掛 {@code warnHandler}。</b> 順帶說明為什麼是 {@code 'primary'}
 * 而不是「乾脆不傳」：2.7.3 的 {@code ElTag} <b>沒有「無色」這個合法值</b>，
 * {@code default} 本身就是 {@code 'primary'} —— 所以 {@code ''} 從來沒有生效過，
 * 它產生的是 {@code el-tag--}（沒有任何 CSS 變數匹配）。「維持原外觀」不可達，
 * 而 {@code 'primary'} 是函式庫自己的預設。
 *
 * <p>⚠️ <b>若日後有人把 {@code statusType} 改回回傳 {@code ''}（或任何
 * {@code ElTag} 允許值以外的值），本檔會直接卡住而不會給出任何斷言失敗</b> ——
 * 那個失敗型狀比紅掉更難察覺，所以特別記錄。
 *
 * <h3>⚠️ 為什麼用 {@code .on-behalf-tag} 而不是全域 {@code toContain}</h3>
 *
 * <p>{@code wrapper.text()} 是所有列<b>攤平</b>後的字串，全域
 * {@code toContain('外部系統代為提出')} 無法分辨標籤掛在哪一列 ——
 * 而「每一列都標成代發」正是本工項最該被擋下的缺陷形狀。
 * view 刻意留了 {@code class="on-behalf-tag"} 作為 per-row 把手。
 *
 * <h3>三個 tab 都要照到（資料來源不同）</h3>
 *
 * <p>{@code running} 走 {@code getProcessInstances}，{@code completed} 與
 * {@code rejected} 走 {@code getHistoricProcessInstances} 再 filter。
 * 已結案的代發單同樣是員工沒送過的單，所以三個 tab 都必須有標示。
 * 切 tab 刻意<b>真的點</b> {@code .el-tabs__item}，走使用者實際的操作路徑，
 * 而不是直接改元件的 ref —— 後者會繞過 {@code @tab-change}，
 * 變成「測試看起來在跑、實際上沒驗到 tab 切換」。
 *
 * <h3>三點驗證（2026-10-01，三種版本各跑一次）</h3>
 *
 * <ol>
 *   <li><b>沒有修法</b>（還原成原始 {@code MyApplications.vue}）→ <b>3 紅 4 綠</b>。
 *       紅的是三個 tab 的正向斷言（每個資料來源各一條）；
 *       綠的是「不得因為 {@code onBehalfOf} 存在就顯示」與三個 tab 的負向斷言。</li>
 *   <li><b>修太寬</b>（{@code v-if="true"}，每一列都標成代發）→ <b>7 紅 0 綠</b>。
 *       這一點是負向控制組存在的理由：沒有它，{@code #68b} 那種
 *       「把所有人都標成代發」在 {@code wrapper.text()} 上仍然是綠的。</li>
 *   <li><b>正確修法</b> → 7 綠。</li>
 * </ol>
 *
 * <h3>⚠️ 第 1 點裡「綠的那幾條」比紅的更有資訊</h3>
 *
 * <p>缺陷期間那 4 條是綠的，而<b>它們綠得沒有證明力</b>：缺陷期間畫面上根本
 * 沒有任何標籤，所以「沒有顯示錯的欄位」「沒有標成代發」都是<b>廢話</b> ——
 * 它們對 #90 的修復與否<b>完全不敏感</b>。
 *
 * <p>它們的價值在第 2 點才出現：修太寬時它們全部轉紅。所以這 4 條的正確
 * 定位是<b>迴歸防護（regression guard）</b>，不是本工項的有效性證明；
 * 有效性只由那 3 條紅→綠的斷言負責。
 *
 * <p>把這件事講明白的理由：<b>「測試全綠」不等於「測試有效」</b>。
 * 一個測試組若只有負向斷言，它在缺陷期間也會全綠 —— 那正是
 * {@code OnBehalfOfMarker.spec.js} 檔頭記錄的同一個教訓。
 */

/** btoa 給一個最小可用的 JWT —— 讓 auth.userId 是真的 'user001' 而非 null。
 *  （沒有它，userId 會是 null、initiator 參數也會是 null；
 *    測試雖然還是綠的，但等於在測一個沒登入的頁面。）
 *  本檔不驗證簽章 —— decodeToken 本來就只讀 payload，驗證是後端的事。 */
function fakeJwt(sub) {
  const b64 = (o) => btoa(JSON.stringify(o)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
  return `${b64({ alg: 'none' })}.${b64({ sub, roles: ['user'] })}.sig`
}

/**
 * 真實 API 形狀（GET /api/process-instances / /api/history/process-instances）。
 *
 * ⚠️ 這裡<b>刻意沒有 onBehalfOf</b>，理由見檔案註解「申請人端與審核人端的欄位
 * 名字不同」—— 放了它就會讓「寫錯欄位名」的 bug 測不出來。
 */
const row = (over) => ({
  processInstanceId: 'pid-90',
  processDefinitionKey: 'leave-approval',
  businessKey: '',
  startTime: '2026-09-30T00:00:00Z',
  status: 'running',
  onBehalf: false,
  ...over,
})

/** 一張代外部系統送出的單。 */
const ON_BEHALF = row({
  processInstanceId: 'pid-90-delegated',
  startTime: '2026-09-30T01:00:00Z',
  onBehalf: true,
})

/** 員工自己送出的單（99% 的情況）。 */
const PLAIN = row({ processInstanceId: 'pid-90-plain', startTime: '2026-09-30T02:00:00Z' })

/** 已結案的代發單 —— 已結束不等於不是他沒送過的單。 */
const CLOSED_ON_BEHALF = row({
  processInstanceId: 'pid-90-closed',
  status: 'completed',
  onBehalf: true,
})
const CLOSED_PLAIN = row({ processInstanceId: 'pid-90-closed-plain', status: 'completed' })

/** ⚠️ 'rejected' 這個 status 後端<b>從來不產生</b>：
 *  HistoryController.processToMap 只推導出 running / completed / cancelled。
 *  「已拒絕」tab 實際上 filter 的是 status !== 'completed'，
 *  拿到的是 cancelled。這是既有行為（不在 #90 範圍），fixture 如實反映。
 */
const CANCELLED_ON_BEHALF = row({
  processInstanceId: 'pid-90-cancelled',
  status: 'cancelled',
  onBehalf: true,
})
const CANCELLED_PLAIN = row({
  processInstanceId: 'pid-90-cancelled-plain',
  status: 'cancelled',
})

const flush = () => new Promise((resolve) => setTimeout(resolve, 0))

// ⚠️ 刻意只列出 MyApplications.vue 會用到的兩個 export，不用 importOriginal。
// 它只 import getProcessInstances / getHistoricProcessInstances；
// 用 importOriginal 會把 http.js → axios 一起拖進來，而那些請求路徑
// 與授權規則不在 #90 的範圍 —— 在這裡重做一次只會得到假綠燈。
vi.mock('../services/flowableApi.js', () => ({
  getProcessInstances: vi.fn(async () => []),
  getHistoricProcessInstances: vi.fn(async () => []),
}))

const MyApplications = (await import('./MyApplications.vue')).default
const { getProcessInstances, getHistoricProcessInstances } = await import('../services/flowableApi.js')

function mountPage() {
  localStorage.setItem('token', fakeJwt('user001'))
  const pinia = createPinia()
  setActivePinia(pinia)
  return mount(MyApplications, {
    global: {
      plugins: [ElementPlus, pinia],
    },
  })
}

/** 真的點 tab 項目，走使用者實際的操作路徑（含 @tab-change → loadData）。 */
async function switchTab(wrapper, index) {
  await wrapper.findAll('.el-tabs__item')[index].trigger('click')
  await flush()
}

const tags = (wrapper) => wrapper.findAll('.on-behalf-tag')

beforeEach(() => {
  localStorage.clear()
  getProcessInstances.mockResolvedValue([])
  getHistoricProcessInstances.mockResolvedValue([])
})

describe('申請人端的代發標示（#90）', () => {
  it('「進行中」的代發單必須標示來源（GET /api/process-instances）', async () => {
    getProcessInstances.mockResolvedValue([ON_BEHALF, PLAIN])
    const wrapper = mountPage()
    await flush()

    expect(wrapper.text()).toContain('外部系統代為提出')
    expect(tags(wrapper)).toHaveLength(1)
    expect(tags(wrapper)[0].text()).toBe('外部系統代為提出')
    // 標籤必須掛在**那一列**，不是攤平後的某一處。
    expect(wrapper.findAll('tbody tr')[0].text()).toContain('外部系統代為提出')
    expect(wrapper.findAll('tbody tr')[1].text()).not.toContain('外部系統代為提出')
    // 非回歸：請求仍帶著自己的 userId（沒有被本工項改掉呼叫方式）。
    expect(getProcessInstances).toHaveBeenCalledWith({ initiator: 'user001' })
  })

  it('「已完成」的代發單必須標示來源（GET /api/history/process-instances）', async () => {
    // 已結案的代發單同樣是員工沒送過的單 —— 資料來源換成歷史端點也必須照到。
    getHistoricProcessInstances.mockResolvedValue([CLOSED_ON_BEHALF, CLOSED_PLAIN])
    const wrapper = mountPage()
    await flush()
    await switchTab(wrapper, 1)

    expect(getHistoricProcessInstances)
      .toHaveBeenCalledWith({ initiator: 'user001', finished: true })
    expect(tags(wrapper)).toHaveLength(1)
    expect(wrapper.findAll('tbody tr')[0].text()).toContain('外部系統代為提出')
    expect(wrapper.findAll('tbody tr')[1].text()).not.toContain('外部系統代為提出')
    // 已完成 tab 的列仍顯示自己的內容（不是只留一個標籤）。
    expect(wrapper.text()).toContain('已完成')
  })

  it('「已拒絕」的代發單必須標示來源', async () => {
    getHistoricProcessInstances.mockResolvedValue([CANCELLED_ON_BEHALF, CANCELLED_PLAIN])
    const wrapper = mountPage()
    await flush()
    await switchTab(wrapper, 2)

    expect(tags(wrapper)).toHaveLength(1)
    expect(wrapper.findAll('tbody tr')[0].text()).toContain('外部系統代為提出')
    expect(wrapper.findAll('tbody tr')[1].text()).not.toContain('外部系統代為提出')
  })

  it('不得因為 row 有 onBehalfOf 就顯示 —— 那是審核人端的欄位（守寫錯欄位名）', async () => {
    // 形狀刻意取自 #68b 的 fixture：一列帶著 onBehalfOf、但沒有 onBehalf。
    // 申請人端的 API 從不回傳這種 row；若 view 讀成 row.onBehalfOf，
    // 這列就會被標成代發 —— 而在真實 API ��� onBehalfOf 不存在，標籤會整個消失。
    // 兩邊都是壞的，所以這條斷言擋的是「用錯欄位」而不是「顯示與否」。
    getProcessInstances.mockResolvedValue([row({ onBehalfOf: 'user001' })])
    const wrapper = mountPage()
    await flush()

    expect(tags(wrapper)).toHaveLength(0)
    // 也不能把審核人端的文案搬過來。
    expect(wrapper.text()).not.toContain('代 user001 發起')
    expect(wrapper.text()).toContain('leave-approval')
  })
})

describe('負向控制組：99% 的申請是員工自己發起的（#90）', () => {
  it('「進行中」全是人工發起時，不得出現任何代發標籤，且仍顯示自己的內容', async () => {
    getProcessInstances.mockResolvedValue([PLAIN, row({ processDefinitionKey: 'expense-claim' })])
    const wrapper = mountPage()
    await flush()

    // 把每一列都標成代發等於沒有標示 —— 這是本工項最該被擋下的形狀。
    expect(tags(wrapper)).toHaveLength(0)
    expect(wrapper.text()).not.toContain('外部系統')
    // 標示不能是靠「蓋掉別的東西」換來的：自己的內容必須照常顯示。
    expect(wrapper.text()).toContain('leave-approval')
    expect(wrapper.text()).toContain('expense-claim')
    expect(wrapper.text()).toContain('進行中')
    expect(wrapper.findAll('tbody tr')).toHaveLength(2)
  })

  it('「已完成」全是人工發起時，不得出現任何代發標籤，且仍顯示自己的內容', async () => {
    getHistoricProcessInstances.mockResolvedValue([CLOSED_PLAIN])
    const wrapper = mountPage()
    await flush()
    await switchTab(wrapper, 1)

    expect(tags(wrapper)).toHaveLength(0)
    expect(wrapper.text()).toContain('已完成')
    expect(wrapper.text()).toContain('leave-approval')
  })

  it('「已拒絕」全是人工發起時，不得出現任何代發標籤，且仍顯示自己的內容', async () => {
    getHistoricProcessInstances.mockResolvedValue([CANCELLED_PLAIN])
    const wrapper = mountPage()
    await flush()
    await switchTab(wrapper, 2)

    expect(tags(wrapper)).toHaveLength(0)
    expect(wrapper.text()).toContain('已取消')
    expect(wrapper.text()).toContain('leave-approval')
  })
})