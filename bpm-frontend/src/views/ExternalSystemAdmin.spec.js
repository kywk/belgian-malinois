import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'

/**
 * #68a：外部系統管理頁的 {@code allowOnBehalfOf} 開關與 resetForm() 的授權繼承。
 *
 * <h3>這個頁面怎麼靜默給出一個不該有的權限</h3>
 *
 * <p>{@code ExternalSystemAdmin.vue} 的 {@code form} 是一個跨越整個頁面生命週期的
 * {@code reactive} 物件，而 {@code resetForm()} 原本是：
 *
 * <pre>
 *   Object.assign(form, { systemId: '', systemName: '', ... })   // 少一個 allowOnBehalfOf
 * </pre>
 *
 * <p>{@code Object.assign} <b>只會新增／覆寫，不會刪掉目標上多出來的鍵</b>。
 * 所以：
 *
 * <pre>
 *   1. 編輯 erp → 打開「允許代員工發起」→ 儲存（editingId = "erp"）
 *   2. submitForm() 收尾呼叫 resetForm() → 少數幾個欄位被清空，
 *      但 form.allowOnBehalfOf 仍然是 true
 *   3. 按「建立外部系統」→ 開出一張空表單（除了那個開關是打開的）
 *   4. 填完送出 → POST 建立了一個 allowOnBehalfOf = true 的新系統
 * </pre>
 *
 * <p>後果比「頁面上少一個開關」嚴重：它是<b>授權繼承</b>，
 * 而且是靜默的 —— 畫面上看不出來，送出的 payload 也完全正常。
 * 一個新建立的外部系統從此可以代任何員工發起流程。
 *
 * <h3>另一個連帶的缺陷：按「建立」其實會改編輯中的那一筆</h3>
 *
 * <p>「建立外部系統」這顆按鈕原本只有 {@code showCreate = true}，沒有重設表單。
 * 所以「編輯 erp → 按取消 → 按建立」會開出一個裝著 erp 資料、
 * 標題卻寫著「建立外部系統」的表單，按下去送出的是對 erp 的 <b>PUT</b>。
 *
 * <h3>⚠️ 為什麼不驗「後端收不收得到這個欄位」</h3>
 *
 * <p>{@code ExternalSystemAdminController.create/update} 早就處理
 * {@code allowOnBehalfOf} 了（{@code Boolean.TRUE.equals(...)}），
 * 後端也已有 {@code AuditCoverageTest} 釘住「只改這一欄的 PUT 要留痕」。
 * 本檔驗的是<b>前端有沒有送</b> —— 那正是缺口所在，也是後端測試看不到的部分。
 *
 * <h3>⚠️ 測試透過 {@code wrapper.vm.$.setupState} 呼叫函式，不點按鈕</h3>
 *
 * <p>{@code el-dialog} 在關閉時不渲染內容（{@code v-model} 為 false），
 * 而 {@code shallow: true} 之下 {@code el-table} 的 slot 也不會渲染 ——
 * 按鈕索引在這裡不是穩定的定位方式，索引錯了會得到「測試看起來在跑、
 * 實際上什麼都沒驗到」的假綠燈。
 *
 * <p>因此這裡直接呼叫 {@code editSystem}／{@code openCreate}／{@code submitForm}／
 * {@code toggleEnabled}。這些就是按鈕綁定的那些函式本身，
 * 所以驗到的仍是「使用者操作 → 送出的 payload」這條路徑；
 * 模板的 {@code v-model} 綁定則不在本檔範圍（那是 {@code el-switch} 的既定行為）。
 */

vi.mock('../services/externalApi.js', () => ({
  getExternalSystems: vi.fn(async () => []),
  createExternalSystem: vi.fn(async () => ({ apiKey: 'sk-new' })),
  updateExternalSystem: vi.fn(async () => ({})),
  deleteExternalSystem: vi.fn(async () => ({})),
  rotateKey: vi.fn(async () => ({ apiKey: 'sk-rotated' })),
}))

const Admin = (await import('./ExternalSystemAdmin.vue')).default
const { getExternalSystems, createExternalSystem, updateExternalSystem } =
  await import('../services/externalApi.js')

const flush = () => new Promise((resolve) => setTimeout(resolve, 0))

/** 一筆「代發授權已開啟」的既有系統（來自 GET /api/admin/external-systems）。 */
const erpRow = {
  id: 'row-1',
  systemId: 'erp',
  systemName: 'ERP 系統',
  apiKey: '***',
  contactEmail: 'erp@example.com',
  allowedProcessKeys: '["leave-approval"]',
  allowedActions: '["start_process"]',
  callbackUrl: '',
  ipWhitelist: '',
  enabled: true,
  allowOnBehalfOf: true,
  createdAt: '2026-09-01T00:00:00Z',
  lastUsedAt: '2026-09-20T00:00:00Z',
  // #88 政策 B：候選群組白名單。
  allowedCandidateGroups: '["dept001"]',
}

