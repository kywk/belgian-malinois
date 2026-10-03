package com.bpm.core.webhook;

import com.bpm.core.support.IntegrationTestBase;
import com.bpm.core.support.WebhookTestSink;
import tools.jackson.databind.ObjectMapper;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #67 的端到端驗證：<b>節點上設定的 webhook 真的被投遞了</b>。
 *
 * <h2>這個工項的失敗型態，以及為什麼斷言必須長成這樣</h2>
 *
 * <p>webhook 從來沒有接上過，而它的失敗型態是<b>什麼都不發生</b>：
 * 沒有例外、沒有 5xx、沒有稽核紀錄、流程照跑、簽核照簽。
 * 監控上與「一切正常」完全無法區分。
 *
 * <p>因此本測試組的每一條正向斷言都同時滿足兩件事：
 * <ol>
 *   <li>流程／任務真的推進了（<b>不</b>只驗「沒拋例外」），而且</li>
 *   <li>一個真的 HTTP 端點（{@link WebhookTestSink}）<b>收到了請求</b>，
 *       帶著正確的 body 與可驗過的 HMAC 標頭。</li>
 * </ol>
 *
 * <p>只做 (1) 的话，一個「listener 什麼都不做」的實作會全數通過 ——
 * 這正是本工項改動前的狀態。
 *
 * <h2>SSRF 閘門的測試為什麼在這裡</h2>
 *
 * <p>投遞位址改成來自 BPMN 之後，<b>設定 URL 的人就是能編輯 BPMN 的人</b>。
 * 這是低程式碼平台，業務人員在設計器裡就能填一個 URL。
 * {@link WebhookUrlPolicy} 是整條鏈路上唯一的閘門，所以必須證明它對
 * 「從 BPMN 來的 URL」同樣生效 —— 單元測試（{@link WebhookUrlPolicyTest}）
 * 只證明它能擋住手寫的字串，證明不了它在真實路徑上被呼叫過。
 *
 * <h2>⚠️ 斷言的等待策略</h2>
 *
 * <p>投遞是<b>非同步</b>的（RabbitMQ → listener thread → HTTP）。
 * 所以每條斷言都用「輪詢到條件成立」而不是「睡固定時間」：
 * 睡固定時間在慢的機器上會偶發失敗，而在快的機器上又測不到真正的非同步路徑。
 * 逾時 15 秒是取決於 consumer 的預設逾時（connect 2s + read 5s）
 * 加上排隊時間，不是拍腦袋的數字。
 *
 * <h2>⚠️ 本類別刻意<b>不</b>加 {@code @TestPropertySource}</h2>
 *
 * <p>要讓投遞真的打得到本測試的 Tomcat，{@code bpm.webhook.allowed-hosts}
 * 必須含 {@code localhost}（{@code WebhookUrlPolicy} 預設拒絕 loopback）。
 * 那個設定放在 {@code src/test/resources/application-test.yml}，
 * 而不是這個類別上 —— 加在類別上會產生第二個 Spring context，
 * 而 {@code IntegrationTestBase.SERVLET_PORT} 是 static final 的單一 port，
 * 第二個 context 會綁不到 port 而讓<b>其他</b>測試整組紅掉。
 *
 * <p>SSRF 的「預設拒絕」仍然測得到，而且就在同一個測試組裡：
 * {@code loopbackUrlFromBpmnIsRejected} 用 {@code 127.0.0.1} 驗證被擋
 * （它與清單裡的 {@code localhost} 是不同的字面 host，而允許清單比對的
 * 就是字面 host）；「清單為空時不放行任何主機」則由
 * {@link WebhookUrlPolicyTest} 的單元測試守住。
 */
class WebhookDeliveryWiringTest extends IntegrationTestBase {

    @Autowired private RepositoryService repositoryService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private TaskService taskService;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private WebhookConfigResolver resolver;
    @Autowired private com.bpm.core.lint.BpmnLintService lintService;

    /** application-test.yml / application.yml 的 hmac-secret。 */
    private static final String HMAC_SECRET = "bpm-webhook-secret";

    @BeforeEach
    void resetSink() {
        WebhookTestSink.reset();
    }

    // ── 工具 ────────────────────────────────────────────────────────

