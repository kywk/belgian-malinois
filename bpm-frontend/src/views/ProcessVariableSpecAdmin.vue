<template>
  <div style="padding: 20px">
    <el-page-header @back="$router.back()" :content="`流程變數規格 — ${processKey}`" />

    <el-table :data="specs" stripe style="margin-top:16px">
      <el-table-column label="變數名稱（必填）" min-width="150">
        <template #default="{ row }">
          <el-input
            v-model="row.variableName"
            size="small"
            placeholder="外部系統要塞進流程的 key，不可空白"
          />
        </template>
      </el-table-column>
      <el-table-column label="型別" width="130">
        <template #default="{ row }">
          <el-select v-model="row.variableType" size="small">
            <el-option v-for="t in types" :key="t" :value="t" :label="t" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="必填" width="70">
        <template #default="{ row }">
          <el-checkbox v-model="row.required" />
        </template>
      </el-table-column>
      <el-table-column label="說明" min-width="180">
        <template #default="{ row }">
          <el-input v-model="row.description" size="small" />
        </template>
      </el-table-column>
      <el-table-column label="範例" width="140">
        <template #default="{ row }">
          <el-input v-model="row.example" size="small" />
        </template>
      </el-table-column>
      <el-table-column label="" width="70">
        <template #default="{ $index }">
          <el-button size="small" type="danger" @click="specs.splice($index, 1)">刪除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div style="margin-top:12px;display:flex;gap:8px">
      <el-button @click="addRow">新增行</el-button>
      <el-button type="primary" @click="save">儲存</el-button>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getVariableSpec, saveVariableSpec } from '../services/externalApi.js'

const route = useRoute()
const processKey = route.params.key
const specs = ref([])
const types = ['string', 'number', 'date', 'boolean']

/**
 * 新增一列空白變數。
 *
 * ⚠️ 空白 `variableName` 是**既有行為**，這裡仍然刻意不改（#87-2 重新確認過）。
 *
 * #87 留下的顧慮沒有因為政策改變而消失，所以**不自動填名字**：
 *
 * 1. **自動命名等於替使用者決定外部系統要塞進流程的 key。**
 *    `variableName` 就是外部系統要塞進流程的 key（spec §8.5），
 *    猜錯了之後使用者不會發現，只會在串接時莫名其妙接不到值。
 *    這是**靜默**的失敗模式 —— 畫面上儲存成功、稽核上有一筆 replace，
 *    而真正壞掉的是三個月後的某一筆外部發起。
 * 2. **`onMounted` 在沒有資料時本來就會補一列。**
 *    自動命名等於「沒設定過的流程」也會莫名其妙多出一個變數，
 *    而那比多一列空白更難察覺（空白名至少看得出來沒填）。
 *
 * 政策改成「空白名要擋」之後，這裡的空白名稱不再是「靜靜存進資料庫」，
 * 而是會被 {@link firstProblem} 在送出前擋下，並且**指名是第幾列**。
 * 也就是說 (a) 自動命名想解決的問題（按了儲存卻得到一個沒有指引的錯誤）
 * 是用「把規則講清楚」解決的，不是用「替使用者決定」解決的。
 *
 * （第三個選項 —— 按下去就開啟編輯並 focus —— 也刻意不做：
 *  它需要把 ref 接到 Element Plus 表格的儲存格元件上，而表格是虛擬化的，
 *  「聚焦哪一列」在排序與編輯後並不穩定；它解決的是手的方向，
 *  而不是「使用者還不知道要給這個 key 什麼名字」。）
 */
function addRow() {
  specs.value.push({ variableName: '', variableType: 'string', required: false, description: '', example: '' })
}

/**
 * 「資料庫認得出來是同一個名字」的比較鍵。
 *
 * ⚠️ 這是後端 `dbComparisonKey` 的**子集**，而且必須是子集：
 * 跨語言沒辦法只維護一份，但方向是固定的 —— **前端不得擋掉後端會接受的資料**
 * （那是使用者改了名字卻存不進去的無意義阻擋）。
 * 刻意不做全形半形折疊與 NFC/NFD 標準化：後端做得更完整，
 * 漏擋只是多跑一趟（後端會回 400 並指名是哪個名字），誤擋則是使用者改了名字
 * 卻仍然存不進去。
 *
 * 只 strip 尾端的半形空白（U+0020）：TAB、換行、NBSP 在資料庫裡都**不是**
 * 空白（ANSI padding 只忽略尾端 U+0020），用 `trimEnd()` 會把它們算成
 * 空白 —— 那會把後端接受的名稱擋掉。
 * `toUpperCase()` 不像 Java 那樣受預設 locale 影響（那是 `toLocaleUpperCase`）。
 *
 * @param {unknown} raw 畫面上的 `variableName`（可能是 undefined／null）
 * @return {string} 空字串代表「資料庫分不出這個名字與空字串的差別」
 */
