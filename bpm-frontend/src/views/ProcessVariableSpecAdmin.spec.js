import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'

/**
 * #87：管理頁「按兩次新增行再按儲存」必定 500。
 * #87-2：空白與 null 的名稱後端改成 400，前端必須在送出前就說清楚。
 *
 * <h3>這個頁面怎麼觸發後端的 500</h3>
 *
 * <p>{@code ProcessVariableSpecAdmin.vue} 的 {@code addRow()} 產生的
 * {@code variableName} 是<b>空字串</b>，而 {@code onMounted} 在沒有資料時
 * 會自動補一列。使用者於是只要：打開一個還沒有規格的流程 → 按「新增行」
 * → 按「儲存」，畫面上就有<b>兩列空白名稱</b>。
 * 送出後端後，兩筆同名（都是空字串）在資料庫層面必然互相衝突 →
 * {@code uk_bpm_process_variable_spec_key_name} → 500。
 *
 * <p>後端已於 #87 加上輸入驗證（回 400 並指名重複的名字），
 * 所以這組測試<b>不是</b>驗「有沒有擋住後端」——
 * 它驗的是「錯誤有沒有在使用者還看得到畫面的時候就以人看得懂的形式出現」。
 *
 * <h3>政策變更（2026-09-30 使用者裁決）：空白名也要擋</h3>
 *
 * <p>裁決前<b>單一</b>空白名稱後端回 200（真的存進資料庫），所以本檔原本有
 * 一條「放行：只有一列空白名稱時照常送出」把它釘住。
 * 裁決後那條必須消失並翻面 —— 本檔把它改成
 * {@link 空白名稱也必須擋下} 並補上兩條新的對照組。
 * 留著一條會紅的測試等於在說謊。
 *
 * <h3>⚠️ 這組測試防的是「擋太寬」</h3>
 *
 * <p>前端檢查<b>不得</b>擋掉後端會接受的資料。最容易犯的錯是
 * 「用 {@code trim()} 判斷空白」—— 後端判「空白」用的是資料庫的比較鍵
 * （{@code dbComparisonKey} 是不是空字串），而實測 TAB、換行在資料庫裡
 * <b>不是</b>空白（ANSI padding 只忽略尾端 U+0020）。用 {@code trim()} 會把
 * {@code "amount\t"} 這種後端接受的名稱擋掉。
 * 所以 {@link 放行} 那組對照是這支檔案裡最重要的一部分。
 *
 * <h3>元件測試的形狀（沿用既有的單元測試設定）</h3>
 *
 * <p>{@code vitest.config.js} 已經是 jsdom + {@code @vitejs/plugin-vue}，
 * {@code @vue/test-utils} 也在 devDependencies 裡，所以這是<b>沿用</b>
 * 既有基礎設施而不是新增測試框架。註冊 Element Plus 是必要的：
 * 不註冊的話 {@code el-table-column} 的 slot 會被當成一般 slot 渲染，
 * 拿到 {@code undefined} 的 {@code row} 而整個 render 拋錯。
 * 但用 {@code shallow: true} —— slot 不會被渲染，測試因此只關心
 * 「按儲存時送不送得出去」，不去驗 Element Plus 的表格行為。
 * <b>例外</b>是「規則有沒有顯示在畫面上」那條：它要看得到渲染結果，
 * 所以用完整 mount（理由見該條註解）。
 *
 * <h3>⚠️ {@code ElMessage} 渲染在 {@code document.body}，不在 wrapper 內</h3>
 *
 * <p>「錯誤以人看得懂的形式出現」這件事本身是本檔要驗的東西，
 * 所以訊息內容要從 {@code document.body.textContent} 讀 —— 那是使用者
 * 真正看得到的 DOM。每條 {@code beforeEach} 都清掉 body，避免上一條測試
 * 的訊息讓下一條的斷言變成空斷言。
 *
 * <h3>負向控制組：還原成 HEAD 版本的 {@code .vue} 後的實測結果</h3>
 *
 * <p>把 {@code ProcessVariableSpecAdmin.vue} 整份還原成 HEAD（用
 * {@code git show HEAD:<path>} 取出再 {@code command cp} 覆蓋），
 * 測試檔維持新版重跑：<b>10 條中紅 3 條、綠 7 條</b>。
 *
 * <ul>
 *   <li><b>紅（3）</b>：空白名稱仍然被送出（政策形狀）、
 *       空白訊息沒有指名第幾列（缺陷期間是「有兩列都沒有填變數名稱」——
 *       它擋得住，但叫使用者自己去數第幾列）、
 *       畫面上沒有「必填／不可空白」的標示。</li>
 *   <li><b>綠（7）</b>：#87 的三條缺陷形狀（兩列空白／大小寫／尾端空白）
 *       與四條放行對照。這正是它們該有的樣子：缺陷期間這個元件會把
 *       所有東西都送出去，所以放行組當然是綠的，它們的意義是
 *       <b>防止修法擋太寬</b>（例如用 {@code trim()} 判斷空白）。</li>
 * </ul>
 *
 * <p><b>⚠️ 過程中抓到一條自己的空斷言</b>：第一版「規則有沒有顯示在畫面上」
 * 斷的是 {@code wrapper.text()).toContain('必填')}，而表格本來就有一欄
 * 標題叫「必填」（那個 checkbox 欄）—— 所以它在缺陷版本也是綠的。
 * 已改成斷確切的標題文字「變數名稱（必填）」，才真的會紅。
 * 記在這裡是因為「測試會紅」與「測試有效」是兩件事。
 *
 * <p>另外 vitest 會回報 <b>Unhandled Rejection</b> 時代表
 * 「{@code save()} 沒有 try/catch」：那正是「後端回 400 會變成 console 裡的
 * 未處理 rejection」，所以「後端回 400 時不得產生未處理的 rejection」
 * 那一條也是有效的，不是空斷言。
 *
 * <h3>⚠️ 測試透過 {@code wrapper.vm.$.setupState} 直接改表格資料</h3>
 *
 * <p>因為 {@code shallow: true} 之下 {@code el-table} 與 {@code el-input}
 * 都是 stub，沒有可輸入的 DOM。本組測試因此<b>不驗輸入綁定</b>
 * （那是 {@code v-model} 的既定行為），只驗儲存前的那道檢查。
 */

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { key: 'probe-key' } }),
}))

