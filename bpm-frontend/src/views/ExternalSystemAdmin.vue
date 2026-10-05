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
        ⚠️ 這一欄與表單的輸入框是同一件事的兩個面（#22 收尾）。
        沒有它，管理員看不出一個系統能被哪些 topic 的 worker 認領／查詢 ——
        而「以為自己看得到」正是授權類缺陷發生的前提（與上面候選群組欄位
        同一個道理）。
      -->
      <el-table-column label="允許 Worker Topic" min-width="180">
        <template #default="{ row }">
          <el-tag v-if="!row.allowedWorkerTopics" type="info" size="small">不限制</el-tag>
          <el-tag v-for="k in parseJson(row.allowedWorkerTopics)" :key="k" size="small" style="margin:2px">{{ k }}</el-tag>
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
      <!--
        #21：回呼密鑰的「有／沒有」必須看得見。
        後端列表／詳情把已設定的密鑰遮蔽成 ***、未設定保持 null ——
        管理員要能一眼看出哪些系統還不能回呼（V5 migration 後的既有系統
        預設是 null），而不是逐一 rotate 才發現。
      -->
      <el-table-column label="回呼密鑰" width="100">
        <template #default="{ row }">
          <el-tag :type="row.callbackSecret ? 'success' : 'info'" size="small">
            {{ row.callbackSecret ? '已設定' : '未設定' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="最後使用" width="180">
        <template #default="{ row }">{{ fmt(row.lastUsedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="360">
        <template #default="{ row }">
          <el-button size="small" @click="editSystem(row)">編輯</el-button>
          <el-button size="small" :type="row.enabled ? 'danger' : 'success'"
            @click="toggleEnabled(row)">{{ row.enabled ? '停用' : '啟用' }}</el-button>
          <el-button size="small" type="warning" @click="handleRotate(row)">輪換 Key</el-button>
          <el-button size="small" type="warning" @click="handleRotateCallbackSecret(row)">輪換回呼密鑰</el-button>
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
        <!--
          ⚠️ R-21：這個欄位是必選的多選清單。
          改動前它是自由文字且無必填，空的 allowedProcessKeys 在後端語意是
          「不限制」—— 於是照 UI 正常流程建立的外部系統預設可以啟動任何流程，
          授權檢查在預設路徑上等於不存在。

          選項來自 GET /api/process-definitions；後端寫入時會再驗一次
          「每個 key 都是已部署的流程定義」，所以這裡刻意不開 allow-create：
          建立一個後端一定拒絕的選項，只會把錯誤延後到儲存。
        -->
        <el-form-item label="允許流程" required>
          <el-select
            v-model="form.allowedProcessKeys"
            multiple
            filterable
            :loading="loadingProcesses"
            placeholder="請選擇此系統可發起的流程（至少一個）"
            style="width:100%">
            <el-option v-for="p in processDefinitions" :key="p.key"
              :label="p.name ? p.name + ' (' + p.key + ')' : p.key"
              :value="p.key" />
          </el-select>
          <div style="color:#909399;font-size:13px;line-height:1.6;margin-top:4px">
            這份清單是此系統可發起的流程白名單，<b>至少要選一個</b>。
            後端只接受已部署的流程定義 key，且空值在授權層代表「不限制」，
            因此未選擇時無法儲存。
          </div>
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
        <!--
          ⚠️ 與 allowedCandidateGroups 同一條規則（#22 收尾）：必須在
          blankForm() 裡，否則 applyForm() 不會從列資料挑出它，而 submitForm()
          送的是 {...form} —— 後端 PUT 是整欄覆寫，儲存一次就把白名單
          靜默清成「不限制」，方向是放寬。
        -->
        <el-form-item label="允許 Worker Topic (JSON array)">
          <el-input v-model="form.allowedWorkerTopics" placeholder='["erp-invoices","hr-sync"]' />
          <div style="color:#909399;font-size:13px;line-height:1.6;margin-top:4px">
            外部系統呼叫 <code>/api/external/worker/tasks</code>（acquire 與查詢）時，
            topic 必須在這份清單內，否則整個請求會被 403 擋下。
            <br />
            ⚠️ <b>留空代表「不限制」</b>（與「允許流程」同一條規則），
            也就是該系統可以認領任意 topic 的未鎖定任務 ——
            需要跨系統隔離時，請明確設定這份清單。
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

    <!--
      建立／輪換 API Key 的一次性明文對話框。
      #21 遺留：建立外部系統時，後端在建立回應裡同時回傳 apiKey 與
      callbackSecret 兩把明文（列表／詳情永遠是遮蔽值）。這個對話框是
      它們唯一的曝光面 —— 只顯示 apiKey 的話，新系統的管理員拿不到回呼
      密鑰，該系統的回呼永遠 401，而唯一的補救（輪換回呼密鑰）是一顆
      破壞性按鈕。與「回呼密鑰」輪換對話框同一條「僅顯示一次」規則：
      @closed 清掉 callbackSecret 的明文，元件不再持有它。
      （輪換 API Key 時 callbackSecret 是空的，v-if 不會渲染那一塊。）
    -->
    <el-dialog v-model="showKey" title="密鑰資訊" width="500px"
      :close-on-click-modal="false" @closed="clearCallbackSecret">
      <el-alert type="warning" title="以下密鑰僅顯示一次，請立即複製保存" show-icon :closable="false" style="margin-bottom:12px" />
      <div style="color:#909399;font-size:13px;margin-bottom:4px">API Key</div>
      <el-input :model-value="newApiKey" readonly>
        <template #append>
          <el-button @click="copyKey">複製</el-button>
        </template>
      </el-input>
      <template v-if="newCallbackSecret">
        <div style="color:#909399;font-size:13px;margin:12px 0 4px">回呼密鑰</div>
        <el-input :model-value="newCallbackSecret" readonly>
          <template #append>
            <el-button @click="copyCallbackSecret">複製</el-button>
          </template>
        </el-input>
        <div style="color:#909399;font-size:13px;line-height:1.6;margin-top:8px">
          外部系統回呼時必須以這把密鑰計算 <code>X-Callback-Signature</code>。
        </div>
      </template>
    </el-dialog>

    <!--
      #21 Callback Secret Display Dialog。
      ⚠️ 與 API Key 分開一個對話框是刻意的：兩把密鑰的失效後果不同
      （Key 失效 → 所有 API 401；回呼密鑰失效 → 回呼 401），
      訊息也要分別說清楚。@closed 清掉狀態讓「僅顯示一次」名副其實 ——
      關掉之後元件不再持有明文（後端也不會再回傳同一把）。
    -->
    <el-dialog v-model="showCallbackSecret" title="回呼密鑰" width="500px"
      :close-on-click-modal="false" @closed="clearCallbackSecret">
      <el-alert type="warning" title="此密鑰僅顯示一次，請立即複製保存" show-icon :closable="false" style="margin-bottom:12px" />
      <el-input :model-value="newCallbackSecret" readonly>
        <template #append>
          <el-button @click="copyCallbackSecret">複製</el-button>
        </template>
      </el-input>
      <div style="color:#909399;font-size:13px;line-height:1.6;margin-top:8px">
        外部系統回呼時必須以這把密鑰計算 <code>X-Callback-Signature</code>。
        舊密鑰已立即失效，尚未換用的系統會收到 401。
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { formatDateTime } from '../utils/datetime.js'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getExternalSystems, createExternalSystem, updateExternalSystem, deleteExternalSystem, rotateKey, rotateCallbackSecret } from '../services/externalApi.js'
// R-21：「允許流程」的選項來源。後端會再驗一次已部署的 key，這裡只是把
// 正確的候選值給人挑，避免自由文字打錯字後才在儲存時被 400。
import { getProcessDefinitions } from '../services/flowableApi.js'

const systems = ref([])
const showCreate = ref(false)
const showKey = ref(false)
const newApiKey = ref('')
const showCallbackSecret = ref(false)
const newCallbackSecret = ref('')
const editingId = ref(null)
const actions = ref([])
const processDefinitions = ref([])
const loadingProcesses = ref(false)

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
    // ⚠️ R-21：表單裡是陣列（el-select multiple 綁的就是陣列），送出時
    // 序列化成後端要的 JSON 陣列字串（見 submitForm）。預設必須是空陣列：
    // 空值在後端代表「不限制所有流程」，那是 required 要擋下的狀態，
    // 不可以有任何「沒選＝全開」的預設路徑。
    allowedProcessKeys: [],
    // ⚠️ #88 政策 B：必須在這裡（見模板裡同一個欄位的註解）。
    // 少了它，編輯任一系統都會讓 payload 沒有這個鍵，而後端 PUT 是整欄覆寫
    // → 儲存一次就把白名單靜默清成「不限制」。
    //
    // 預設必須是空字串（＝後端的 null ＝不限制），與後端
    // ExternalSystemPolicy.Kind.UNRESTRICTED 同一個方向。
    allowedCandidateGroups: '',
    // ⚠️ #22 收尾：必須在這裡（見模板裡同一個欄位的註解）。
    // 少了它，編輯任一系統都會讓 payload 沒有這個鍵，而後端 PUT 是整欄覆寫
    // → 儲存一次就把 topic 白名單靜默清成「不限制」。
    allowedWorkerTopics: '',
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
 * 載入「允許流程」的選項（R-21）。
 *
 * <p>失敗時只留空清單：錯誤提示由 {@code services/http.js} 的攔截器負責
 * （view 不重複 toast），而空清單會讓 required 擋住送出 —— 比拿著一份
 * 殘缺的選項清單去儲存安全。成功後即使後端重新部署流程，重新開啟頁面
 * 就會拿到新清單。
 */
async function loadProcessDefinitions() {
  loadingProcesses.value = true
  try {
    const data = await getProcessDefinitions({ latestVersion: true })
    processDefinitions.value = data.data ?? data
  } catch {
    /* 錯誤已由攔截器顯示；沒有選項時 submitForm 的 required 會擋下 */
  } finally {
    loadingProcesses.value = false
  }
}

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
    // R-21：allowedProcessKeys 在列資料裡是 JSON 字串，在表單裡是
    // el-select 的陣列。parseJson 對 null（舊系統未設定）與無法解析的
    // 舊逗號格式都回 [] —— 讓「未選擇」如實呈現並被 required 擋下，
    // 而不是把舊格式原樣帶進送出、或不聲不響地選了錯的東西。
    form.allowedProcessKeys = parseJson(values.allowedProcessKeys)
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
  // R-21：允許流程是必填。後端對 allowedProcessKeys 缺席／空陣列會回 400，
  // 但來回一趟才發現不如在前端講清楚；這裡擋下的正是「照 UI 正常流程建立
  // 一個可啟動任何流程的系統」那條預設路徑。
  if (!form.allowedProcessKeys || form.allowedProcessKeys.length === 0) {
    ElMessage.error('請至少選擇一個允許流程')
    return
  }
  const data = {
    ...form,
    // 後端存的是 JSON 陣列字串，與 allowedActions 同一個格式。
    allowedProcessKeys: JSON.stringify(form.allowedProcessKeys),
    allowedActions: JSON.stringify(actions.value),
  }
  if (editingId.value) {
    await updateExternalSystem(editingId.value, data)
    ElMessage.success('已更新')
  } else {
    const result = await createExternalSystem(data)
    newApiKey.value = result.apiKey
    // #21 遺留：建立回應同時帶回 callbackSecret 的明文（僅此一次）。
    // 不顯示它，新系統就沒有可用的回呼密鑰，而補救只能按輪換 ——
    // 那是破壞性操作，不該是新系統的預設路徑。
    // `|| ''` 讓狀態保持字串（後端若沒帶欄位，v-if 不渲染那一塊）。
    newCallbackSecret.value = result.callbackSecret || ''
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

/**
 * 輪換回呼密鑰（#21）。
 *
 * <p>與 {@link handleRotate} 同一套模式，但失敗路徑不同：這裡用 try/catch
 * 吞掉 API 的 rejection。錯誤提示由 {@code services/http.js} 的攔截器
 * 統一顯示（後端訊息會原樣出現在 toast），view 若再顯示一次會變成兩個 toast
 * —— 與 {@code MyApplications.vue} 的催辦同一條規則。吞掉的另一個目的是
 * 不產生 unhandled rejection。
 *
 * <p>失敗時<b>絕不</b>把 {@code result.callbackSecret} 以外的東西當成明文：
 * 只有 await 成功才打開對話框。後端回傳的明文只存在 {@link newCallbackSecret}
 * 直到對話框關閉（見模板的 @closed）。
 */
async function handleRotateCallbackSecret(row) {
  try {
    await ElMessageBox.confirm(
      '舊回呼密鑰將立即失效，該系統必須改用新密鑰簽章才能回呼。確定要輪換？',
      '輪換回呼密鑰', { type: 'warning' })
  } catch {
    return // 使用者取消；ElMessageBox 以 reject 表示取消，不是失敗
  }

  try {
    const result = await rotateCallbackSecret(row.systemId)
    newCallbackSecret.value = result.callbackSecret
    showCallbackSecret.value = true
  } catch {
    /* 錯誤提示由 http 攔截器負責；明文與成功提示都不出現 */
    return
  }

  // 狀態欄必須跟著變：legacy 系統（null）rotate 後是「已設定」，
  // 不重載的話畫面會停在舊狀態 —— 那正是「畫面說的和事實不同」的形狀。
  // 重載失敗不影響已完成的輪換（明文已在對話框裡），錯誤由攔截器提示。
  await load().catch(() => {})
}

function copyCallbackSecret() {
  navigator.clipboard.writeText(newCallbackSecret.value)
  ElMessage.success('已複製')
}

/**
 * 對話框關閉後清掉明文 —— 這是「僅顯示一次」的一半。
 *
 * <p>後端只在輪換當下回傳明文（列表／詳情永遠是 *** 或 null），所以元件
 * 留著它沒有任何用處，只多一個「被重新打開就看到」的機會。清掉之後
 * 要再看到明文只能再輪換一次（舊密鑰也一併失效）。
 */
function clearCallbackSecret() {
  newCallbackSecret.value = ''
}

onMounted(() => {
  load()
  loadProcessDefinitions()
})
</script>
