<template>
  <div style="padding: 20px">
    <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:16px">
      <h2>外部系統管理</h2>
      <el-button type="primary" @click="openCreate">建立外部系統</el-button>
    </div>

    <el-table :data="systems" stripe>
      <el-table-column prop="systemId" label="System ID" width="160" />
      <el-table-column prop="systemName" label="名稱" width="160" />
      <el-table-column label="狀態" width="80">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'danger'">{{ row.enabled ? '啟用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="允許流程" min-width="200">
        <template #default="{ row }">
          <el-tag v-for="k in parseJson(row.allowedProcessKeys)" :key="k" size="small" style="margin:2px">{{ k }}</el-tag>
        </template>
      </el-table-column>
      <!--
        ⚠️ 這一欄與表單的輸入框是同一件事的兩個面（#88 政策 B）。
        沒有它，管理員只能靠「點進編輯才知道」確認一個系統能被指定哪些待辦池 ——
        而「以為自己看得到」正是授權類缺陷發生的前提（與下面代發授權欄位同一個道理）。
      -->
      <el-table-column label="允許候選群組" min-width="180">
        <template #default="{ row }">
          <el-tag v-if="!row.allowedCandidateGroups" type="info" size="small">不限制</el-tag>
          <el-tag v-for="k in parseJson(row.allowedCandidateGroups)" :key="k" size="small" style="margin:2px">{{ k }}</el-tag>
        </template>
      </el-table-column>
      <!--
        ⚠️ 這一欄與表單裡的開關是同一件事的兩個面。
        沒有它，管理員只能靠「點進編輯才知道」來確認一個系統有沒有代發授權 ——
        而授權繼承的缺陷（見 resetForm 的註解）正是發生在「以為自己看得到」的
        情況下。看得見才谈得上管理。
      -->
      <el-table-column label="代發授權" width="100">
        <template #default="{ row }">
          <el-tag :type="row.allowOnBehalfOf ? 'warning' : 'info'" size="small">
            {{ row.allowOnBehalfOf ? '可代員工發起' : '不可代發' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="最後使用" width="180">
        <template #default="{ row }">{{ fmt(row.lastUsedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="240">
        <template #default="{ row }">
          <el-button size="small" @click="editSystem(row)">編輯</el-button>
          <el-button size="small" :type="row.enabled ? 'danger' : 'success'"
            @click="toggleEnabled(row)">{{ row.enabled ? '停用' : '啟用' }}</el-button>
          <el-button size="small" type="warning" @click="handleRotate(row)">輪換 Key</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- Create/Edit Dialog -->
    <el-dialog v-model="showCreate" :title="editingId ? '編輯外部系統' : '建立外部系統'" width="600px">
      <el-form :model="form" label-position="top">
        <el-form-item label="System ID" v-if="!editingId">
          <el-input v-model="form.systemId" />
        </el-form-item>
        <el-form-item label="系統名稱" required>
          <el-input v-model="form.systemName" />
        </el-form-item>
        <el-form-item label="聯絡信箱" required>
          <el-input v-model="form.contactEmail" />
        </el-form-item>
        <el-form-item label="允許流程 (JSON array)">
          <el-input v-model="form.allowedProcessKeys" placeholder='["leave-approval","purchase-approval"]' />
        </el-form-item>
        <!--
          ⚠️ 這個欄位<b>必須</b>在 blankForm() 裡，否則會製造一個比 #68a 更難察覺的
          授權缺陷（#88 政策 B）：

          applyForm() 只從列資料挑 blankForm() 認得的鍵，而 submitForm() 送的是
          {...form}。少了這一行，編輯任一系統（例如只改名字）都會讓 payload
          裡沒有 allowedCandidateGroups —— 後端 PUT 是整欄覆寫，於是
          **儲存一次就把白名單清成「不限制」**，而畫面上沒有任何東西顯示這件事。
          而且被靜默放寬的方向是「可以指定更多待辦池」，也就是授權範圍。

          所以這裡除了把它加進 blankForm()，也給它一個輸入框 ——
          「沒有人能設定的授權」本身就是缺陷（allowOnBehalfOf 的註解是同一句話）。
        -->
        <el-form-item label="允許候選群組 (JSON array)">
          <el-input v-model="form.allowedCandidateGroups" placeholder='["dept001","hr:leave:approve"]' />
          <div style="color:#909399;font-size:13px;line-height:1.6;margin-top:4px">
            外部系統呼叫 <code>/api/external/process-instances</code> 時，
            <code>firstTaskCandidateGroups</code> 的<b>每一個</b>群組都必須在這份清單內，
            否則整個請求會被 403 擋下且不啟動流程。
            <br />
            ⚠️ <b>留空代表「不限制」</b>（與「允許流程」同一條規則），
            也就是該系統可以指定任意群組的待辦池。
          </div>
        </el-form-item>
        <el-form-item label="允許操作">
          <el-checkbox-group v-model="actions">
            <el-checkbox value="start_process">發起流程</el-checkbox>
            <el-checkbox value="complete_task">完成任務</el-checkbox>
            <el-checkbox value="query_status">查詢狀態</el-checkbox>
            <el-checkbox value="callback">Callback</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="IP 白名單（逗號分隔）">
          <el-input v-model="form.ipWhitelist" type="textarea" :rows="2" placeholder="10.0.0.1,10.0.0.2" />
        </el-form-item>
        <el-form-item label="Callback URL">
          <el-input v-model="form.callbackUrl" />
        </el-form-item>
        <!--
          ⚠️ 這個開關是 R-20 授權的唯一管理入口。
          改動前後端模型有 allowOnBehalfOf、管理頁卻沒有它 ——
          「外部系統可以代員工發起」是一個沒有人能設定、也沒有人看得出有沒有被設定的能力。
        -->
        <el-form-item label="允許代員工發起 (onBehalfOf)">
          <el-switch v-model="form.allowOnBehalfOf" active-text="允許" inactive-text="不允許" />
          <div style="color:#909399;font-size:13px;line-height:1.6;margin-top:4px">
            開啟後，此系統呼叫 <code>/api/external/process-instances</code> 時可帶
            <code>onBehalfOf</code> 指定一位員工，案件會以該員工的名義路由與補件
            （<code>initiator</code> 仍是 <code>system:&lt;systemId&gt;</code>）。
            <br />
            ⚠️ 預設必須是<b>不允許</b>：這是讓第三方系統能把單子放進某位員工名下
            的能力，而後端 {@code PUT} 在欄位缺席時一律收回它（見
            {@code ExternalSystemAdminController.update}）。
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showCreate = false">取消</el-button>
        <el-button type="primary" @click="submitForm">{{ editingId ? '更新' : '建立' }}</el-button>
      </template>
    </el-dialog>

    <!-- API Key Display Dialog -->
    <el-dialog v-model="showKey" title="API Key" width="500px" :close-on-click-modal="false">
      <el-alert type="warning" title="此 Key 僅顯示一次，請立即複製" show-icon :closable="false" style="margin-bottom:12px" />
      <el-input :model-value="newApiKey" readonly>
        <template #append>
          <el-button @click="copyKey">複製</el-button>
        </template>
      </el-input>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { formatDateTime } from '../utils/datetime.js'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getExternalSystems, createExternalSystem, updateExternalSystem, deleteExternalSystem, rotateKey } from '../services/externalApi.js'

const systems = ref([])
const showCreate = ref(false)
const showKey = ref(false)
const newApiKey = ref('')
const editingId = ref(null)
const actions = ref([])

/**
 * 表單的空白值。
 *
 * ⚠️ 這是一個<b>函式</b>而不是一個常數物件 —— 每次呼叫都必須給一份新的。
 * 共用同一個物件會讓上一筆資料的欄位被就地改寫，那正是底下那個缺陷的形狀。
 *
 * 欄位清單是「這個表單認得的欄位」的唯一來源，{@link applyForm} 只從列資料
 * 挑這些鍵出來。不用「把整列灌進 form」是因為那會讓伺服器新增的欄位
 * （例如唯讀的 id / apiKey / lastUsedAt）也變成可送出的欄位。
 */
function blankForm() {
  return {
    systemId: '',
    systemName: '',
    contactEmail: '',
    allowedProcessKeys: '',
    // ⚠️ #88 政策 B：必須在這裡（見模板裡同一個欄位的註解）。
    // 少了它，編輯任一系統都會讓 payload 沒有這個鍵，而後端 PUT 是整欄覆寫
    // → 儲存一次就把白名單靜默清成「不限制」。
    //
    // 預設必須是空字串（＝後端的 null ＝不限制），與後端
    // ExternalSystemPolicy.Kind.UNRESTRICTED 同一個方向。
    allowedCandidateGroups: '',
    ipWhitelist: '',
    callbackUrl: '',
    // R-20 的代發授權。預設必須是 false（與後端 create() 的
    // Boolean.TRUE.equals(...) 同一個方向）。見 ExternalSystem 的欄位註解。
    allowOnBehalfOf: false,
    // ⚠️ 這個欄位沒有輸入框，卻一定要在 payload 裡。
    // 後端 PUT 是整欄覆寫（sys.setEnabled(req.getEnabled())），漏掉會讓
    // NOT NULL 約束失敗 —— 也就是「只為了改代發授權而按儲存」會 500。
    // 它只能由「停用／啟用」那顆按鈕改，所以這裡固定帶著 row 的現值。
    enabled: true,
  }
}

const form = reactive(blankForm())

const fmt = (t) => formatDateTime(t, '-')
const parseJson = (s) => { try { return JSON.parse(s || '[]') } catch { return [] } }

async function load() { systems.value = await getExternalSystems() }

/**
 * 把表單整份換成 {@link values} 指定的內容。
 *
 * ⚠️ <b>為什麼要先刪掉所有鍵再 Object.assign，而不是直接 Object.assign</b>
 * （#68a 的授權繼承缺陷）：
 *
 * <pre>
 *   1. 編輯 erp，把「允許代員工發起」打開 → 儲存
 *   2. 按「建立外部系統」→ resetForm()
 *   3. 表單送出 → POST 建立了一個 allowOnBehalfOf = true 的新系統
 * </pre>
 *
 * <p>{@code Object.assign} 只能「新增／覆寫」，<b>不會刪掉目標上多出來的鍵</b>。
 * 而 {@code form} 是同一個 reactive 物件跨越整個頁面生命週期，
 * {@code editSystem()} 又把整列灌進去 —— 上一個系統的 {@code allowOnBehalfOf}
 * 就這樣留在表單裡，被下一個「建立」送出。
 *
 * <p>後果比「少一個開關」嚴重：它<b>靜默</b>給了一個新系統它不該有的權限，
 * 而且畫面上看不出來（開關在表單裡，值是錯的）。
 *
 * <h3>另一個做法：把 form 換成 ref，每次賦一個新物件</h3>
 *
 * <p>那樣結構上就沒有「多餘的鍵」這回事，但 {@code el-form :model} 與
 * {@code v-model} 綁的是 {@code form.xxx}，改成形如 {@code form.value.xxx}
 * 會讓整個模板的綁定都要跟著改，而且<b>未來有人加欄位時同樣可能忘記放進
 * blankForm()</b> —— 缺陷會以完全相同的形式回來。刪鍵是就地修掉同一個陷阱。
 */
function applyForm(values) {
  for (const key of Object.keys(form)) delete form[key]
  Object.assign(form, blankForm())
  if (values) {
    for (const key of Object.keys(form)) {
      if (values[key] !== undefined) form[key] = values[key]
    }
  }
}

function editSystem(row) {
  editingId.value = row.systemId
  applyForm(row)
  actions.value = parseJson(row.allowedActions)
  showCreate.value = true
}

function resetForm() {
  editingId.value = null
  applyForm(null)
  actions.value = []
}

/**
 * 開啟「建立」對話框。
 *
 * ⚠️ 必須呼叫 {@link resetForm}：改動前這顆按鈕只有 {@code showCreate = true}，
 * 於是「編輯 A → 取消 → 建立」會開出一個裝著 A 的資料、標題寫著「建立」的表單 ——
 * 按下建立送出的其實是對 A 的 PUT。<b>而且是靜默的</b>：畫面看起來完全正常。
 */
function openCreate() {
  resetForm()
  showCreate.value = true
}

async function submitForm() {
  const data = { ...form, allowedActions: JSON.stringify(actions.value) }
  if (editingId.value) {
    await updateExternalSystem(editingId.value, data)
    ElMessage.success('已更新')
  } else {
    const result = await createExternalSystem(data)
    newApiKey.value = result.apiKey
    showKey.value = true
  }
  showCreate.value = false
  resetForm()
  await load()
}

async function toggleEnabled(row) {
  if (row.enabled) {
    await deleteExternalSystem(row.systemId)
  } else {
    // ⚠️ 必須帶著 row 的完整內容（包含 allowOnBehalfOf），不能只送 {enabled:true}。
    // 後端 PUT 是整欄覆寫且「欄位缺席 = 收回該能力」
    // （ExternalSystemAdminController.update 的註解說明了那個方向的理由）。
    // 只送 {enabled:true} 會讓「停用 → 啟用」順手把代發授權關掉，
    // 而那個欄位在這個畫面上沒有輸入框 —— 使用者無從得知、也無從反悔。
    await updateExternalSystem(row.systemId, { ...row, enabled: true })
  }
  await load()
}

async function handleRotate(row) {
  await ElMessageBox.confirm('舊 Key 將立即失效，確定要輪換？', '輪換 API Key', { type: 'warning' })
  const result = await rotateKey(row.systemId)
  newApiKey.value = result.apiKey
  showKey.value = true
}

function copyKey() {
  navigator.clipboard.writeText(newApiKey.value)
  ElMessage.success('已複製')
}

onMounted(load)
</script>