async function mountAdmin(rows = [erpRow]) {
  getExternalSystems.mockResolvedValue(rows)
  const wrapper = mount(Admin, { shallow: true, global: { plugins: [ElementPlus] } })
  await flush()   // 等 onMounted 的 load()
  return wrapper
}

const state = (wrapper) => wrapper.vm.$.setupState

/**
 * 使用者按下「建立外部系統」這顆按鈕。
 *
 * ⚠️ 刻意<b>不</b>直接呼叫 {@code openCreate}：那樣一來，還原成改動前的
 * 元件時測試會因為「函式不存在」而紅 —— 那不是有效的負向控制組，
 * 它證明不了「授權真的會被繼承」，只證明「我加了一個函式」。
 * 這裡模擬的是<b>使用者的動作</b>，具體由元件決定那個動作是什麼；
 * 沒有 {@code openCreate} 的舊元件會退回「只把對話框打開」，
 * 於是斷言會在正確的地方紅。
 */
const clickCreate = (s) => (s.openCreate ? s.openCreate() : (s.showCreate = true))

describe('ExternalSystemAdmin 的代發授權開關（#68a）', () => {
  beforeEach(() => {
    getExternalSystems.mockClear()
    getExternalSystems.mockResolvedValue([])
    createExternalSystem.mockClear()
    createExternalSystem.mockResolvedValue({ apiKey: 'sk-new' })
    updateExternalSystem.mockClear()
    updateExternalSystem.mockResolvedValue({})
  })

  // ── 缺陷形狀 1：編輯後按「建立」，新系統繼承了代發授權 ───────────

  it('編輯過 allowOnBehalfOf=true 的系統後按「建立」，新系統不得繼承該授權', async () => {
    const wrapper = await mountAdmin()
    const s = state(wrapper)

    s.editSystem(erpRow)
    expect(s.form.allowOnBehalfOf, '前置條件：編輯時確實載入了 true').toBe(true)

    // 儲存（走 update），收尾會呼叫 resetForm()
    await s.submitForm()
    await flush()
    expect(updateExternalSystem).toHaveBeenCalledTimes(1)
    updateExternalSystem.mockClear()

    // 下一筆是「新建」
    clickCreate(s)
    s.form.systemId = 'new-erp'
    s.form.systemName = '新系統'
    await s.submitForm()
    await flush()

    expect(createExternalSystem, '這一步必須是建立，不是更新').toHaveBeenCalledTimes(1)
    const payload = createExternalSystem.mock.calls[0][0]
    expect(payload.systemId).toBe('new-erp')
    expect(payload.allowOnBehalfOf,
      '新系統繼承了 erp 的代發授權 —— 後端會照單全收（create() 只做 TRUE.equals）')
      .toBe(false)
  })

  // ── 缺陷形狀 2：按「建立」其實會改編輯中的那一筆 ─────────────────

  it('編輯 A 後按「建立」必須開出空白表單，不可沿用 A 的資料', async () => {
    const wrapper = await mountAdmin()
    const s = state(wrapper)

    s.editSystem(erpRow)
    // 使用者改個名字但按「取消」
    s.form.systemName = '被改壞的名字'
    s.showCreate = false

    clickCreate(s)
    expect(s.form.systemName, '前一個系統的半成品不得留在表單裡').toBe('')
    expect(s.form.systemId).toBe('')
    expect(s.editingId, 'editingId 必須清空，否則送出的會是 PUT').toBeNull()

    s.form.systemId = 'brand-new'
    s.form.systemName = '全新系統'
    await s.submitForm()
    await flush()

    expect(updateExternalSystem, '「建立」不得變成對前一筆的更新').not.toHaveBeenCalled()
    expect(createExternalSystem).toHaveBeenCalledTimes(1)
  })

  // ── 開關真的送得出去（這是本工項的主要功能）────────────────────

  it('開啟開關後更新必須送出 allowOnBehalfOf=true', async () => {
    const wrapper = await mountAdmin([{ ...erpRow, allowOnBehalfOf: false }])
    const s = state(wrapper)

    s.editSystem({ ...erpRow, allowOnBehalfOf: false })
    s.form.allowOnBehalfOf = true
    await s.submitForm()
    await flush()

    expect(updateExternalSystem.mock.calls[0][1].allowOnBehalfOf).toBe(true)
  })

  it('關閉既有系統的代發授權必須送出 false（後端靠這個關權）', async () => {
    const wrapper = await mountAdmin()
    const s = state(wrapper)

    s.editSystem(erpRow)
    s.form.allowOnBehalfOf = false
    await s.submitForm()
    await flush()

    expect(updateExternalSystem.mock.calls[0][1].allowOnBehalfOf,
      '送出 undefined 會讓後端把它當成 false，結果一樣但看不出是刻意的')
      .toBe(false)
  })

  it('建立新系統時預設不得開啟代發授權', async () => {
    const wrapper = await mountAdmin()
    const s = state(wrapper)

    clickCreate(s)
    expect(s.form.allowOnBehalfOf).toBe(false)

    s.form.systemId = 'brand-new'
    s.form.systemName = '全新系統'
    await s.submitForm()
    await flush()

    expect(createExternalSystem.mock.calls[0][0].allowOnBehalfOf).toBe(false)
  })

  // ── 非回歸：payload 的其他形狀不得被這次改動弄壞 ───────────────

  it('更新時必須保留 enabled —— 後端 PUT 是整欄覆寫，漏掉會撞 NOT NULL', async () => {
    // 停用中的系統被編輯：enabled=false 必須原樣送回去。
    // 這個欄位在表單上沒有輸入框，只靠「從 row 帶進來」。
    const wrapper = await mountAdmin([{ ...erpRow, enabled: false }])
    const s = state(wrapper)

    s.editSystem({ ...erpRow, enabled: false })
    s.form.systemName = '停用中的系統'
    await s.submitForm()
    await flush()

    const payload = updateExternalSystem.mock.calls[0][1]
    expect(payload.enabled).toBe(false)
    expect(payload.allowOnBehalfOf).toBe(true)
  })

  it('payload 不得夾帶唯讀欄位（id / apiKey / lastUsedAt）', async () => {
    // 改動前 editSystem() 是 Object.assign(form, row)，整列都進了表單，
    // 於是 submit 會把 id 與 apiKey 一起送出去。後端擋掉它（READ_ONLY），
    // 但那是後端的防護，不是前端該做的事 —— 而且 apiKey 欄位若哪天改成
    // 可寫入，這裡就會變成「用 *** 覆蓋金鑰雜湊」。
    const wrapper = await mountAdmin()
    const s = state(wrapper)

    s.editSystem(erpRow)
    await s.submitForm()
    await flush()

    const payload = updateExternalSystem.mock.calls[0][1]
    expect(payload).not.toHaveProperty('id')
    expect(payload).not.toHaveProperty('apiKey')
    expect(payload).not.toHaveProperty('lastUsedAt')
    expect(payload).not.toHaveProperty('createdAt')
    expect(payload.systemName).toBe('ERP 系統')
    expect(payload.allowedActions).toBe('["start_process"]')
  })

  it('停用後再啟用不得順手關掉代發授權', async () => {
    // 後端 PUT 的方向是「欄位缺席 = 收回該能力」，所以只送 {enabled:true}
    // 會把 allowOnBehalfOf 一起收掉，而畫面上沒有任何東西顯示這件事發生過。
    const wrapper = await mountAdmin([{ ...erpRow, enabled: false }])
    const s = state(wrapper)

    await s.toggleEnabled({ ...erpRow, enabled: false })
    await flush()

    expect(updateExternalSystem).toHaveBeenCalledTimes(1)
    const payload = updateExternalSystem.mock.calls[0][1]
    expect(payload.enabled).toBe(true)
    expect(payload.allowOnBehalfOf).toBe(true)
  })

  // ── 表格欄位：代發授權必須看得見 ───────────────────────────────

  it('列表必須實際顯示「代發授權」這一欄', async () => {
    // 沒有這一欄，管理員只能靠「點進編輯才知道」確認一個系統有沒有代發授權 ——
    // 而授權繼承的缺陷正是發生在「以為自己看得到」的情況下。
    //
    // ⚠️ 這一條用「完整 mount」而不是 shallow：shallow 之下 el-table 整個被
    // stub 掉，連欄位標題都不會渲染，那樣斷言的是空字串而不是「有沒有這一欄」。
    getExternalSystems.mockResolvedValue([erpRow])
    const wrapper = mount(Admin, { global: { plugins: [ElementPlus] } })
    await flush()

    expect(wrapper.text()).toContain('代發授權')
    expect(wrapper.text()).toContain('可代員工發起')
  })

  it('列表資料必須帶出 allowOnBehalfOf 供表格顯示', async () => {
    const wrapper = await mountAdmin()
    const s = state(wrapper)

    expect(s.systems).toHaveLength(1)
    expect(s.systems[0].allowOnBehalfOf).toBe(true)
  })
})

