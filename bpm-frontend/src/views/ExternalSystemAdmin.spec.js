import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus, { ElMessage, ElMessageBox } from 'element-plus'

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
  createExternalSystem: vi.fn(async () => ({ apiKey: 'sk-new', callbackSecret: 'cs-new' })),
  updateExternalSystem: vi.fn(async () => ({})),
  deleteExternalSystem: vi.fn(async () => ({})),
  rotateKey: vi.fn(async () => ({ apiKey: 'sk-rotated' })),
  rotateCallbackSecret: vi.fn(async () => ({ callbackSecret: 'cs-rotated' })),
}))

const Admin = (await import('./ExternalSystemAdmin.vue')).default
const { getExternalSystems, createExternalSystem, updateExternalSystem, rotateCallbackSecret } =
  await import('../services/externalApi.js')

const flush = () => new Promise((resolve) => setTimeout(resolve, 0))

/** 一筆「代發授權已開啟」的既有系統（來自 GET /api/admin/external-systems）。 */
const erpRow = {
  id: 'row-1',
  systemId: 'erp',
  systemName: 'ERP 系統',
  apiKey: '***',
  // #21：GET 對已設定的回呼密鑰一律回傳 ***（未設定是 null），
  // 明文只在建立／輪換的回應出現一次。
  callbackSecret: '***',
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
    createExternalSystem.mockResolvedValue({ apiKey: 'sk-new', callbackSecret: 'cs-new' })
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

  it('payload 不得夾帶唯讀欄位（id / apiKey / callbackSecret / lastUsedAt）', async () => {
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
    // #21：row 現在帶著 callbackSecret（***），但它是 READ_ONLY 的狀態欄位，
    // 不該被當成可寫入值送回後端（送 *** 覆蓋是這個欄位最糟的失敗形狀）。
    expect(payload).not.toHaveProperty('callbackSecret')
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
    createExternalSystem.mockResolvedValue({ apiKey: 'sk-new', callbackSecret: 'cs-new' })
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

/**
 * #21：外部系統管理頁的回呼密鑰輪換 UI。
 *
 * <h3>缺陷形狀：能力存在、入口不存在</h3>
 *
 * <p>後端早已完成（建立回傳明文一次、{@code rotate-callback-secret} 回傳新明文
 * 一次、列表／詳情的 {@code callbackSecret} 是 {@code ***} 或 {@code null}），
 * 但管理頁沒有入口。V5 migration 之後既有系統的 callbackSecret 是 null
 * （＝不能回呼），管理員在畫面上既看不出來，也沒有辦法補發 ——
 * 該系統的回呼永遠 401，而唯一能做的事（rotate）不在 UI 上。
 * 這與 #6 的催辦同一個形狀：畫面看起來沒事，實際能力不存在。
 *
 * <h3>兩件必須釘住的事</h3>
 *
 * <ol>
 *   <li><b>端點與 id 正確</b>：輪換是破壞性的（舊密鑰立刻失效），
 *       打錯 id 會讓另一個系統的回呼中斷。端點路徑由
 *       {@code services/externalApi.spec.js} 釘住（本檔 mock 掉 service，
 *       所以路徑寫錯時本檔不會紅 —— 這是刻意的分工）。</li>
 *   <li><b>明文只呈現一次</b>：後端只在 rotate 的回應裡給明文，
 *       列表／詳情永遠是 {@code ***}；前端若把明文留在狀態裡，就多了一個
 *       「重新打開對話框就看得到」的曝露面。關閉對話框即清掉。</li>
 * </ol>
 *
 * <h3>⚠️ 失敗的錯誤訊息由 http.js 攔截器負責（view 不重複 toast）</h3>
 *
 * <p>與 {@code MyApplications.vue} 的催辦同一條規則（見該檔的註解）：
 * 4xx／5xx 的提示集中在 {@code services/http.js}，後端 message 會原樣出現
 * （{@code http.spec.js} 的「優先顯示後端給的 message」）。本檔 mock 掉了
 * service，所以失敗那條驗的是「不顯示明文、不顯示成功、view 不重複顯示錯誤、
 * 不產生未處理的 rejection」，而不是訊息內容本身。
 *
 * <h3>⚠️ 這裡用「完整 mount」＋真的點按鈕</h3>
 *
 * <p>與前兩組不同：本組要驗的正是「畫面上有沒有那一欄、那一顆按鈕，
 * 以及對話框裡有沒有真的渲染出明文」。{@code shallow} 之下 el-table 的 slot
 * 不渲染，按鈕與對話框內容都不存在 —— 那會讓斷言變成對空字串的空斷言。
 * {@code ElMessageBox.confirm} 以 spy 取代（真的對話框在 jsdom 裡沒有人按）。
 *
 * <h3>負向控制組（實測：把實作改壞再跑本檔）</h3>
 *
 * <p>以下計數是<b>當時</b>的檔案規模（24 條）。#21 遺留於文末新增 3 條
 * 「建立時顯示 callbackSecret」測試後，本檔總數為 27 條；新增的 3 條
 * 不經過輪換路徑，不影響下列結果。
 *
 * <ul>
 *   <li>service 的端點改成 {@code rotate-key}：{@code externalApi.spec.js}
 *       紅 1 條（路徑那條），本檔 24 條全綠 —— 端點防線刻意放在 service 層，
 *       本檔 mock 掉 service 所以看不到，這是分工不是漏洞。</li>
 *   <li>拿掉對話框輸入框的 {@code :model-value="newCallbackSecret"}：
 *       本檔紅 1 條（「明文真的渲染在對話框裡」，{@code input.value} 是空的），
 *       其餘 23 條綠 —— 只有 state 有值不算交付。</li>
 *   <li>拿掉對話框的 {@code @closed="clearCallbackSecret"}：本檔紅 1 條
 *       （「明文不得留在元件裡」），其餘 23 條綠。</li>
 *   <li>拿掉列表的「回呼密鑰」欄：本檔紅 2 條（狀態顯示、以及 rotate 後
 *       狀態更新那條的前置條件），其餘 22 條綠。</li>
 * </ul>
 */
describe('ExternalSystemAdmin 的回呼密鑰輪換（#21）', () => {
  beforeEach(() => {
    getExternalSystems.mockClear()
    getExternalSystems.mockResolvedValue([])
    rotateCallbackSecret.mockClear()
    rotateCallbackSecret.mockResolvedValue({ systemId: 'erp', callbackSecret: 'cs-rotated-1' })
    // ElMessage 渲染在 document.body 上（完整 mount 的 wrapper 之外），
    // 不清掉會讓下一條的斷言看到上一條的訊息。
    document.body.innerHTML = ''
  })

  /** 完整 mount：el-table 的 slot 與 el-dialog 的內容才真的會渲染。 */
  async function mountFull(rows = [erpRow]) {
    getExternalSystems.mockResolvedValue(rows)
    const wrapper = mount(Admin, { global: { plugins: [ElementPlus] } })
    await flush()
    return wrapper
  }

  const rotateButton = (wrapper) => {
    const btn = wrapper.findAll('button').find((b) => b.text().includes('輪換回呼密鑰'))
    expect(btn, '畫面上必須有「輪換回呼密鑰」按鈕（否則後面的斷言是空的）').toBeTruthy()
    return btn
  }

  const callbackDialog = (wrapper) =>
    wrapper.findAllComponents({ name: 'ElDialog' })
      .find((d) => d.props('title') === '回呼密鑰')

  // ── 狀態顯示：已設定（***）／未設定（null）──────────────────────

  it('列表必須顯示「回呼密鑰：已設定／未設定」', async () => {
    const wrapper = await mountFull([
      { ...erpRow, callbackSecret: '***' },
      // V5 migration 之後的既有系統：null = 尚未設定 = 不能回呼。
      { ...erpRow, systemId: 'legacy', callbackSecret: null },
    ])

    expect(wrapper.text()).toContain('回呼密鑰')
    expect(wrapper.text(), '已設定的密鑰在列表是 ***，必須翻成人看得懂的狀態').toContain('已設定')
    expect(wrapper.text(), 'null 必須顯示成未設定，不能顯示成空白欄位').toContain('未設定')
  })

  // ── 輪換：端點、id、確認框 ─────────────────────────────────────

  it('按下輪換並確認後，以該列的 systemId 呼叫 rotate-callback-secret', async () => {
    const confirmSpy = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
    const wrapper = await mountFull()

    await rotateButton(wrapper).trigger('click')
    await flush()

    expect(confirmSpy, '輪換會讓舊密鑰立刻失效，必須先確認').toHaveBeenCalledTimes(1)
    expect(rotateCallbackSecret).toHaveBeenCalledTimes(1)
    expect(rotateCallbackSecret).toHaveBeenCalledWith('erp')
  })

  it('取消確認時不得呼叫端點、不得打開明文對話框', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel')
    const wrapper = await mountFull()

    await rotateButton(wrapper).trigger('click')
    await flush()

    expect(rotateCallbackSecret).not.toHaveBeenCalled()
    expect(state(wrapper).showCallbackSecret).toBe(false)
  })

  // ── 成功：明文只呈現一次 ───────────────────────────────────────

  it('輪換成功：明文真的渲染在對話框裡，並提醒「僅顯示一次」', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
    rotateCallbackSecret.mockResolvedValue({ systemId: 'erp', callbackSecret: 'cs-rotated-1' })
    const wrapper = await mountFull()

    await rotateButton(wrapper).trigger('click')
    await flush()
    await flush()

    const s = state(wrapper)
    expect(s.showCallbackSecret).toBe(true)
    expect(s.newCallbackSecret).toBe('cs-rotated-1')

    // ⚠️ 明文在 <input> 的 value 裡，不在 textContent 裡 ——
    // 只斷 wrapper.text() 會是一條永遠不會紅的空斷言。
    const dialog = callbackDialog(wrapper)
    expect(dialog, '必須存在「回呼密鑰」對話框').toBeTruthy()
    const input = dialog.find('input')
    expect(input.exists(), '明文必須真的渲染出來（只有 state 有值不算交付）').toBe(true)
    expect(input.element.value).toBe('cs-rotated-1')
    expect(dialog.text(), '必須明確提醒只顯示一次、要立刻保存').toContain('僅顯示一次')
  })

  it('輪換成功後狀態欄必須跟著更新（legacy 由未設定變已設定）', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
    // 第一次 GET：legacy 尚未設定；rotate 後重載：已設定。
    getExternalSystems
      .mockResolvedValueOnce([{ ...erpRow, systemId: 'legacy', callbackSecret: null }])
      .mockResolvedValueOnce([{ ...erpRow, systemId: 'legacy', callbackSecret: '***' }])
    const wrapper = mount(Admin, { global: { plugins: [ElementPlus] } })
    await flush()

    expect(wrapper.text(), '前置條件：rotate 前必須顯示未設定').toContain('未設定')

    await rotateButton(wrapper).trigger('click')
    await flush()
    await flush()

    expect(getExternalSystems, 'rotate 後必須重載列表（狀態欄來自列表資料）')
      .toHaveBeenCalledTimes(2)
    expect(wrapper.text(), 'rotate 後仍顯示未設定＝畫面說的和事實不同').toContain('已設定')
    expect(wrapper.text()).not.toContain('未設定')
  })

  it('關閉對話框後明文從元件狀態消失（僅顯示一次的另一半）', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
    const wrapper = await mountFull()

    await rotateButton(wrapper).trigger('click')
    await flush()
    await flush()

    const s = state(wrapper)
    expect(s.newCallbackSecret).toBe('cs-rotated-1')

    // 模擬使用者關掉對話框。jsdom 不會跑 CSS transition，所以不觸發真的
    // 關閉動畫，直接對對話框元件發出 el-dialog 的 closed 事件 ——
    // 那正是元件接的 @closed。
    s.showCallbackSecret = false
    await wrapper.vm.$nextTick()
    await callbackDialog(wrapper).vm.$emit('closed')

    expect(s.newCallbackSecret, '明文不得留在元件裡等下一次被打開').toBe('')
    expect(callbackDialog(wrapper).find('input').element.value).toBe('')
  })

  it('明文不得進入建立／編輯表單狀態', async () => {
    const wrapper = await mountFull()
    const s = state(wrapper)

    s.editSystem(erpRow)
    // form 只認 blankForm() 的欄位；callbackSecret 是唯讀的狀態欄位。
    expect(s.form.callbackSecret).toBeUndefined()
  })

  // ── 失敗：不假裝成功 ──────────────────────────────────────────

  it('輪換失敗：不得顯示明文、不得顯示成功，錯誤由 http 攔截器負責', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
    const successSpy = vi.spyOn(ElMessage, 'success')
    const errorSpy = vi.spyOn(ElMessage, 'error')
    rotateCallbackSecret.mockRejectedValue({
      response: { status: 500, data: { message: '伺服器錯誤' } },
    })
    const wrapper = await mountFull()

    await rotateButton(wrapper).trigger('click')
    await flush()
    await flush()

    const s = state(wrapper)
    expect(rotateCallbackSecret).toHaveBeenCalledWith('erp')
    expect(s.showCallbackSecret, '失敗時不得打開一次性明文對話框').toBe(false)
    expect(s.newCallbackSecret).toBe('')
    expect(successSpy, '失敗不得顯示成功（假成功是本專案最優先消滅的形狀）')
      .not.toHaveBeenCalled()
    // 錯誤訊息由 services/http.js 的攔截器顯示；view 若再顯示一次會變成
    // 兩個 toast（與 MyApplications.vue 的催辦同一條規則）。
    expect(errorSpy).not.toHaveBeenCalled()
  })
})

