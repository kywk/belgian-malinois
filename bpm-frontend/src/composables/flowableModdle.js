/**
 * Flowable BPMN moddle extension for bpmn-js.
 * Defines Flowable-specific attributes on BPMN elements.
 *
 * ⚠️ 為什麼 {@code TaskListener} 有 superClass: ['Element']（2026-09-30 新增，#67）
 * ────────────────────────────────────────────────────────────────────
 * 改動前 bpmn-js **開啟再存檔會把 <flowable:taskListener> 整個刪掉**。
 *
 * 成因不是 bpmn-js 的 bug，是本檔的型別定義有問題：
 *   - bpmn:ExtensionElements 的子元素型別是內建的 {@code Element}（泛用元素），
 *     moddle-xml 只有在子元素的<b>型別鏈</b>裡找得到 {@code Element} 時，
 *     才知道要把它收進 extensionElements。
 *   - 原本的 {@code TaskListener} 沒有 superClass → 型別鏈裡沒有 Element
 *     → moddle-xml 丟出「unrecognized element <flowable:taskListener>」
 *     → 該節點的 extensionElements 變成空的 → 匯出時整段不見。
 *
 * 後果很嚴重：leave-approval / purchase-approval 的每一個 UserTask 都靠
 * {@code ${notifyTaskListener}} 寄信，設計器裡改個欄位再存檔就沒有名單了。
 * （實測：把 leave-approval.bpmn20.xml 匯出一次，兩個 taskListener 都消失。）
 *
 * 為什麼不用「把 superClass 寫成 ['bpmn:BaseElement']」：那樣型別鏈裡
 * 仍然沒有內建的 {@code Element}，moddle-xml 一樣不收，問題不會消失。
 * {@code Element} 是內建型別（moddle 的 BUILTINS 裡有），是唯一正確的寫法。
 *
 * 為什麼 {@code Webhooks}／{@code Webhook} 也用同一個寫法：它們就是
 * extensionElements 的子元素，理由完全一樣。
 */
export default {
  name: 'Flowable',
  uri: 'http://flowable.org/bpmn',
  prefix: 'flowable',
  // ⚠️ 'lowerCase' 只把「首字元」小寫化（moddle-xml 的 lower()），
  // 所以 TaskListener → taskListener、Webhooks → webhooks。
  // 這是為什麼後端 WebhookConfig 的 element 名刻意是全小寫的 webhook／webhooks：
  // tag 名是由型別名決定的，型別名寫成 WebHook 會變成 webHook，
  // 與後端對不起來，而且<b>不會有任何錯誤</b> —— 設定會靜默失效。
  xml: { tagAlias: 'lowerCase' },
  associations: [],
  types: [
    {
      name: 'Assignable',
      extends: ['bpmn:UserTask'],
      properties: [
        { name: 'assignee', isAttr: true, type: 'String' },
        { name: 'candidateGroups', isAttr: true, type: 'String' },
        { name: 'candidateUsers', isAttr: true, type: 'String' },
        { name: 'formKey', isAttr: true, type: 'String' },
        { name: 'skipExpression', isAttr: true, type: 'String' }
      ]
    },
    {
      name: 'Initiator',
      extends: ['bpmn:StartEvent'],
      properties: [
        { name: 'initiator', isAttr: true, type: 'String' },
        { name: 'formKey', isAttr: true, type: 'String' }
      ]
    },
    {
      name: 'TaskListenerList',
      extends: ['bpmn:UserTask'],
      properties: [
        { name: 'taskListener', type: 'TaskListener', isMany: true }
      ]
    },
    {
      name: 'TaskListener',
      // ⚠️ 不可移除。理由見檔頭。
      superClass: ['Element'],
      properties: [
        { name: 'event', isAttr: true, type: 'String' },
        { name: 'delegateExpression', isAttr: true, type: 'String' },
        { name: 'class', isAttr: true, type: 'String' }
      ]
    },
    // ── 節點層 webhook 設定（#67，spec §11.4）──────────────────────
    // 寫出來長相：
    //   <bpmn:extensionElements>
    //     <flowable:webhooks>
    //       <flowable:webhook event="create" url="https://..." method="POST"/>
    //     </flowable:webhooks>
    //   </bpmn:extensionElements>
    {
      name: 'Webhooks',
      superClass: ['Element'],
      properties: [
        { name: 'values', type: 'Webhook', isMany: true }
      ]
    },
    {
      name: 'Webhook',
      superClass: ['Element'],
      properties: [
        { name: 'event', isAttr: true, type: 'String' },
        { name: 'url', isAttr: true, type: 'String' },
        { name: 'method', isAttr: true, type: 'String' }
      ]
    },
    // ── Call Activity 的子流程呼叫設定（#4，spec §4.4.2）─────────────
    // 寫出來長相：
    //   <callActivity calledElement="countersign-review"
    //                 flowable:inheritVariables="false">
    //     <bpmn:extensionElements>
    //       <flowable:in source="legalReviewer" target="countersignAssignee"/>
    //       <flowable:out source="approved" target="countersignApproved"/>
    //     </bpmn:extensionElements>
    //   </callActivity>
    //
    // ⚠️ In／Out 的 superClass: ['Element'] 不可移除，理由與檔頭的
    // TaskListener 完全相同 —— 而且後果已用 bpmn-moddle 實測過：
    // 沒有這兩個型別時，設計器「開啟含 in／out 的 BPMN 再存檔」會把
    // <flowable:in>／<flowable:out> 整個刪掉（unparsable content → 匯出時
    // extensionElements 只剩空的）。那等於使用者在設計器按一次儲存，就
    // 無聲拆掉父流程與子流程之間的變數映射。
    //
    // inheritVariables 定義成 Boolean 而不是放給 moddle 當 unknown
    // attribute：unknown attribute 雖然目前會被保留在 $attrs，但每次解析都
    // 產生 warning，而且那條保留路徑不受型別系統保證（見 #67 TaskListener
    // 的教訓）。
    {
      name: 'CallActivity',
      extends: ['bpmn:CallActivity'],
      properties: [
        { name: 'inheritVariables', isAttr: true, type: 'Boolean' }
      ]
    },
    {
      name: 'In',
      superClass: ['Element'],
      properties: [
        { name: 'source', isAttr: true, type: 'String' },
        { name: 'sourceExpression', isAttr: true, type: 'String' },
        { name: 'target', isAttr: true, type: 'String' },
        { name: 'targetExpression', isAttr: true, type: 'String' }
      ]
    },
    {
      name: 'Out',
      superClass: ['Element'],
      properties: [
        { name: 'source', isAttr: true, type: 'String' },
        { name: 'sourceExpression', isAttr: true, type: 'String' },
        { name: 'target', isAttr: true, type: 'String' },
        { name: 'targetExpression', isAttr: true, type: 'String' }
      ]
    }
  ]
}
