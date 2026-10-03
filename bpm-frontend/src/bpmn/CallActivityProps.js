import { h } from 'preact'
import { useEffect, useState } from 'preact/hooks'
import { SelectEntry, TextFieldEntry, CheckboxEntry } from '@bpmn-io/properties-panel'
import { useService } from 'bpmn-js-properties-panel'
import { getProcessDefinitions } from '../services/flowableApi'

/**
 * Call Activity 節點的面板（#4、spec §4.4.2）：業務人員在這裡挑一個已部署的
 * 預定義加簽子流程（例如 countersign-review），並決定要不要繼承父流程變數。
 *
 * <h2>為什麼要處理「流程清單載入失敗」</h2>
 *
 * <p>calledElement 是自由文字屬性，下拉只是方便。若 API 掛掉就讓使用者什麼都
 * 填不了，等於把「後端暫時不可用」放大成「設計器不能設計流程」—— 而設計器
 * 是離線編輯（草稿存 localStorage、部署才連後端）。所以載入失敗時降級成
 * 手動輸入，並把失敗原因寫在欄位描述裡：使用者仍然可以輸入 key、存檔、
 * 之後再部署。
 *
 * <h2>存檔契約</h2>
 *
 * <ul>
 *   <li><b>一次 updateProperties</b>：與 WebhookProps 同一條理由 —— 這是
 *       undo 堆疊裡的一步。拆成兩次的話使用者按一次 ctrl+Z 只會走一半。</li>
 *   <li><b>只寫自己那一格</b>：calledElement 與 flowable:inheritVariables
 *       都是單一屬性，不碰 extensionElements。Call Activity 的
 *       flowable:in／flowable:out 就在 extensionElements 裡，是父流程與
 *       子流程之間唯一的變數介面（flowableModdle.js 為此補了 In／Out
 *       型別，否則設計器開啟再存檔會靜默刪掉它們）。</li>
 * </ul>
 *
 * <h2>為什麼流程清單放在模組層快取</h2>
 *
 * <p>面板每次選取變更都會重建群組與元件；沒有快取的話，每次點到 Call
 * Activity 都會重新打一次 API。快取的生命週期是「這個設計器分頁」——
 * 期間新部署的流程不會出現在下拉裡（要重開設計器）。這是刻意的取捨：
 * 設計流程時清單變動的機率低，而每次選取都打 API 的延遲與失敗面更煩人。
 * 載入失敗後同一分頁也不再重試（promise 已 settled）：使用者以降級的手動
 * 輸入繼續工作，重新載入頁面才會再試。
 */

/** 流程清單的載入狀態。idle → loading → loaded／error，其餘狀態不變。 */
let definitionsState = { status: 'idle' }
let definitionsPromise = null

/** 目前快取狀態（測試與元件讀取用）。 */
export function processDefinitionsState() {
  return definitionsState
}

/**
 * （重新）載入流程清單。元件第一次掛載走 {@link ensureProcessDefinitions}；
 * 這個函式是測試與未來「重新整理」入口用的強制重載。
 *
 * <p>無論成功失敗都回傳新的狀態物件、不拋例外：呼叫端（面板）要的是
 * 「能顯示什麼」，不是錯誤傳播。錯誤訊息在這裡就轉成可讀字串。
 */
export function loadProcessDefinitions() {
  definitionsState = { status: 'loading' }
  definitionsPromise = getProcessDefinitions({ latestVersion: true })
    .then(list => {
      definitionsState = { status: 'loaded', definitions: Array.isArray(list) ? list : [] }
      return definitionsState
    })
    .catch(error => {
      definitionsState = { status: 'error', message: describeError(error) }
      return definitionsState
    })
  return definitionsPromise
}

/** 載入中時共用同一個 promise，避免多個元件實例各打一次 API。 */
export function ensureProcessDefinitions() {
  return definitionsPromise || loadProcessDefinitions()
}

/**
 * 下拉選項：已部署流程（key 為值、名稱＋key 為標籤）。
 *
 * <p>目前值不在清單裡時附加一個標示選項 —— 情境與 WebhookProps 的無效事件
 * 相同：BPMN 可能是舊檔、手改的、或引用了已刪除的流程。SelectEntry 沒有
 * 對應選項時畫面是空白，使用者連「它存了什麼」都看不出來；原值照實顯示、
 * 只是標明不在清單中，要改再自己選。
 */