/**
 * #21 遺留：建立外部系統時必須同時顯示 {@code callbackSecret}。
 *
 * <h3>缺陷形狀：能力存在、入口只開了一半</h3>
 *
 * <p>後端建立外部系統的回應同時帶回 {@code apiKey} 與 {@code callbackSecret}
 * 兩把明文（列表／詳情永遠是 {@code ***} 或 {@code null}），但管理頁只把
 * {@code apiKey} 放進一次性對話框。後果是：新建立的外部系統拿不到回呼密鑰，
 * 它的回呼永遠 401，而唯一的補救是按「輪換回呼密鑰」——一顆會讓舊密鑰
 * 立刻失效的破壞性按鈕。正常流程被迫從破壞性操作開始，這與 #21 輪換 UI
 * 想解決的「能力存在、入口不存在」是同一個形狀，只是缺在建立路徑。
 *
 * <h3>兩件必須釘住的事</h3>
 *
 * <ol>
 *   <li><b>明文真的渲染出來</b>：只有 state 有值不算交付（與輪換那組
 *       同一條教訓 —— 拿掉 {@code :model-value} 時 state 斷言照樣綠）。</li>
 *   <li><b>「僅顯示一次」的另一半</b>：關閉對話框後 callbackSecret
 *       必須從元件狀態消失，不留下「重新打開就看到」的曝露面。</li>
 * </ol>
 *
 * <h3>負向控制組（2026-10-03 實測，3 條）</h3>
 *
 * <ul>
 *   <li>拿掉 {@code submitForm} 的
 *       {@code newCallbackSecret.value = result.callbackSecret || ''}：
 *       <b>2 紅 1 綠</b>。紅的是「明文真的渲染」與「關閉後清掉」
 *       （後者在前置條件就紅），綠的是「後端沒帶欄位時只有 API Key」。</li>
 *   <li>拿掉「密鑰資訊」對話框的 {@code @closed="clearCallbackSecret"}：
 *       <b>1 紅 2 綠</b>。紅的是「關閉後明文從元件狀態消失」。</li>
 * </ul>
 *
 * <h3>⚠️ 這裡用「完整 mount」</h3>
 *
 * <p>要驗的正是對話框裡有沒有真的渲染出兩把明文；{@code shallow} 之下
 * {@code el-dialog} 的內容不會渲染，斷言會變成對空字串的空斷言。
 * 建立流程直接呼叫 {@code submitForm}（setupState），與按鈕綁定的是
 * 同一個函式；對話框以 {@code findAllComponents} 依標題定位。
 */
