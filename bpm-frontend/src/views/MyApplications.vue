<template>
  <div style="padding: 20px">
    <h2>我的申請</h2>
    <el-tabs v-model="activeTab" @tab-change="loadData">
      <el-tab-pane label="進行中" name="running" />
      <el-tab-pane label="已完成" name="completed" />
      <el-tab-pane label="已拒絕" name="rejected" />
    </el-tabs>

    <el-table :data="list" stripe>
      <!--
        ⚠️ #90：沒有這個標籤，員工在「我的申請」看到一張自己沒送過的單，
        畫面完全不解釋那是怎麼回事。
        R-20 規定外部系統發起時 initiator 一律是 system:<id>、員工記在
        onBehalfOf，所以這張單的 initiator 是系統，卻出現在員工自己的清單裡
        （後端比對的是 initiator 或 onBehalfOf，所以它確實「屬於」他）。
        後端兩個端點早就回傳 onBehalf=true 了
        （ProcessController / HistoryController 的 getProcessInstances 系），
        缺的只是畫面上這一行 —— 資料早就在 row 上。

        為什麼標在「流程名稱」旁邊而不是另開一欄：
        清單的寬度有限（流程名稱欄 width 160），而「是不是我親自送出的」
        改變的是**這一列的解讀方式**，不是另一個維度。
        與 TaskInbox.vue:16 同一個理由，刻意一致。

        為什麼文案裡沒有 userId（這與審核人端刻意不同，別照抄那邊）：
        這兩個端點的查詢條件是「initiator == 我」或「onBehalfOf == 我」，
        所以 onBehalf = true 代表「**代我本人**送出」—— 沒有第三方的名字可寫。
        而且寫出來只會是「代 user001 發起」，而 user001 就是看這個頁面的人，
        等於把一句沒有資訊的話印在畫面上。
        審核人端則相反：看的人不是被代的人，那裡非寫出 userId 不可
        （TaskInbox.vue:17 的「代 {{ row.onBehalfOf }} 發起」）。
        ⚠️ 連帶的實作陷阱：欄位名是 row.onBehalf（boolean），
        不是 row.onBehalfOf（那是審核人端 DTO 的字串欄位，此處不存在）。
        寫錯不會報錯，只會讓標籤永遠不出現。

        為什麼用 el-tag 而不是 el-alert、也不加 tooltip：
        DocumentDetail.vue:12 用 alert 是因為那是單一案件的頁面，有空間解釋；
        這裡是列表，alert 會變成每一列一塊橫幅。
        tooltip 則在 jsdom 裡永遠不會渲染，等於多一段沒有測試擋的程式碼。
        若產品要更長的說明，那是設計裁決，應由 PM 決定而不是我自創。
      -->
      <el-table-column label="流程名稱" width="160">
        <template #default="{ row }">
          {{ row.processDefinitionKey }}
          <!--
            class 是給測試的 per-row 把手：wrapper.text() 是所有列攤平後的字串，
            全域 toContain 無法分辨標籤掛在哪一列 —— 那正是「標得太寬」的缺陷形狀。
          -->
          <el-tag
            v-if="row.onBehalf"
            class="on-behalf-tag"
            size="small"
            type="warning"
            style="margin-left:4px"
          >外部系統代為提出</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="發起時間" width="180">
        <template #default="{ row }">{{ fmt(row.startTime) }}</template>
      </el-table-column>
      <el-table-column label="當前節點" width="140">
        <template #default="{ row }">{{ row.currentTask?.taskName || '-' }}</template>
      </el-table-column>
      <el-table-column label="審核人" width="120">
        <template #default="{ row }">{{ row.currentTask?.assignee || '-' }}</template>
      </el-table-column>
      <el-table-column label="狀態" width="100">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="100" v-if="activeTab === 'running'">
        <template #default="{ row }">
          <el-button size="small" @click="urge(row)">催辦</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { formatDateTime } from '../utils/datetime.js'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../stores/auth'
import { getProcessInstances, getHistoricProcessInstances } from '../services/flowableApi.js'

const auth = useAuthStore()
const userId = computed(() => auth.userId)
const activeTab = ref('running')
const list = ref([])
const fmt = (t) => formatDateTime(t)

const statusType = (s) => ({ running: '', completed: 'success', rejected: 'danger', cancelled: 'info' }[s] || '')
const statusLabel = (s) => ({ running: '進行中', completed: '已完成', rejected: '已拒絕', cancelled: '已取消' }[s] || s)

async function loadData() {
  // ⚠️ #90 刻意「不動」這個函式，理由寫在這裡以免日後有人以為漏了：
  // 1. 後端比對的是 initiator **或** onBehalfOf（ProcessController.java:269-270、
  //    HistoryController 的同名端點），所以代發的單本來就在這個查詢的結果裡，
  //    改呼叫方式（例如加一個 initiator 以外的參數）只會把它弄壞。
  // 2. 三個 tab 共用同一張 <el-table>（在上方，:data="list"），
  //    所以標籤只需要寫一次，三個 tab 自動都照得到 —— 沒有「per-tab 模板」
  //    這種東西可以漏掉。
  // 3. completed / rejected 只做陣列 filter（保留整個 row 物件），
  //    onBehalf 這個鍵會原封不動跟著 row 走過來，不需要重新對映。
  //    這也是為什麼已結案的代發單一樣會有標示 —— 它同樣是員工沒送過的單。
  if (activeTab.value === 'running') {
    list.value = await getProcessInstances({ initiator: userId.value })
  } else {
    const all = await getHistoricProcessInstances({ initiator: userId.value, finished: true })
    list.value = activeTab.value === 'completed'
      ? all.filter(p => p.status === 'completed')
      : all.filter(p => p.status !== 'completed')
  }
}

function urge(row) {
  ElMessage.success(`已發送催辦通知：${row.currentTask?.assignee || '審核人'}`)
}

onMounted(loadData)
</script>