vi.mock('../services/externalApi.js', () => ({
  getVariableSpec: vi.fn(async () => []),
  saveVariableSpec: vi.fn(async () => []),
}))

const Admin = (await import('./ProcessVariableSpecAdmin.vue')).default
const { getVariableSpec, saveVariableSpec } = await import('../services/externalApi.js')

const row = (variableName, extra = {}) => ({
  variableName,
  variableType: 'string',
  required: false,
  description: '',
  example: '',
  ...extra,
})

async function mountAdmin() {
  const wrapper = mount(Admin, { shallow: true, global: { plugins: [ElementPlus] } })
  // onMounted 會 await getVariableSpec 再補一列 —— 等它跑完，
  // 否則「按儲存」的時序會落在 mount 之前。
  await flush()
  return wrapper
}

/** 讓 onMounted 的 await 與 click 之後的 microtask/promise 都跑完。 */
const flush = () => new Promise((resolve) => setTimeout(resolve, 0))

const saveButtons = (wrapper) => wrapper.findAllComponents({ name: 'ElButton' })
const addButtons = (wrapper) => wrapper.findAllComponents({ name: 'ElButton' })

describe('ProcessVariableSpecAdmin 的儲存前檢查（#87／#87-2）', () => {
  beforeEach(() => {
    getVariableSpec.mockClear()
    getVariableSpec.mockResolvedValue([])
    saveVariableSpec.mockClear()
    saveVariableSpec.mockResolvedValue([])
    // ElMessage 渲染在 body 上、且不會自己消失（jsdom 沒有計時器驅動的
    // 動畫結束）。不清掉的話，上一條測試的訊息會讓下一條的
    // document.body.textContent 斷言變成空斷言。
    document.body.innerHTML = ''
  })

  // ── 缺陷形狀：最常見的失敗 ───────────────────────────────────────

  it('開啟頁面後再按一次「新增行」，有兩列空白名稱時不得送出', async () => {
    const wrapper = await mountAdmin()

    // onMounted 在沒有資料時補一列 → 1 列空白
    expect(saveVariableSpec).not.toHaveBeenCalled()
    await addButtons(wrapper)[0].trigger('click')   // 「新增行」→ 2 列空白
    await saveButtons(wrapper)[1].trigger('click')   // 「儲存」
    await flush()

    // 這是缺陷的形狀：兩列空白名稱送出後必然 500（後端已改回 400，
    // 但使用者看到的是沒有指名任何東西的錯誤訊息）。
    expect(saveVariableSpec)
      .toHaveBeenCalledTimes(0)
  })

  it('使用者把名稱填成大小寫不同時不得送出（後端的欄位定序不分大小寫）', async () => {
    const wrapper = await mountAdmin()
    const table = wrapper.vm.$.setupState.specs
    table.splice(0, table.length, row('Amount'), row('amount'))

    await saveButtons(wrapper)[1].trigger('click')
    await flush()

    expect(saveVariableSpec).toHaveBeenCalledTimes(0)
  })

  it('使用者只在尾端多打一個空白時不得送出（MSSQL 的 ANSI padding）', async () => {
    const wrapper = await mountAdmin()
    const table = wrapper.vm.$.setupState.specs
    table.splice(0, table.length, row('amount'), row('amount '))

    await saveButtons(wrapper)[1].trigger('click')
    await flush()

    expect(saveVariableSpec).toHaveBeenCalledTimes(0)
  })

  // ── 非空性：不得擋掉後端會接受的資料 ───────────────────────────

  it('空白名稱也必須擋下（後端已改成 400：空白名永遠比對不到外部系統送來的值）', async () => {
    // ⚠️ 這一條**取代**了 #87 原本的「放行：只有一列空白名稱時照常送出」。
    // 那條測試釘的是裁決前的政策（單一空白名回 200，真的存進了資料庫）。
    // 政策改了，現況就變了 —— 留著一條會紅的測試等於在說謊。
    const wrapper = await mountAdmin()   // 只有 onMounted 補的那 1 列空白

    await saveButtons(wrapper)[1].trigger('click')
    await flush()

    expect(saveVariableSpec,
      '後端現在回 400；前端不擋就是讓使用者去撞一個沒有指名任何東西的錯誤')
      .toHaveBeenCalledTimes(0)
  })

  it('空白名稱的訊息必須指名是第幾列（使用者要能對照畫面）', async () => {
    // 這一條驗的是「錯誤以人看得懂的形式出現」本身：訊息是 ElMessage 渲染到
    // document.body 的，而那正是使用者看得到的 DOM（不是 wrapper 內）。
    // 只說「有空白」等於要求他逐列數表格。
    const wrapper = await mountAdmin()
    const table = wrapper.vm.$.setupState.specs
    table.splice(0, table.length, row('amount'), row('  '), row(''))

    await saveButtons(wrapper)[1].trigger('click')
    await flush()

    const shown = document.body.textContent
    expect(shown, '必須指名第 2 列（第一個沒有名稱的列）').toContain('第 2 列')
    expect(shown, '要講清楚為什麼不行 —— 使用者才不會覺得是系統在找麻煩')
      .toContain('外部系統要塞進流程的 key')
    expect(saveVariableSpec).toHaveBeenCalledTimes(0)
  })

  it('畫面上必須看得出「變數名稱必填、不可空白」（不是只有按儲存才知道）', async () => {
    // 政策改成擋空白名之後，只靠儲存時才跳出來的訊息是不夠的：
    // 使用者要能在**按下去之前**就看出這一欄不能留空。
    // ⚠️ 這一條用完整 mount：shallow 之下 el-table 整個被 stub，
    // 連欄位標題都不會渲染，那樣斷言到的會是空字串。
    getVariableSpec.mockResolvedValue([{
      id: 'row-1',
      processDefinitionKey: 'probe-key',
      variableName: 'amount',
      variableType: 'string',
      required: true,
      description: '',
      example: '',
    }])
    const wrapper = mount(Admin, { global: { plugins: [ElementPlus] } })
    await flush()

    // ⚠️ 斷「變數名稱（必填）」而不是斷「必填」：表格本來就有一欄叫「必填」
    // （那個 checkbox 欄），斷那個字等於這條斷言在缺陷版本也會是綠的。
    expect(wrapper.text(), '欄位標題必須寫出這一欄是必填').toContain('變數名稱（必填）')
    expect(wrapper.find('input[placeholder="外部系統要塞進流程的 key，不可空白"]').exists(),
      '輸入框自己也要寫出「不可空白」—— 標題離輸入框還隔著一格，'
      + '只改標題等於把規則講在別的地方').toBe(true)
  })

  it('放行：名稱全部不同就照常送出', async () => {
    const wrapper = await mountAdmin()
    const table = wrapper.vm.$.setupState.specs
    table.splice(0, table.length, row('amount'), row('reason'), row('approver'))

    await saveButtons(wrapper)[1].trigger('click')
    await flush()

    expect(saveVariableSpec)
      .toHaveBeenCalledTimes(1)
      .toHaveBeenCalledWith('probe-key', [
        row('amount'), row('reason'), row('approver'),
      ])
  })

  it('放行：只有一列、而且名字填好了就照常送出（不得把整個端點都擋掉）', async () => {
    // 少這一條，前面的 400 可能來自「擋掉所有送出」而不是檢查本身。
    // 而這個頁面最常見的操作就是「照著上一次的設定再存一次」。
    const wrapper = await mountAdmin()
    const table = wrapper.vm.$.setupState.specs
    table.splice(0, table.length, row('amount'))

    await saveButtons(wrapper)[1].trigger('click')
    await flush()

    expect(saveVariableSpec).toHaveBeenCalledTimes(1)
  })

  it('放行：資料庫分得開的名稱不得被前端擋掉', async () => {
    // 前端檢查刻意是後端規則的**子集**，這幾組都是後端會接受的：
    //   café / cafe  → 定序 AS（分重音），是兩個不同的變數
    //   ① / 1        → 相容但不等價，資料庫分得開
    //   " amount" / "amount" → 只有**尾端**空白會被忽略，前導空白是有意義的
    //   "amount\t" / "amount" → TAB 不是 ANSI padding，資料庫分得開
    //   "\t" / "amount" → **整個名稱就是一個 TAB**：後端判「空白」用的是
    //     比較鍵是不是空字串（不是 isBlank()），所以後端接受、前端必須放行。
    //     用 trim() 的實作會擋掉這一條 —— 那就是擋掉後端會接受的資料。
    // 前端多擋任何一個都是「使用者改了名字卻存不進去」。
    const cases = [
      ['café', 'cafe'],
      ['①', '1'],
      [' amount', 'amount'],
      ['amount\t', 'amount'],
      ['\t', 'amount'],
    ]
    for (const [a, b] of cases) {
      const wrapper = await mountAdmin()
      const table = wrapper.vm.$.setupState.specs
      table.splice(0, table.length, row(a), row(b))
      await saveButtons(wrapper)[1].trigger('click')
      await flush()
      expect(saveVariableSpec, `前端擋掉了後端會接受的組合：${JSON.stringify([a, b])}`)
        .toHaveBeenCalledTimes(1)
      wrapper.unmount()
      saveVariableSpec.mockClear()
    }
  })

  it('後端回 400 時不得產生未處理的 rejection', async () => {
    // 沒有 try/catch 的話，save() 的 rejection 會變成 console 裡的
    // unhandled rejection —— 而 vitest 會把它算成這個測試檔的錯誤。
    // 訊息本身由 services/http.js 統一顯示（這裡 mock 掉了 service，
    // 所以本測試只驗「不會炸」）。
    const wrapper = await mountAdmin()
    const table = wrapper.vm.$.setupState.specs
    table.splice(0, table.length, row('amount'), row('reason'))
    saveVariableSpec.mockRejectedValueOnce(
      Object.assign(new Error('Request failed'), {
        response: { status: 400, data: { message: '同一個流程底下變數名稱不得重複: amount' } },
      }),
    )

    // trigger() 本身不會因為 handler 內部的 rejection 而 reject ——
    // 未處理的 rejection 是由 vitest 在測試檔層級報錯的，
    // 所以「這個測試檔跑得完」本身就是那條斷言。
    await saveButtons(wrapper)[1].trigger('click')
    await flush()

    expect(saveVariableSpec).toHaveBeenCalledTimes(1)
  })
})