describe('ExternalSystemAdmin 建立時的一次性密鑰（#21 遺留）', () => {
  beforeEach(() => {
    getExternalSystems.mockClear()
    getExternalSystems.mockResolvedValue([])
    createExternalSystem.mockClear()
    createExternalSystem.mockResolvedValue({
      systemId: 'brand-new', apiKey: 'sk-created', callbackSecret: 'cs-created',
    })
    document.body.innerHTML = ''
  })

  async function mountFull() {
    const wrapper = mount(Admin, { global: { plugins: [ElementPlus] } })
    await flush()
    return wrapper
  }

  /** 建立一張新系統並等到一次性對話框出現。 */
  async function createSystem(wrapper) {
    const s = state(wrapper)
    s.form.systemId = 'brand-new'
    s.form.systemName = '新系統'
    await s.submitForm()
    await flush()
    return s
  }

  const secretDialog = (wrapper) =>
    wrapper.findAllComponents({ name: 'ElDialog' })
      .find((d) => d.props('title') === '密鑰資訊')

  it('建立成功後對話框同時渲染 apiKey 與 callbackSecret 的明文', async () => {
    const wrapper = await mountFull()
    const s = await createSystem(wrapper)

    expect(s.showKey).toBe(true)
    expect(s.newApiKey).toBe('sk-created')
    expect(s.newCallbackSecret,
      'callbackSecret 明文必須進到一次性對話框的狀態').toBe('cs-created')

    const dialog = secretDialog(wrapper)
    expect(dialog, '必須存在顯示建立結果的密鑰對話框').toBeTruthy()
    // ⚠️ 明文在 <input> 的 value 裡，不在 textContent 裡 ——
    // 只斷 wrapper.text() 會是一條永遠不會紅的空斷言。
    const values = dialog.findAll('input').map((i) => i.element.value)
    expect(values, 'API Key 的明文').toContain('sk-created')
    expect(values, '回呼密鑰的明文（少了它新系統的回呼永遠 401）').toContain('cs-created')
    expect(dialog.text(), '兩把密鑰都必須提醒僅顯示一次').toContain('僅顯示一次')
  })

  it('關閉對話框後 callbackSecret 從元件狀態消失（僅顯示一次的另一半）', async () => {
    const wrapper = await mountFull()
    const s = await createSystem(wrapper)
    expect(s.newCallbackSecret).toBe('cs-created')

    // 與輪換那組同一種模擬方式：jsdom 不跑 CSS transition，
    // 直接對對話框元件發出 el-dialog 的 closed 事件（元件接的 @closed）。
    s.showKey = false
    await wrapper.vm.$nextTick()
    await secretDialog(wrapper).vm.$emit('closed')

    expect(s.newCallbackSecret, '明文不得留在元件裡等下一次被打開').toBe('')
    const values = secretDialog(wrapper).findAll('input').map((i) => i.element.value)
    expect(values).not.toContain('cs-created')
  })

  it('後端沒帶 callbackSecret 時不渲染空白欄位（只有 API Key）', async () => {
    createExternalSystem.mockResolvedValue({ systemId: 'brand-new', apiKey: 'sk-only' })
    const wrapper = await mountFull()
    const s = await createSystem(wrapper)

    expect(s.newCallbackSecret).toBe('')
    const values = secretDialog(wrapper).findAll('input').map((i) => i.element.value)
    expect(values).toContain('sk-only')
    expect(values, '沒有 callbackSecret 就不該多一個空欄位').toHaveLength(1)
  })
})
