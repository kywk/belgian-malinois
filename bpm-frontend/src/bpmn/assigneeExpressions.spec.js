import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import {
  ASSIGNEE_TYPES,
  departmentCodeValue,
  detectAssigneeType,
  managerAtLevelExpr,
  propertiesForType,
  referencedBeans
} from './assigneeExpressions'

/**
 * 設計器產生的運算式（security-audit P2-6）。
 *
 * 這些字串是設計器與 Flowable 引擎之間唯一的介面。寫錯的後果不在前端 ——
 * 畫面完全正常、部署可能也通過 —— 而是在使用者送出表單、引擎求值的那一刻。
 * 所以這裡測的是字串本身，以及它與後端白名單的一致性。
 */
describe('設計器產生的指派運算式', () => {
  it('N 階主管不得對主管鏈直接做索引', () => {
    // 原本產生 ${orgService.getManagerChain(initiator, 2)[1]}。
    // JUEL 對越界索引不拋例外而是回 null（後端已實測）→ 任務建立成功但
    // assignee 為空且無候選群組 → 對所有人都不可見，永遠卡在引擎裡。
    // 而離組織頂端只差一階的發起人就會越界，2 正是預設值。
    const expr = propertiesForType('managerN')['flowable:assignee']

    expect(expr).not.toContain('getManagerChain')
    expect(expr).not.toMatch(/\[\s*\d+\s*\]/)
    expect(expr).toBe('${orgService.getManagerAtLevel(initiator, 2)}')
  })

  it('改階數時也不得產生索引形式', () => {
    for (const level of [1, 2, 3, 7]) {
      const expr = managerAtLevelExpr(level)
      expect(expr).not.toMatch(/\[\s*\d+\s*\]/)
      expect(expr).toContain('getManagerAtLevel(initiator, ' + level + ')')
    }
  })

  it('部門代碼必須是字面值，不可包成變數參照', () => {
    // 原本 setValue 是 '${' + v + '}'，所以使用者輸入 dept001 會被存成
    // ${dept001} —— 一個不存在的流程變數。後端 EL 白名單的 regex 要求
    // 「${名稱.」有點才匹配，所以這種寫法完全不被檢查，部署會通過，
    // 等到使用者送出表單時才拋 Unknown property used in expression。
    expect(departmentCodeValue('dept001')).toBe('dept001')
    expect(departmentCodeValue('dept001')).not.toContain('$')
    expect(departmentCodeValue('')).toBe('')
  })

  it('「特定單位」的預設值必須留空，不可是 ${dept}', () => {
    // ${dept} 是原本的預設值，而沒有任何地方設定這個流程變數。
    // 留空的話後端 lint 規則 a（assignee-required）會在部署時擋下來 ——
    // 那是正確的失敗時機。
    expect(propertiesForType('department')['flowable:candidateGroups']).toBe('')
  })

  it('「發起人所屬單位」用的是真實存在的 bean 方法', () => {
    expect(propertiesForType('ownDept')['flowable:candidateGroups'])
      .toBe('${orgService.getDeptId(initiator)}')
  })

  it('任何類型都不得產生 callbackService', () => {
    // 後端完全沒有這個 bean，也不在 EL 白名單上。原本的「特定 Callback」
    // 選項讓業務人員填好名稱、按部署，只會拿到白名單錯誤。
    for (const { value } of ASSIGNEE_TYPES) {
      const props = propertiesForType(value)
      for (const expr of Object.values(props)) {
        expect(expr).not.toContain('callbackService')
      }
    }
    expect(ASSIGNEE_TYPES.map((t) => t.value)).not.toContain('callback')
  })

  it('切換類型時必須清空其他兩個屬性', () => {
    // 三個屬性同時有值時，Flowable 的行為取決於解析順序 ——
    // 設計器不該產生那種狀態。
    for (const { value } of ASSIGNEE_TYPES) {
      const props = propertiesForType(value)
      const nonEmpty = Object.values(props).filter((v) => v !== '')
      expect(nonEmpty.length, `類型 ${value} 產生了 ${nonEmpty.length} 個非空屬性`)
        .toBeLessThanOrEqual(1)
    }
  })
})

describe('從既有屬性反推類型', () => {
  it('舊圖的 getManagerChain 仍要被辨識為 N 階主管', () => {
    // 否則使用者打開舊流程看到的是「特定人員」，改不到那個壞掉的運算式。
    expect(detectAssigneeType({
      assignee: '${orgService.getManagerChain(initiator, 2)[1]}'
    })).toBe('managerN')
  })

  it('修正後的形式也要被辨識', () => {
    expect(detectAssigneeType({
      assignee: '${orgService.getManagerAtLevel(initiator, 3)}'
    })).toBe('managerN')
  })

  it('發起人所屬單位與指定代碼必須區分開', () => {
    expect(detectAssigneeType({ candidateGroups: '${orgService.getDeptId(initiator)}' }))
      .toBe('ownDept')
    expect(detectAssigneeType({ candidateGroups: 'dept001' })).toBe('department')
  })

  it('每個類型產生的屬性都必須反推回同一個類型', () => {
    // 這是往返一致性：設定後重新開啟屬性面板，看到的類型必須是同一個。
    // 不一致的話使用者改一次就會跳到別的類型，屬性被清空。
    for (const { value } of ASSIGNEE_TYPES) {
      if (value === 'specific' || value === 'department') continue // 兩者都等使用者填值
      const p = propertiesForType(value)
      expect(detectAssigneeType({
        assignee: p['flowable:assignee'],
        candidateUsers: p['flowable:candidateUsers'],
        candidateGroups: p['flowable:candidateGroups']
      }), `類型 ${value} 反推不回自己`).toBe(value)
    }
  })
})

/**
 * 跨語言的漂移守衛。
 *
 * 設計器產生的每個 bean 名稱都必須在後端的 EL 白名單上，否則部署會被擋下 ——
 * 業務人員選好審核對象、填好值、按部署，才發現這個選項根本用不了。
 * callbackService 就是這樣存在了很久。
 *
 * 直接讀後端原始碼比對，而不是在前端複製一份清單 ——
 * 複製的清單本身就會漂移。
 */
describe('與後端 EL 白名單的一致性', () => {
  const backendWhitelist = () => {
    const java = readFileSync(
      resolve(__dirname, '../../../bpm-core/src/main/java/com/bpm/core/lint/BpmnLintService.java'),
      'utf8'
    )
    const block = java.match(/EL_WHITELIST\s*=\s*Set\.of\(([\s\S]*?)\);/)
    expect(block, '在後端找不到 EL_WHITELIST —— 可能被改名或搬家了').toBeTruthy()
    return [...block[1].matchAll(/"(\w+)"/g)].map((m) => m[1])
  }

  it('後端白名單必須讀得到且非空', () => {
    // 若讀不到就退化成空門測試，所以先斷言它有內容。
    expect(backendWhitelist().length).toBeGreaterThan(0)
  })

  it('設計器產生的每個 bean 都必須在後端白名單上', () => {
    const allowed = backendWhitelist()
    for (const { value } of ASSIGNEE_TYPES) {
      const props = propertiesForType(value)
      for (const expr of Object.values(props)) {
        for (const bean of referencedBeans(expr)) {
          expect(allowed, `類型 ${value} 用了 ${bean}，但後端白名單只有 ${allowed}`)
            .toContain(bean)
        }
      }
    }
  })

  it('改階數與填權限碼產生的 bean 也要在白名單上', () => {
    const allowed = backendWhitelist()
    expect(referencedBeans(managerAtLevelExpr(3)).every((b) => allowed.includes(b))).toBe(true)
  })
})
