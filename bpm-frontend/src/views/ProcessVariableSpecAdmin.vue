<template>
  <div style="padding: 20px">
    <el-page-header @back="$router.back()" :content="`流程變數規格 — ${processKey}`" />

    <el-table :data="specs" stripe style="margin-top:16px">
      <el-table-column label="變數名稱" min-width="150">
        <template #default="{ row }">
          <el-input v-model="row.variableName" size="small" />
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
 * ⚠️ 空白 `variableName` 是**既有行為**，這裡刻意不改。
 *
 * 改成自動填入流水號（v1、v2…）聽起來比較友善，但那是**替使用者決定變數名**，
 * 而變數名就是外部系統要塞進流程的 key（spec §8.5），猜錯了之後
 * 使用者不會發現，只會在串接時莫名其妙接不到值。
 * 而且 `onMounted` 在沒有資料時本來就會補一列，自動命名等於
 * 「沒設定的流程」也會莫名其妙多出一個變數。
 *
 * 後果就是：本頁最容易發生的失敗是「同一批送出兩列空白名稱」
 * （按兩次「新增行」，或存過一次之後再加一列）——
 * 那是後端 #87 修掉的那個 500。修法在 {@link firstProblem}，
 * 不在這裡。
 */
function addRow() {
  specs.value.push({ variableName: '', variableType: 'string', required: false, description: '', example: '' })
}

/**
 * 找出第一個擋住送出的問題；沒問題回 null。
 *
 * <h3>為什麼前端也要擋（後端 #87 已經會回 400）</h3>
 *
 * 後端 `ProcessVariableSpecController.batchSave` 現在會驗「同一批內
 * `variableName` 不得重複」並回 400，所以這裡**不是安全邊界**，
 * 擋掉錯誤的責任在後端。這裡擋的理由只有一個：
 * <b>讓錯誤在使用者還看得到畫面的時候就以人看得懂的形式出現</b>。
 * 不擋的話，使用者看到的是「伺服器錯誤 (500)」，
 * 他能做的事只有重試，而重試永遠不會成功（payload 沒變，結果就不會變）。
 *
 * <h3>⚠️ 這份規則刻意是後端规则的<b>子集</b>，不是同一份</h3>
 *
 * 跨語言沒有辦法只維護一份，但方向必須固定：<b>前端不得擋掉後端會接受的資料</b>
 * （那是使用者改了名字卻存不進去的無意義阻擋）。所以這裡只做兩條最容易
 * 打錯、且後端<b>一定</b>也判成重複的規則：
 * <ul>
 *   <li>尾端空白（MSSQL 的 ANSI padding，只認 U+0020）</li>
 *   <li>大小寫（欄位定序 SQL_Latin1_General_CP1_CI_AS 的 CI）</li>
 * </ul>
 * 全形／半形、NFC／NFD 那些後端做得更完整，這裡<b>不做</b> ——
 * 漏擋只是多跑一趟，後端會回 400 並指名是哪個名字；
 * 誤擋則是使用者改了名字卻仍然存不進去。
 *
 * @param rows 畫面上的規格列
 * @return 可以直接顯示給使用者的訊息；沒有問題時回 null
 */
function firstProblem(rows) {
  const seen = new Set()
  for (const row of rows) {
    const raw = row.variableName ?? ''
    // 只 strip 尾端的半形空白：TAB、換行、NBSP 在資料庫裡都不等於空白，
    // 用 trimEnd() 會把它們算成重複，那會誤擋合法的名稱。
    const key = raw.replace(/ +$/, '').toUpperCase()
    if (seen.has(key)) {
      // 空白重複是本頁最常見的形狀（addRow 產生的就是空字串），
      // 單純說「變數名稱重複：」使用者會看不懂是哪兩列。
      return key === ''
        ? '有兩列都沒有填變數名稱，請先填寫名稱再儲存'
        : `變數名稱重複：${raw}`
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