export function calledElementOptions(definitions, currentValue) {
  const options = (definitions || []).map(d => ({
    value: d.key,
    label: d.name ? `${d.name}（${d.key}）` : d.key
  }))
  if (currentValue && !options.some(o => o.value === currentValue)) {
    options.push({ value: currentValue, label: `${currentValue}（未在已部署清單中）` })
  }
  return options
}

export default function CallActivityProps(element) {
  return {
    id: 'flowable-call-activity',
    label: '呼叫子流程',
    entries: [
      { id: 'calledElement', component: CalledElementEntry, isEdited: () => true },
      { id: 'inheritVariables', component: InheritVariablesEntry, isEdited: () => true }
    ]
  }
}

/** calledElement 的存檔路徑：單次 updateProperties，只寫這一個屬性。 */
export function saveCalledElement(modeling, element, key) {
  modeling.updateProperties(element, { calledElement: key })
}

/**
 * flowable:inheritVariables 的存檔路徑。
 *
 * <p>Flowable 的 {@code CallActivity.inheritVariables} 預設是 false，所以
 * 「未設定」與「false」語意相同；這裡勾選切換時明確寫入 true／false，
 * 讓 XML 忠實反映使用者看得到的畫面狀態。
 */
export function saveInheritVariables(modeling, element, value) {
  modeling.updateProperties(element, { 'flowable:inheritVariables': value })
}

/**
 * 面板元件的無 hook 版本（測試直接驅動這一個）。
 *
 * <p>元件本體只負責接 service 與 hook，形狀決策全在這裡 —— 與
 * WebhookProps 把存檔路徑抽成 saveWebhooks 同一個理由：測到的必須是產品
 * 真的跑的那條路徑，而不是測試裡另寫一份複製品。
 */
export function calledElementVNode({ element, modeling, debounce, state, currentValue }) {
  if (state.status === 'error') {
    return h(TextFieldEntry, {
      id: 'calledElement',
      label: '被呼叫流程 key',
      element,
      debounce,
      getValue: () => currentValue,
      setValue: (v) => saveCalledElement(modeling, element, v),
      description: `流程清單載入失敗（${state.message}），可手動輸入流程 key`
    })
  }

  return h(SelectEntry, {
    id: 'calledElement',
    label: '被呼叫流程',
    element,
    debounce,
    getOptions: () => calledElementOptions(state.definitions, currentValue),
    getValue: () => currentValue,
    setValue: (v) => saveCalledElement(modeling, element, v),
    description: state.status === 'loading' ? '流程清單載入中…' : undefined
  })
}

export function inheritVariablesVNode({ element, modeling }) {
  const bo = element.businessObject
  return h(CheckboxEntry, {
    id: 'inheritVariables',
    label: '繼承父流程變數（inheritVariables）',
    element,
    getValue: () => bo.get('flowable:inheritVariables') === true,
    setValue: (v) => saveInheritVariables(modeling, element, v),
    description: '勾選時子流程可讀取父流程全部變數；未勾選時只有 flowable:in 映射的變數可見'
  })
}

function CalledElementEntry(props) {
  const { element } = props
  const modeling = useService('modeling')
  const debounce = useService('debounceInput')
  const state = useDefinitionsState()
  return calledElementVNode({
    element,
    modeling,
    debounce,
    state,
    currentValue: element.businessObject.get('calledElement') || ''
  })
}

function InheritVariablesEntry(props) {
  const { element } = props
  const modeling = useService('modeling')
  return inheritVariablesVNode({ element, modeling })
}

/**
 * 第一次掛載時觸發載入，完成後用回傳的狀態物件重繪。
 *
 * <p>只在 idle 時載入：其他元件實例（或重新選取）共用模組層的
 * {@link ensureProcessDefinitions}，不重打 API。
 */
function useDefinitionsState() {
  const [state, setState] = useState(processDefinitionsState)
  useEffect(() => {
    if (state.status !== 'idle') return undefined
    let alive = true
    ensureProcessDefinitions().then(next => {
      if (alive) setState(next)
    })
    return () => { alive = false }
  }, [state.status])
  return state
}

function describeError(error) {
  return error?.response?.data?.message || error?.message || '未知錯誤'
}