/**
 * #88 政策 B：候選群組白名單（{@code allowedCandidateGroups}）必須在表單裡。
 *
 * <h3>⚠️ 這不是「補一個輸入框」，是防一個授權缺陷</h3>
 *
 * <p>{@code applyForm()} 只從列資料挑 {@code blankForm()} 認得的鍵，而
 * {@code submitForm()} 送的是 <code>{...form}</code>。所以若
 * {@code allowedCandidateGroups} 不在 {@code blankForm()} 裡：
 *
 * <pre>
 *   1. 編輯 erp（已設定 ["dept001"] 白名單）→ 只改名字 → 儲存
 *   2. payload 沒有 allowedCandidateGroups 這個鍵
 *   3. 後端 PUT 是整欄覆寫 → 白名單變成 null ＝「不限制」
 *   4. 該系統從此可以把單子丟進任意待辦池，而畫面上完全看不出來
 * </pre>
 *
 * <p>與 #68a 的形狀相同但<b>方向更糟</b>：#68a 是新系統繼承了不該有的權限，
 * 這個是<b>既有的權限被靜默移除</b>——而且被移除的方向是放寬。
 */
describe('ExternalSystemAdmin 的候選群組白名單（#88 政策 B）', () => {
  beforeEach(() => {
    getExternalSystems.mockClear()
    getExternalSystems.mockResolvedValue([])
    createExternalSystem.mockClear()
    createExternalSystem.mockResolvedValue({ apiKey: 'sk-new' })
    updateExternalSystem.mockClear()
    updateExternalSystem.mockResolvedValue({})
  })

  it('編輯既有系統時必須把白名單載進表單', async () => {
    const wrapper = await mountAdmin()
    const s = state(wrapper)

    s.editSystem(erpRow)
    expect(s.form.allowedCandidateGroups,
      '前置條件：白名單必須從列資料載入，否則後續所有斷言都是對空字串的')
      .toBe('["dept001"]')
  })

  it('⚠️ 只改名字的儲存不得弄掉白名單（少帶欄位 = 靜默放寬授權）', async () => {
    const wrapper = await mountAdmin()
    const s = state(wrapper)

    s.editSystem(erpRow)
    s.form.systemName = 'ERP 系統（改名）'
    await s.submitForm()
    await flush()

    const payload = updateExternalSystem.mock.calls[0][1]
    expect(payload.allowedCandidateGroups,
      '後端 PUT 是整欄覆寫：漏掉這個欄位等於把白名單清成「不限制」')
      .toBe('["dept001"]')
  })

  it('設定新的白名單必須送得出去（後端靠它授權）', async () => {
    const wrapper = await mountAdmin([{ ...erpRow, allowedCandidateGroups: '' }])
    const s = state(wrapper)

    s.editSystem({ ...erpRow, allowedCandidateGroups: '' })
    s.form.allowedCandidateGroups = '["dept001","dept002"]'
    await s.submitForm()
    await flush()

    expect(updateExternalSystem.mock.calls[0][1].allowedCandidateGroups)
      .toBe('["dept001","dept002"]')
  })

  it('建立新系統時預設為「不限制」（空字串 → 後端 null → UNRESTRICTED）', async () => {
    const wrapper = await mountAdmin()
    const s = state(wrapper)

    clickCreate(s)
    expect(s.form.allowedCandidateGroups,
      '預設不得是某個群組 —— 那是授權，而授權必須由人明確給')
      .toBe('')

    s.form.systemId = 'brand-new'
    s.form.systemName = '全新系統'
    await s.submitForm()
    await flush()

    expect(createExternalSystem.mock.calls[0][0].allowedCandidateGroups).toBe('')
  })

  it('停用後再啟用不得順手清掉白名單', async () => {
    const wrapper = await mountAdmin([{ ...erpRow, enabled: false }])
    const s = state(wrapper)

    await s.toggleEnabled({ ...erpRow, enabled: false })
    await flush()

    const payload = updateExternalSystem.mock.calls[0][1]
    expect(payload.enabled).toBe(true)
    expect(payload.allowedCandidateGroups,
      'toggleEnabled 送的是 {...row}，所以 row 有沒有帶到這個欄位是後端 GET 的責任')
      .toBe('["dept001"]')
  })

  it('列表必須實際顯示「允許候選群組」這一欄與「不限制」', async () => {
    // ⚠️ 用「完整 mount」而不是 shallow（與代發授權那一條同一個理由）：
    // shallow 之下 el-table 整個被 stub 掉，連欄位標題都不會渲染。
    getExternalSystems.mockResolvedValue([erpRow, { ...erpRow, systemId: 'erp2', allowedCandidateGroups: null }])
    const wrapper = mount(Admin, { global: { plugins: [ElementPlus] } })
    await flush()

    expect(wrapper.text()).toContain('允許候選群組')
    expect(wrapper.text()).toContain('dept001')
    // 空值必須顯示成「不限制」，不能顯示成一個空欄位 ——
    // 「空白欄位」與「不限制」的差別正是授權範圍的差別。
    expect(wrapper.text()).toContain('不限制')
  })
})