    private String sinkUrl(String name) {
        // ⚠️ 主機名必須是 localhost。WebhookUrlPolicy 的允許清單比對的是
        // URL 裡的字面 host，而本類別的設定只列了 localhost ——
        // 同一個測試組裡因此可以用 127.0.0.1 驗「不在清單內會被擋」。
        return "http://localhost:" + SERVLET_PORT + "/mock/test-webhook-sink/" + name;
    }

    /**
     * 產生一支只有一個 UserTask 的流程。
     *
     * @param nodeInner   userTask 的內容（taskListener / extensionElements / documentation）
     * @param processKey  流程 key（每次測試用唯一的，避免與其他測試共用同一個定義）
     */
    private String deploySingleTaskProcess(String processKey, String nodeInner) {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="http://bpm.com/webhook-test">
                  <process id="%1$s" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="approve"/>
                    <userTask id="approve" name="審核關卡" flowable:assignee="mgr001">%2$s</userTask>
                    <sequenceFlow id="f2" sourceRef="approve" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(processKey, nodeInner);
        repositoryService.createDeployment()
                .name("whtest-" + processKey)
                .addString(processKey + ".bpmn20.xml", xml)
                .deploy();
        return processKey;
    }

    /** 帶 webhookTaskListener 的 UserTask 內容（event="all" 與出貨的兩支 BPMN 一致）。 */
    private String nodeWithHooks(String inner) {
        return "<extensionElements>"
                + "<flowable:taskListener event=\"create\" delegateExpression=\"${notifyTaskListener}\"/>"
                + "<flowable:taskListener event=\"all\" delegateExpression=\"${webhookTaskListener}\"/>"
                + inner
                + "</extensionElements>";
    }

    private String webhooksElement(String... webhookAttrs) {
        return "<flowable:webhooks>" + String.join("", webhookAttrs) + "</flowable:webhooks>";
    }

    private String webhook(String event, String url, String method) {
        return "<flowable:webhook event=\"" + event + "\" url=\"" + url
                + "\" method=\"" + method + "\"/>";
    }

    /**
     * 舊格式的 {@code <documentation>} 元素。
     *
     * <p>必須排在 {@code <extensionElements>} <b>之前</b>（BPMN XSD 的 sequence 順序），
     * 放錯位置的症狀是部署時的 SAXParseException，看起來與被測的行為無關。
     */
    private String legacyDoc(String name) {
        return "<documentation>" + WebhookConfigResolver.LEGACY_DOC_PREFIX
                + "[{\"event\":\"create\",\"url\":\"" + sinkUrl(name)
                + "\",\"method\":\"POST\"}]</documentation>";
    }