function comparisonKey(raw) {
  return String(raw ?? '').replace(/ +$/, '').toUpperCase()
}

/**
 * 找出第一個擋住送出的問題；沒問題回 null。
 *
 * <h3>為什麼前端也要擋（後端 #87／#87-2 已經會回 400）</h3>
 *
 * 後端 `ProcessVariableSpecController.batchSave` 現在會驗「每一列都有名字」
 * 與「同一批內 `variableName` 不得重複」並回 400，所以這裡**不是安全邊界**，
 * 擋掉錯誤的責任在後端。這裡擋的理由只有一個：
 * <b>讓錯誤在使用者還看得到畫面的時候就以人看得懂的形式出現</b>。
 * 不擋的話，使用者看到的是伺服器回的一句錯誤訊息（或舊版的「伺服器錯誤 (500)」），
 * 他能做的事只有重試，而重試永遠不會成功（payload 沒變，結果就不會變）。
 *
 * <h3>空白先於重複，順序與後端一致</h3>
 *
 * 「有兩列都沒填名字」比「某兩列撞名」更基本：撞名可能是使用者自己造成的，
 * 而空白名多半是剛按了「新增行」還沒填。順序與後端
 * `requireUsableVariableNames` 一致，兩邊給使用者的第一句話不會互相矛盾。
 *
 * <h3>⚠️ 這份規則刻意是後端規則的<b>子集</b>，不是同一份</h3>
 *
 * 跨語言沒有辦法只維護一份，但方向必須固定：<b>前端不得擋掉後端會接受的資料</b>。
 * 所以這裡只做三條最容易打錯、且後端<b>一定</b>也判成同樣結果的規則：
 * <ul>
 *   <li>沒有名字（null／空字串／只有空白字元）</li>
 *   <li>尾端空白（MSSQL 的 ANSI padding，只認 U+0020）</li>
 *   <li>大小寫（欄位定序 SQL_Latin1_General_CP1_CI_AS 的 CI）</li>
 * </ul>
 * 全形／半形、NFC／NFD 那些後端做得更完整，這裡<b>不做</b>。
 *
 * @param rows 畫面上的規格列
 * @return 可以直接顯示給使用者的訊息；沒有問題時回 null
 */
function firstProblem(rows) {
  // ① 沒有名字。後端現在回 400，所以這一列在送出前就說清楚是第幾列沒填 ——
  //    管理頁是一張可編輯的表格，「你送錯了」等於把比對的工作丟回去給使用者。
  for (let i = 0; i < rows.length; i++) {
    if (comparisonKey(rows[i].variableName) !== '') continue
    return `第 ${i + 1} 列的變數名稱是空白。`
      + '變數名稱是外部系統要塞進流程的 key，空白名永遠比對不到任何值，請填上名稱再儲存'
  }
  // ② 重複。走到這裡每一列都有名字了，所以 key 不可能是空字串 ——
  //    空白重複已經被 ① 擋掉且訊息不同（那個形狀的修法是兩列都命名，
  //    不是「把其中一列改名」，講錯等於給錯指引）。
  const seen = new Set()
  for (const row of rows) {
    const raw = row.variableName ?? ''
    const key = comparisonKey(raw)
    if (seen.has(key)) {
      return `變數名稱重複：${raw}`
    }
    seen.add(key)
  }
  return null
}

async function save() {
  const problem = firstProblem(specs.value)
  if (problem) {
    ElMessage.warning(problem)
    return
  }
  try {
    await saveVariableSpec(processKey, specs.value)
    ElMessage.success('已儲存')
  } catch {
    // 訊息已由 services/http.js 的 response interceptor 統一顯示（含後端
    // 400 的指名訊息），這裡只把 rejection 吃掉 —— 沒有 catch 的話
    // 「後端回 400」會變成 console 裡的 unhandled rejection，
    // 那是除錯時的噪音，會讓真正要看的錯誤訊息被蓋掉。
  }
}

onMounted(async () => {
  specs.value = await getVariableSpec(processKey)
  if (!specs.value.length) addRow()
})
</script>
