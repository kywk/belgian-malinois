import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'

/**
 * #87：管理頁「按兩次新增行再按儲存」必定 500。
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
 * <h3>⚠️ 這組測試防的是「擋太寬」</h3>
 *
 * <p>前端檢查<b>不得</b>擋掉後端會接受的資料。最容易犯的錯是
 * 「順便擋掉空白名稱」或「用 {@code trim()} 去除前後空白再比對」——
 * 後者會把尾端是 TAB 的合法名稱誤判成重複。
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
 *
 * <h3>負向控制組：還原成 HEAD 版本後 7 條中紅 3 條、綠 4 條（exit=1）</h3>
 *
 * <p>紅的是三條缺陷形狀（兩列空白／大小寫不同／尾端多空白）；
 * 綠的是四條「放行」對照 —— 缺陷版本會把所有東西都送出去，
 * 所以那四條當然是綠的，它們存在的意義是<b>防止修法擋太寬</b>。
 *
 * <p>另外 vitest 會回報 1 個 <b>Unhandled Rejection</b>：那正是
 * 「{@code save()} 沒有 try/catch 時，後端回 400 會變成 console 裡的
 * 未處理 rejection」。所以「後端回 400 時不得產生未處理的 rejection」
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

describe('ProcessVariableSpecAdmin 的儲存前檢查（#87）', () => {
  beforeEach(() => {
    getVariableSpec.mockClear()
    getVariableSpec.mockResolvedValue([])
    saveVariableSpec.mockClear()
    saveVariableSpec.mockResolvedValue([])
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

  it('放行：只有一列空白名稱時照常送出（空白名稱是否該擋是政策性決定，不由前端代做）', async () => {
    // ⚠️ 這一條與缺陷形狀的那一條是成組的。少了它，一個「把所有空白名稱
    // 都擋掉」的實作也能讓上面的測試全綠 —— 但那等於前端單方面
    // 改變了後端的現行政策（單一空白名稱目前回 200）。
    const wrapper = await mountAdmin()   // 只有 onMounted 補的那 1 列空白

    await saveButtons(wrapper)[1].trigger('click')
    await flush()

    expect(saveVariableSpec).toHaveBeenCalledTimes(1)
  })

  it('放行：資料庫分得開的名稱不得被前端擋掉', async () => {
    // 前端檢查刻意是後端規則的**子集**，這三組都是後端會接受的：
    //   café / cafe  → 定序 AS（分重音），是兩個不同的變數
    //   ① / 1        → 相容但不等價，資料庫分得開
    //   " amount" / "amount" → 只有**尾端**空白會被忽略，前導空白是有意義的
    // 前端多擋任何一個都是「使用者改了名字卻存不進去」。
    const cases = [
      ['café', 'cafe'],
      ['①', '1'],
      [' amount', 'amount'],
      ['amount\t', 'amount'],   // TAB 不是空白，資料庫分得開
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