    /** 等待非空斷言成立。 */
    private void awaitDelivery(BooleanSupplier condition, String what) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) return;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(condition.getAsBoolean())
                .as("等待 15 秒後仍未發生：%s%n實際收到的投遞：%s", what, WebhookTestSink.received())
                .isTrue();
    }

    private Map<String, Object> body(WebhookTestSink.Received r) {
        try {
            return objectMapper.readValue(r.body(), new tools.jackson.core.type.TypeReference<>() {
            });
        } catch (Exception e) {
            throw new AssertionError("投遞 body 不是 JSON：" + r.body(), e);
        }
    }

    private String expectedHmac(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(HMAC_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ── 主要案例 ────────────────────────────────────────────────────

    @Nested
    @DisplayName("新格式（extensionElements）")
    class ExtensionElementsFormat {

        @Test
        @DisplayName("create 事件真的投遞到設定的 URL，body 與 HMAC 都正確")
        void createEventIsActuallyDelivered() {
            String key = deploySingleTaskProcess("wh-create",
                    nodeWithHooks(webhooksElement(webhook("create", sinkUrl("a"), "POST"))));

            var pi = runtimeService.startProcessInstanceByKey(key,
                    Map.of("businessKey", "WH-1", "leaveType", "annual", "days", 3));

            // 非空斷言 (1)：任務真的建立了（listener 有被呼叫）
            Task task = taskService.createTaskQuery().processInstanceId(pi.getId()).singleResult();
            assertThat(task).as("listener 若解析不到 delegateExpression，任務根本不會建立").isNotNull();

            // 非空斷言 (2)：HTTP 端點真的收到了
            awaitDelivery(() -> !WebhookTestSink.receivedTo("a").isEmpty(), "create 事件的投遞");
            WebhookTestSink.Received r = WebhookTestSink.receivedTo("a").get(0);

            assertThat(r.method()).isEqualTo("POST");
            assertThat(body(r))
                    .containsEntry("event", "task.create")
                    .containsEntry("processInstanceId", pi.getId())
                    .containsEntry("processDefinitionKey", key)
                    .containsEntry("businessKey", "WH-1")
                    .containsEntry("taskId", task.getId())
                    .containsEntry("assignee", "mgr001");

            // HMAC 必須能驗過 —— 標頭存在不代表簽章是對的。
            // 這正是 WebhookConsumer 註解裡記錄過的舊缺陷（簽章是對
            // 「未塞簽章的 JSON」算的，送出的 body 又是重新序列化的結果）。
            assertThat(r.signature())
                    .as("X-BPM-Signature 必須等於對實際送出的 body 計算的 HMAC")
                    .isEqualTo(expectedHmac(r.body()));
            assertThat(r.deliveryId()).as("重放偵測用的 delivery id 不可為空").isNotBlank();
            assertThat(r.timestamp()).isNotBlank();

            // ⚠️ 不得外送流程變數（security-audit P2-1）。表單欄位 id 就是
            // 變數名，所以把 variables 送出去等於把整張表單送給外部 URL。
            assertThat(body(r))
                    .as("payload 不得包含任何流程變數 —— leaveType/days 是這個案例刻意放進去的")
                    .doesNotContainKeys("leaveType", "days", "variables", "allVariables");
        }

        @Test
        @DisplayName("complete 事件帶著 action，且 PUT 方法有被尊重")
        void completeEventIsDeliveredWithAction() {
            String key = deploySingleTaskProcess("wh-complete",
                    nodeWithHooks(webhooksElement(webhook("complete", sinkUrl("b"), "PUT"))));

            var pi = runtimeService.startProcessInstanceByKey(key, Map.of("businessKey", "WH-2"));
            Task task = taskService.createTaskQuery().processInstanceId(pi.getId()).singleResult();

            // 非空斷言：create 事件不該投遞（這個節點只設定了 complete）。
            // 少了這條，「事件比對根本沒生效」的實作也會讓下面那條綠。
            taskService.complete(task.getId(), Map.of("approved", true, "days", 5));

            awaitDelivery(() -> !WebhookTestSink.receivedTo("b").isEmpty(), "complete 事件的投遞");
            WebhookTestSink.Received r = WebhookTestSink.receivedTo("b").get(0);

            assertThat(r.method()).as("設定 PUT 就必須真的用 PUT").isEqualTo("PUT");
            assertThat(body(r))
                    .containsEntry("event", "task.complete")
                    .containsEntry("action", "approved")
                    .containsEntry("operatorId", "mgr001")
                    .doesNotContainKeys("days");
        }

        @Test
        @DisplayName("事件比對有效：只設定 complete 時，create 不投遞")
        void eventFilterIsNotANoOp() {
            String key = deploySingleTaskProcess("wh-filter",
                    nodeWithHooks(webhooksElement(webhook("complete", sinkUrl("c"), "POST"))));

            var pi = runtimeService.startProcessInstanceByKey(key, Map.of("businessKey", "WH-3"));
            Task task = taskService.createTaskQuery().processInstanceId(pi.getId()).singleResult();

            assertThat(WebhookTestSink.receivedTo("c"))
                    .as("任務建立時只觸發 create，而這個節點只設定了 complete —— 不得投遞")
                    .isEmpty();

            taskService.complete(task.getId(), Map.of("approved", true));
            awaitDelivery(() -> !WebhookTestSink.receivedTo("c").isEmpty(), "complete 事件的投遞");
            assertThat(body(WebhookTestSink.receivedTo("c").get(0)))
                    .containsEntry("event", "task.complete");
        }

        @Test
        @DisplayName("同一節點的兩筆設定各自投遞一次（不是只取第一筆，也不是只投最後一筆）")
        void eachConfiguredUrlGetsItsOwnDelivery() {
            String key = deploySingleTaskProcess("wh-multi", nodeWithHooks(webhooksElement(
                    webhook("create", sinkUrl("d1"), "POST"),
                    webhook("create", sinkUrl("d2"), "PUT"),
                    webhook("complete", sinkUrl("d3"), "POST"))));

            var pi = runtimeService.startProcessInstanceByKey(key, Map.of("businessKey", "WH-4"));
            Task task = taskService.createTaskQuery().processInstanceId(pi.getId()).singleResult();

            awaitDelivery(() -> WebhookTestSink.receivedTo("d1").size() == 1
                    && WebhookTestSink.receivedTo("d2").size() == 1, "兩筆 create 設定的投遞");
            assertThat(WebhookTestSink.receivedTo("d3"))
                    .as("complete 設定不該在 create 時投遞").isEmpty();

            taskService.complete(task.getId(), Map.of("approved", true));
            awaitDelivery(() -> WebhookTestSink.receivedTo("d3").size() == 1, "complete 設定的投遞");

            // 每個目標都只有一則 —— 沒有重複投遞。
            assertThat(WebhookTestSink.receivedTo("d1")).hasSize(1);
            assertThat(WebhookTestSink.receivedTo("d2")).hasSize(1);
            assertThat(WebhookTestSink.receivedTo("d3")).hasSize(1);
        }

        @Test
        @DisplayName("event=\"reject\" 是推導出來的：駁回才投遞，退回不投遞")
        void rejectEventIsDerivedFromComplete() {
            // Flowable 沒有 reject 這個 task event；spec §11.4 的前端選單有。
            // 這個案例釘住「推導規則」而不是「有沒有接線」——
            // 把它誤做成「complete 事件都投遞」是最自然的錯法。
            String key = deploySingleTaskProcess("wh-reject",
                    nodeWithHooks(webhooksElement(webhook("reject", sinkUrl("e"), "POST"))));

            var pi = runtimeService.startProcessInstanceByKey(key, Map.of("businessKey", "WH-5"));
            Task task = taskService.createTaskQuery().processInstanceId(pi.getId()).singleResult();

            // 先退回：approved=false、rejected 沒有值 → 不得投遞
            taskService.complete(task.getId(), Map.of("approved", false));
            assertThat(WebhookTestSink.receivedTo("e"))
                    .as("退回不是駁回（spec 明確分開兩者）—— 投遞了就是規則寫錯了")
                    .isEmpty();

            // 這個流程只有一個關卡，所以另起一個實例做駁回
            var pi2 = runtimeService.startProcessInstanceByKey(key, Map.of("businessKey", "WH-6"));
            Task task2 = taskService.createTaskQuery().processInstanceId(pi2.getId()).singleResult();
            taskService.complete(task2.getId(), Map.of("rejected", true, "rejectReason", "證件不清"));

            awaitDelivery(() -> !WebhookTestSink.receivedTo("e").isEmpty(), "駁回的投遞");
            Map<String, Object> payload = body(WebhookTestSink.receivedTo("e").get(0));
            assertThat(payload)
                    .containsEntry("event", "task.complete")
                    .containsEntry("action", "rejected")
                    .containsEntry("rejectReason", "證件不清");
        }
    }

    // ── 舊格式（向後相容）────────────────────────────────────────────

    @Nested
    @DisplayName("舊格式（documentation）")
    class LegacyFormat {

        @Test
        @DisplayName("寫在 documentation 的舊設定仍然會投遞（既有流程不會壞掉）")
        void legacyDocumentationStillDelivers() {
            // 這是「已部署的流程會不會壞掉」那個問題的答案：
            // 2026-09-30 之前用設計器存過檔的流程，設定在 documentation 裡，
            // 後端仍然讀得到。
            String key = deploySingleTaskProcess("wh-legacy",
                    legacyDoc("f") + nodeWithHooks(""));

            runtimeService.startProcessInstanceByKey(key, Map.of("businessKey", "WH-7"));

            awaitDelivery(() -> !WebhookTestSink.receivedTo("f").isEmpty(), "舊格式的投遞");
            assertThat(body(WebhookTestSink.receivedTo("f").get(0)))
                    .containsEntry("event", "task.create")
                    .containsEntry("businessKey", "WH-7");
        }

        @Test
        @DisplayName("兩種格式並存時以 extensionElements 為準")
        void extensionElementsWinsOverLegacy() {
            // 遷移期間會短暫並存（設計器改寫舊節點之前）。若兩個都被當權威，
            // 使用者會收到<b>兩次</b>通知，而且刪掉新格式那筆之後舊的會復活。
            String key = deploySingleTaskProcess("wh-both",
                    legacyDoc("g-old")
                            + nodeWithHooks(webhooksElement(webhook("create", sinkUrl("g-new"), "POST"))));

            runtimeService.startProcessInstanceByKey(key, Map.of("businessKey", "WH-8"));

            awaitDelivery(() -> !WebhookTestSink.receivedTo("g-new").isEmpty(), "新格式的投遞");
            assertThat(WebhookTestSink.receivedTo("g-old"))
                    .as("舊格式在並存時不得被採用 —— 否則每次都會投兩次")
                    .isEmpty();
        }
    }

    // ── 對照組：沒有設定時什麼都不該發生 ────────────────────────────

    @Nested
    @DisplayName("對照組")
    class ControlGroup {

        @Test
        @DisplayName("沒有 webhook 設定的節點不得投遞，但流程必須照常走完")
        void nodeWithoutWebhookSendsNothing() {
            // 這是整個測試組的錨點：它證明「有投遞」不是環境或計時造成的幻覺。
            // 缺陷期間（有 listener、沒設定、沒接線）這一條本來就會綠，
            // 它的作用是與正向案例成對，把「listener 存在」與「投遞發生」分開。
            String key = deploySingleTaskProcess("wh-none", nodeWithHooks(""));

            var pi = runtimeService.startProcessInstanceByKey(key, Map.of("businessKey", "WH-9"));
            Task task = taskService.createTaskQuery().processInstanceId(pi.getId()).singleResult();
            assertThat(task).as("沒有 webhook 設定的節點仍必須能建立任務").isNotNull();
            taskService.complete(task.getId(), Map.of("approved", true));

            assertThat(runtimeService.createProcessInstanceQuery()
                    .processInstanceId(pi.getId()).singleResult())
                    .as("沒有 webhook 設定不得影響流程推進").isNull();
            assertThat(WebhookTestSink.received())
                    .as("沒有設定就不得有任何投遞")
                    .isEmpty();
        }

        @Test
        @DisplayName("⚠️ SSRF：BPMN 裡的 loopback 位址不得被投遞，而且流程不得因此失敗")
        void loopbackUrlFromBpmnIsRejected() throws InterruptedException {
            // ⚠️ 這一條是「接上線」帶來的新風險，不是既有風險：
            // 投遞位址改成來自 BPMN，而 BPMN 是業務人員在設計器裡編輯的內容。
            // 設定 URL 的人與決定送什麼資料出去的人可能是不同人。
            //
            // WebhookUrlPolicy 的允許清單比對的是 URL 的字面 host。
            // 本類別的設定只列了 localhost，所以 http://127.0.0.1:.../ 必須被擋。
            // 順帶證明「擋下來」不等於「讓流程壞掉」—— fail-closed 於投遞、
            // fail-open 於流程。
            String key = deploySingleTaskProcess("wh-ssrf", nodeWithHooks(webhooksElement(
                    webhook("create", "http://127.0.0.1:" + SERVLET_PORT + "/mock/test-webhook-sink/h", "POST"))));

            var pi = runtimeService.startProcessInstanceByKey(key, Map.of("businessKey", "WH-10"));
            Task task = taskService.createTaskQuery().processInstanceId(pi.getId()).singleResult();
            assertThat(task)
                    .as("URL 被 SSRF 閘門擋下不得讓任務建立失敗")
                    .isNotNull();

            taskService.complete(task.getId(), Map.of("approved", true));
            assertThat(runtimeService.createProcessInstanceQuery()
                    .processInstanceId(pi.getId()).singleResult()).isNull();

            // 被擋下來之後，訊息會被 consumer 丟棄（記 ERROR 後 return，不重試）。
            // 等一段時間確認「它真的沒有被送出」，而不是還沒排隊到。
            Thread.sleep(2000);
            assertThat(WebhookTestSink.receivedTo("h"))
                    .as("不在允許清單內的 loopback 位址不得被投遞（127.0.0.1 ≠ localhost）")
                    .isEmpty();
        }

        @Test
        @DisplayName("啟動時 BPMN 的 webhookTaskListener 真的接上了（setBeans 不可漏）")
        void shippedProcessesResolveTheWebhookListener() throws Exception {
            // 這是斷線 (B) 的靜態版本。若 setBeans() 漏掉 webhookTaskListener，
            // 出貨的兩支流程在第一次送出案件時就會炸 —— 這個測試讓它在那之前紅。
            for (String name : List.of("leave-approval", "purchase-approval")) {
                String xml = new String(getClass().getClassLoader()
                        .getResourceAsStream("processes/" + name + ".bpmn20.xml").readAllBytes(),
                        StandardCharsets.UTF_8);
                assertThat(xml)
                        .as("%s 必須以 delegateExpression 引用 webhookTaskListener", name)
                        .contains("delegateExpression=\"${webhookTaskListener}\"");
                assertThat(xml)
                        .as("%s 不得移除既有的 notifyTaskListener（使用者硬規則）", name)
                        .contains("delegateExpression=\"${notifyTaskListener}\"");
            }

            // 執行期實證：真的跑一次出貨的流程。
            var pi = runtimeService.startProcessInstanceByKey("leave-approval",
                    Map.of("initiator", "user001", "businessKey", "WH-11", "leaveType", "annual", "days", 1));
            assertThat(taskService.createTaskQuery().processInstanceId(pi.getId()).singleResult())
                    .as("出貨流程在 webhookTaskListener 接上後必須照常建立任務")
                    .isNotNull();
            assertThat(WebhookTestSink.received())
                    .as("出貨流程沒有 webhook 設定，不得有任何投遞")
                    .isEmpty();
        }

        @Test
        @DisplayName("出貨的兩支 BPMN 必須仍然通過部署前的 lint（seed-data.sh 會走這條路）")
        void shippedProcessesStillPassLint() throws Exception {
            // ⚠️ 這條是因為 #67 改了出廠 BPMN 才補的，不是因為 lint 有問題。
            //
            // 部署路徑是 POST /api/deployments → BpmnLintService.lint() → 400 就整個拒絕，
            // 而 scripts/seed-data.sh 正是走這條路。整份測試套件裡沒有任何一條
            // 測試用 lint 檢查過出廠的兩支 BPMN，所以「改了 BPMN 卻讓 seed 失敗」
            // 這件事原本抓不到 —— 症狀是 seed-data.sh 在 CI 或 PM 的線上實測
            // 才爆，而那時已經離開本工項的範圍了。
            for (String name : List.of("leave-approval", "purchase-approval")) {
                String xml = new String(getClass().getClassLoader()
                        .getResourceAsStream("processes/" + name + ".bpmn20.xml").readAllBytes(),
                        StandardCharsets.UTF_8);

                var result = lintService.lint(xml);
                assertThat(result.valid())
                        .as("%s 必須通過 lint，否則 seed-data.sh 會被 400 擋下。錯誤：%s",
                                name, result.errors())
                        .isTrue();
            }
        }
    }

    // ── resolver 的讀取規則（不經過 HTTP，測的是規則本身）─────────────

    /**
     * resolver 本身的讀取規則。
     *
     * <h2>⚠️ 為什麼這裡直接呼叫 resolver，而不走「部署 → 跑流程 → 看有沒有投遞」</h2>
     *
     * <p>負向控制組（2026-09-30 實測）證明了原因：把 {@code notify()} 還原成
     * 改動前的內容後，這一組原本<b>全部是綠的</b> —— 因為它們斷言的是
     * 「沒有投遞」，而斷言沒投遞的那個實作只是「整條鏈路沒接上」。
     *
     * <p>也就是說：只靠端到端的負向斷言，resolver 的規則在 listener 壞掉時
     * <b>完全沒有被驗到</b>。所以這裡改成直接呼叫 resolver，
     * 讓規則的驗證與「有沒有接上」互相獨立。
     */
    @Nested
    @DisplayName("設定讀取規則（直接呼叫 resolver）")
    class Resolution {

        private List<WebhookConfig> resolve(String processKey) {
            var pd = repositoryService.createProcessDefinitionQuery()
                    .processDefinitionKey(processKey)
                    .latestVersion().singleResult();
            assertThat(pd).as("流程 %s 必須已部署", processKey).isNotNull();
            return resolver.resolve(pd.getId(), "approve");
        }

        @Test
        @DisplayName("新格式被正確讀出（event／url／method 三個欄位）")
        void readsExtensionElements() {
            deploySingleTaskProcess("wh-r1", nodeWithHooks(webhooksElement(
                    webhook("complete", sinkUrl("r1"), "PUT"),
                    webhook("all", sinkUrl("r2"), "POST"))));

            assertThat(resolve("wh-r1"))
                    .containsExactly(
                            new WebhookConfig("complete", sinkUrl("r1"), "PUT"),
                            new WebhookConfig("all", sinkUrl("r2"), "POST"));
        }

        @Test
        @DisplayName("空的 webhooks 元素代表「清空了」，不得回頭讀舊格式")
        void emptyContainerIsAuthoritative() {
            // 使用者刪掉最後一筆之後，設計器會寫出一個空的 <flowable:webhooks/>。
            // 若 resolver 改成「非空才優先」，剛刪掉的設定會立刻復活。
            //
            // ⚠️ BPMN 的 XSD 規定 <documentation> 必須排在 <extensionElements> 之前 ——
            // 放錯位置的症狀是部署時 SAXParseException（cvc-complex-type.2.4.a），
            // 與 resolver 的行為完全無關，很容易誤判成實作的問題。
            deploySingleTaskProcess("wh-r2", legacyDoc("r3") + nodeWithHooks(webhooksElement()));

            assertThat(resolve("wh-r2"))
                    .as("空的 webhooks 元素是權威的 —— 舊的 documentation 不得讓設定復活")
                    .isEmpty();
        }

        @Test
        @DisplayName("完全沒有 webhooks 元素時才讀舊格式")
        void fallsBackToLegacyOnlyWhenNoContainer() {
            deploySingleTaskProcess("wh-r3", legacyDoc("r4") + nodeWithHooks(""));

            assertThat(resolve("wh-r3"))
                    .as("沒有新格式元素 → 舊格式必須仍然讀得到（這是向後相容的核心）")
                    .containsExactly(new WebhookConfig("create", sinkUrl("r4"), "POST"));
        }

        @Test
        @DisplayName("沒有 url 屬性的 webhook 設定被忽略")
        void webhookWithoutUrlIsIgnored() {
            deploySingleTaskProcess("wh-r4", nodeWithHooks(
                    webhooksElement("<flowable:webhook event=\"create\" method=\"POST\"/>")));

            assertThat(resolve("wh-r4"))
                    .as("沒有 URL 的設定不構成一筆可投遞的設定")
                    .isEmpty();
        }

        @Test
        @DisplayName("壞掉的舊格式不得讓讀取拋例外")
        void brokenLegacyJsonIsIgnored() {
            deploySingleTaskProcess("wh-r5",
                    "<documentation>" + WebhookConfigResolver.LEGACY_DOC_PREFIX
                            + "{這不是合法 JSON</documentation>"
                            + nodeWithHooks(""));

            assertThat(resolve("wh-r5"))
                    .as("設定壞掉時的取捨是「不投遞」，不是「整個流程失敗」")
                    .isEmpty();
        }

        @Test
        @DisplayName("壞掉的舊格式不得讓任務建立失敗（端到端確認）")
        void brokenLegacyJsonDoesNotBreakTheFlow() {
            String key = deploySingleTaskProcess("wh-badlegacy",
                    "<documentation>" + WebhookConfigResolver.LEGACY_DOC_PREFIX
                            + "{這不是合法 JSON</documentation>"
                            + nodeWithHooks(""));

            var pi = runtimeService.startProcessInstanceByKey(key, Map.of("businessKey", "WH-14"));
            assertThat(taskService.createTaskQuery().processInstanceId(pi.getId()).singleResult())
                    .as("設定壞掉時的取捨是「不投遞」，不是「流程失敗」")
                    .isNotNull();
            assertThat(WebhookTestSink.received()).isEmpty();
        }

        @Test
        @DisplayName("不存在的節點／流程 id 回空清單而不是拋例外")
        void missingNodeIsNotAnError() {
            deploySingleTaskProcess("wh-r6", nodeWithHooks(""));

            var pd = repositoryService.createProcessDefinitionQuery()
                    .processDefinitionKey("wh-r6").latestVersion().singleResult();
            assertThat(resolver.resolve(pd.getId(), "no-such-node")).isEmpty();
            assertThat(resolver.resolve(null, "approve")).isEmpty();
            assertThat(resolver.resolve(pd.getId(), null)).isEmpty();
            assertThat(resolver.resolve("no-such-definition:1:999", "approve"))
                    .as("查不到流程定義時不得讓簽核失敗")
                    .isEmpty();
        }
    }
}
