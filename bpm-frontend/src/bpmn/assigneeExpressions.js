/**
 * 設計器產生的 BPMN 指派運算式 —— 與後端的契約（security-audit P2-6）。
 *
 * 為什麼抽成獨立模組：這些字串是設計器與 Flowable 引擎之間唯一的介面。
 * 寫錯的後果不在前端（畫面完全正常），而是在使用者送出表單、引擎求值的那一刻。
 * 抽出來之後可以在沒有 preact／properties-panel 環境的情況下直接測，
 * 並且可以拿後端的 EL 白名單來比對 —— 見 assigneeExpressions.spec.js。
 *
 * ⚠️ 新增 bean 呼叫時，後端 BpmnLintService.EL_WHITELIST 與
 * FlowableConfig.setBeans() 都必須同步加上，否則：
 *   - 白名單沒加 → 部署被 lint 擋下（安全，但業務人員白做一場）
 *   - setBeans 沒加 → 部署通過，使用者送出時才爆
 */

export const ASSIGNEE_TYPES = [
  { value: 'specific', label: '特定人員' },
  { value: 'manager1', label: '直屬主管（一階）' },
  { value: 'managerN', label: '直屬主管（N階）' },
  { value: 'dynamicManager', label: '動態：第 N 階主管（含代理人）' },
  { value: 'firstAvailable', label: '動態：首位可受理的權限持有人（含代理人）' },
  { value: 'managerWithPermission', label: '動態：具指定權限的最近主管（含代理人）' },
  { value: 'permission', label: '特定權限（候選人認領）' },
  { value: 'department', label: '特定單位（指定代碼）' },
  { value: 'ownDept', label: '發起人所屬單位' }
]

/** 一階主管。 */
export const directManagerExpr = () => '${orgService.getDirectManager(initiator)}'

/**
 * 第 N 階主管。
 *
 * ⚠️ 不可改回 '${orgService.getManagerChain(initiator, N)[N-1]}'。
 *
 * JUEL 對越界索引不拋例外，而是回 null（後端已實測）。於是任務建立成功、
 * 案件存在，但 assignee 為空且沒有候選群組 —— 這個任務對所有人都不可見，
 * 沒有人收到通知也沒有人能認領，永遠卡在引擎裡。
 *
 * 而離組織頂端只差一階的發起人就會越界，N=2 正是預設值。
 * getManagerAtLevel 永遠不回 null：鏈較短時回最高階並記 warn，
 * 完全沒有主管則拋例外。
 */
export const managerAtLevelExpr = (level) =>
  '${orgService.getManagerAtLevel(initiator, ' + level + ')}'

/** 持有指定權限的人（候選人清單）。 */
export const usersByPermissionExpr = (permCode) =>
  "${permService.getUsersByPermission('" + (permCode || '') + "')}"

/** 發起人所屬單位。這是原本的預設值 ${dept} 想表達的意思。 */
export const ownDepartmentExpr = () => '${orgService.getDeptId(initiator)}'

/**
 * 指定的部門代碼。
 *
 * ⚠️ 回傳的是<b>字面值</b>，不是 '${' + code + '}'。
 *
 * 原本的寫法會把使用者輸入的 dept001 存成 ${dept001} —— 一個不存在的流程
 * 變數參照。這個錯誤部署時抓不到：後端 EL 白名單的 regex 要求「${名稱.」
 * 有點才匹配，所以純變數參照完全不被檢查。部署順利通過，等到使用者送出
 * 表單、引擎求值 candidateGroups 時才拋 Unknown property used in expression。
 *
 * 後端已補上 lint 規則 i（undeclared-variable）擋這種寫法，
 * 但設計器本來就不該產生它。
 */
export const departmentCodeValue = (code) => code || ''

export const DEFAULT_MANAGER_LEVEL = 2

/** 依類型產生要寫入 businessObject 的三個屬性。未指定的一律清空。 */
export function propertiesForType(type) {
  const p = {
    'flowable:assignee': '',
    'flowable:candidateUsers': '',
    'flowable:candidateGroups': ''
  }
  switch (type) {
    case 'manager1':
      p['flowable:assignee'] = directManagerExpr()
      break
    case 'managerN':
      p['flowable:assignee'] = managerAtLevelExpr(DEFAULT_MANAGER_LEVEL)
      break
    case 'dynamicManager':
      p['flowable:assignee'] = dynamicManagerAtLevelExpr(DEFAULT_MANAGER_LEVEL)
      break
    case 'firstAvailable':
      p['flowable:assignee'] = firstAvailableExpr('')
      break
    case 'managerWithPermission':
      p['flowable:assignee'] = managerWithPermissionExpr('')
      break
    case 'permission':
      p['flowable:candidateUsers'] = usersByPermissionExpr('')
      break
    case 'ownDept':
      p['flowable:candidateGroups'] = ownDepartmentExpr()
      break
    case 'specific':
    case 'department':
      // 兩者都等使用者填字面值。沒填的話後端 lint 規則 a
      // （assignee-required）會在部署時擋下來。
      break
  }
  return p
}

/**
 * 從既有屬性反推類型。
 *
 * getManagerChain 是舊圖的寫法，仍要辨識得出來 —— 否則使用者打開舊流程時
 * 看到的是「特定人員」，改不到那個壞掉的運算式。
 */
export function detectAssigneeType({ assignee = '', candidateUsers = '', candidateGroups = '' }) {
  const dynamic = parseDynamicAssignee(assignee)
  if (dynamic && !candidateUsers && !candidateGroups) return dynamic.type
  // 未知或帶有其他參數／來源的動態運算式不得誤認成可編輯欄位。
  if (assignee.includes('dynamicAssignee')) return 'custom'
  if (candidateGroups === ownDepartmentExpr()) return 'ownDept'
  if (candidateGroups) return candidateGroups.includes('${') && !/^\$\{\w+\}$/.test(candidateGroups) ? 'custom' : 'department'
  if (/^\$\{permService\.getUsersByPermission\('[^']*'\)\}$/.test(candidateUsers)) return 'permission'
  if (/^\$\{orgService\.getManagerAtLevel\(initiator,\s*\d+\)\}$/.test(assignee) ||
      /^\$\{orgService\.getManagerChain\(initiator,\s*\d+\)\[\d+\]\}$/.test(assignee)) return 'managerN'
  if (assignee === directManagerExpr()) return 'manager1'
  if ([assignee, candidateUsers, candidateGroups].some(value => value.includes('${'))) return 'custom'
  return 'specific'
}

/** 抽出運算式裡所有被呼叫的 bean 名稱（即 ${名稱. 的部分）。 */
export function referencedBeans(expr) {
  if (!expr) return []
  return [...expr.matchAll(/\$\{(\w+)\./g)].map((m) => m[1])
}

/** JUEL 字串常值只跳脫反斜線與單引號；參數仍是單一權限碼。 */
const quoteElString = value => "'" + String(value ?? '').replace(/\\/g, '\\\\').replace(/'/g, "\\'") + "'"

export function managerLevelError(level) {
  return (typeof level === 'number' || typeof level === 'string') &&
    String(level).trim() !== '' && Number.isInteger(Number(level)) &&
    Number(level) >= 1 && Number(level) <= 2147483647
    ? undefined : '請輸入 1 到 2147483647 的整數階數'
}

export const dynamicManagerAtLevelExpr = level => {
  if (managerLevelError(level)) throw new RangeError(managerLevelError(level))
  return '${dynamicAssignee.managerAtLevel(initiator, ' + Number(level) + ')}'
}

export const firstAvailableExpr = permCode =>
  '${dynamicAssignee.firstAvailable(' + quoteElString(permCode) + ')}'

export const managerWithPermissionExpr = permCode =>
  '${dynamicAssignee.managerWithPermission(initiator, ' + quoteElString(permCode) + ')}'

/** 只解析設計器支援的完整呼叫；其他運算式保持原文，不猜參數或變數來源。 */
export function parseDynamicAssignee(expression = '') {
  if (typeof expression !== 'string') return null
  const manager = expression.match(/^\$\{\s*dynamicAssignee\.managerAtLevel\(\s*initiator\s*,\s*(\d+)\s*\)\s*\}$/)
  if (manager && !managerLevelError(manager[1])) {
    return { type: 'dynamicManager', level: Number(manager[1]) }
  }
  const literal = "'((?:[^'\\\\]|\\\\['\\\\])*)'"
  const first = expression.match(new RegExp('^\\$\\{\\s*dynamicAssignee\\.firstAvailable\\(\\s*' + literal + '\\s*\\)\\s*\\}$'))
  const supervisor = expression.match(new RegExp('^\\$\\{\\s*dynamicAssignee\\.managerWithPermission\\(\\s*initiator\\s*,\\s*' + literal + '\\s*\\)\\s*\\}$'))
  const match = first || supervisor
  return match ? {
    type: first ? 'firstAvailable' : 'managerWithPermission',
    permCode: match[1].replace(/\\(['\\])/g, '$1')
  } : null
}
