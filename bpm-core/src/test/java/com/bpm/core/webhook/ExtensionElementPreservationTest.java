package com.bpm.core.webhook;

import com.bpm.core.support.IntegrationTestBase;
import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.ExtensionElement;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.RepositoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #67 開工前的前置驗證：<b>Flowable 7.2.0 會不會保留它不認識的 extension element。</b>
 *
 * <h2>為什麼這件事決定了整個讀取策略</h2>
 *
 * <p>spec §11.4 要求 webhook 設定「儲存於 BPMN XML {@code extensionElements} 中」。
 * 拿到設定有兩條路，選哪一條完全取決於這個答案：
 *
 * <ol>
 *   <li><b>{@code RepositoryService.getBpmnModel(id)}</b> —— 走 Flowable 自己的
 *       {@code BpmnXMLConverter}。只有在 converter 願意把未知元素保留下來時才可用。
 *       好處是拿到的是型別化的 {@link UserTask}/{@link Process}/{@link ExtensionElement}。</li>
 *   <li><b>{@code getResourceAsStream(deploymentId, resourceName)}</b> —— 直接讀
 *       {@code ACT_GE_BYTEARRAY} 裡那份原始位元組，用 StAX/DOM 自己解析。
 *       永遠可用（部署時存的就是原始位元組），但要自己處理 BPMN 的所有形狀。</li>
 * </ol>
 *
 * <p>如果答案是「會丟」，就只能走 (2)，而 (2) 意味著自己寫一個 BPMN 剖析器 ——
 * 那不是 #67 該承擔的範圍。所以這個測試是<b>規格，不是實作的附屬品</b>：
 * 它把「可以走 (1)」這個前提釘住，未來任何人升級 Flowable（Stage 5 的 7→8）
 * 一旦這個前提失效，這裡會先紅。
 *
 * <h2>驗證方式（2026-09-30 實測）</h2>
 *
 * <p>不是讀原始碼猜的，是真的部署一份含未知 extension element 的 BPMN，然後
 * <b>三條路各走一次</b>：
 *
 * <ol>
 *   <li>資料庫原始位元組（{@code getResourceAsStream}）—— 確認部署存的是原封不動的 XML，</li>
 *   <li>{@code getBpmnModel} —— 確認解析後未知元素<b>仍在</b>
 *       （{@code ExtensionElementsParser} 的 else 分支 → {@code parseExtensionElement}，
 *       以及 {@code BpmnXMLUtil.parseChildElements} 的 {@code inExtensionElements} 分支），</li>
 *   <li>{@code BpmnXMLConverter.convertToXML} —— 確認<b>重新輸出</b>時也在
 *       （{@code BpmnXMLUtil.writeExtensionElements}）。</li>
 * </ol>
 *
 * <p>第 3 條是最容易被忽略的：解析進得去不代表吐得出來，而 Flowable 的
 * {@code convertToXML} 是重點，因為 {@code ProcessDiagram}／匯出等路徑會用到它。
 *
 * <h2>順手釘住兩件會被「修好別的東西」弄壞的事</h2>
 *
 * <ul>
 *   <li><b>已知的 {@code flowable:taskListener} 必須仍然被轉成型別化物件</b>，
 *       不能因為我們新增了別的 extension element 就掉進 generic 分支。</li>
 *   <li><b>{@code <documentation>} 必須仍然可讀</b> —— 那是舊格式的相容性來源
 *       （見 {@link WebhookConfigResolver}）。如果哪天它也讀不到了，
 *       舊流程的設定會無聲地變成「沒有設定」。</li>
 * </ul>
 */
class ExtensionElementPreservationTest extends IntegrationTestBase {

    @Autowired
    private RepositoryService repositoryService;

    /**
     * 含三種 extension element 的最小流程：
     * <ul>
     *   <li>{@code flowable:taskListener} —— Flowable <b>認識</b>的（→ 型別化 {@code FlowableListener}）</li>
     *   <li>{@code flowable:webhooks/flowable:webhook} —— Flowable <b>不認識</b>的（→ {@code ExtensionElement}）</li>
     *   <li>{@code documentation} —— 舊格式的相容性來源</li>
     * </ul>
     * 最後一個 userTask 故意<b>完全沒有</b> extension element，
     * 當作「沒有設定就是沒有設定」的對照組 —— 避免測試只證明了「有東西時找得到」。
     */
    private static final String PROBE_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:flowable="http://flowable.org/bpmn"
                         targetNamespace="http://bpm.com/probe">
              <process id="extprobe" name="extension element 保留探測" isExecutable="true">
                <startEvent id="start"/>
                <sequenceFlow id="f1" sourceRef="start" targetRef="withHooks"/>
                <userTask id="withHooks" name="有設定的關卡"
                          flowable:assignee="kermit">
                  <documentation>__webhooks__:[{"event":"create","url":"https://legacy.example/old","method":"POST"}]</documentation>
                  <extensionElements>
                    <flowable:taskListener event="create" delegateExpression="${notifyTaskListener}"/>
                    <flowable:webhooks>
                      <flowable:webhook event="create" url="https://hooks.example/a" method="POST"/>
                      <flowable:webhook event="complete" url="https://hooks.example/b" method="PUT"/>
                    </flowable:webhooks>
                  </extensionElements>
                </userTask>
                <sequenceFlow id="f2" sourceRef="withHooks" targetRef="withoutHooks"/>
                <userTask id="withoutHooks" name="沒有設定的關卡" flowable:assignee="kermit"/>
                <sequenceFlow id="f3" sourceRef="withoutHooks" targetRef="end"/>
                <endEvent id="end"/>
              </process>
            </definitions>
            """;

    private Process deploy() {
        // 用唯一的 deployment 名稱避免與其他測試的部署互相覆蓋
        // （測試共用同一組 static 容器，ACT_RE_DEPLOYMENT 是共用的）。
        var deployment = repositoryService.createDeployment()
                .name("extprobe-" + System.nanoTime())
                .addString("extprobe.bpmn20.xml", PROBE_XML)
                .deploy();
        BpmnModel model = repositoryService.getBpmnModel(
                repositoryService.createProcessDefinitionQuery()
                        .deploymentId(deployment.getId())
                        .singleResult()
                        .getId());
        // ⚠️ Flowable 7 的 BpmnModel.getProcess(String) 收的是「池（pool）id」，
        // 不是 process id —— 傳 process id 會拿到 null。沒有 pool 時要另外找。
        // （這是寫 WebhookConfigResolver 時踩到的，寫在這裡避免下一個人再踩。）
        return model.getProcesses().stream()
                .filter(p -> "extprobe".equals(p.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("BpmnModel 找不到 process extprobe，實際有 "
                        + model.getProcesses().stream().map(org.flowable.bpmn.model.Process::getId).toList()));
    }

    // ── (1) 儲存層：部署存的是原封不動的位元組 ──────────────────────

    @Test
    @DisplayName("部署時存的 BPMN 資源是原始位元組，未知 extension element 原封不動")
    void deploymentStoresOriginalBytes() throws Exception {
        var deployment = repositoryService.createDeployment()
                .name("extprobe-raw-" + System.nanoTime())
                .addString("extprobe-raw.bpmn20.xml", PROBE_XML)
                .deploy();

        String stored;
        try (InputStream in = repositoryService.getResourceAsStream(
                deployment.getId(), "extprobe-raw.bpmn20.xml")) {
            assertThat(in).as("部署後必須讀得回資源").isNotNull();
            stored = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        // 這一條決定了「最壞情況下還有路可走」：即使 converter 哪天不保留未知元素，
        // 資料庫裡的原始 XML 仍然完整，讀取策略可以退回去自己解析。
        assertThat(stored).contains("<flowable:webhooks>");
        assertThat(stored).contains("url=\"https://hooks.example/a\"");
        assertThat(stored).contains("__webhooks__:");
    }

    // ── (2) 解析層：getBpmnModel 必須保留未知元素 ────────────────────

    @Test
    @DisplayName("getBpmnModel() 保留 Flowable 不認識的 extension element 及其子元素與屬性")
    void getBpmnModelKeepsUnknownExtensionElements() {
        Process process = deploy();
        UserTask task = (UserTask) process.getFlowElement("withHooks");

        List<ExtensionElement> webhooks = task.getExtensionElements().get("webhooks");
        assertThat(webhooks)
                .as("未知 extension element 必須仍在 —— 若這條紅了，#67 的讀取策略"
                        + "必須改成自己解析 ACT_GE_BYTEARRAY 的原始位元組")
                .isNotNull()
                .hasSize(1);

        List<ExtensionElement> hooks = webhooks.get(0).getChildElements().get("webhook");
        assertThat(hooks).as("子元素也必須保留（陣列語義在子元素層）").hasSize(2);
        // ⚠️ 屬性一律是無前置的（<flowable:webhook event="...">），
        // 因此 namespace 必須傳 null —— BaseElement.getAttributeValue 用
        // 「namespace 相等」比對，傳錯會拿到 null 而看起來像「屬性被丟了」。
        assertThat(hooks).extracting(h -> h.getAttributeValue(null, "event"))
                .containsExactly("create", "complete");
        assertThat(hooks).extracting(h -> h.getAttributeValue(null, "url"))
                .containsExactly("https://hooks.example/a", "https://hooks.example/b");
        assertThat(hooks).extracting(h -> h.getAttributeValue(null, "method"))
                .containsExactly("POST", "PUT");

        // 對照組：沒有 extension element 的節點必須真的沒有。
        // 少了這條，一個「把所有節點都當成有設定」的實作也會讓上面那條綠。
        FlowElement bare = process.getFlowElement("withoutHooks");
        assertThat(bare.getExtensionElements())
                .as("沒有設定的節點不得憑空生出設定")
                .isEmpty();
    }

    @Test
    @DisplayName("getBpmnModel() 仍把 flowable:taskListener 當成已知元素處理（不是 generic）")
    void knownFlowableElementsAreStillTyped() {
        Process process = deploy();
        UserTask task = (UserTask) process.getFlowElement("withHooks");

        // 如果 taskListener 也掉進 generic 分支，delegateExpression 就不會被解析，
        // 整套通知機制會在部署後才壞掉。這是「不要修壞別人」的對照組。
        assertThat(task.getTaskListeners())
                .as("已知的 flowable:taskListener 必須仍是型別化的 FlowableListener")
                .hasSize(1);
        assertThat(task.getTaskListeners().get(0).getImplementation())
                .isEqualTo("${notifyTaskListener}");
    }

    @Test
    @DisplayName("getBpmnModel() 仍保留 <documentation>（舊格式的相容性來源）")
    void documentationIsStillReadable() {
        Process process = deploy();
        UserTask task = (UserTask) process.getFlowElement("withHooks");

        // 舊格式（WebhookProps.js 在 2026-09-30 之前寫入 documentation）
        // 只能從這裡讀。若這條紅了，向後相容就是假的。
        assertThat(task.getDocumentation())
                .as("documentation 是舊格式 webhook 設定的唯一來源")
                .isNotNull()
                .startsWith("__webhooks__:");
    }

    // ── (3) 輸出層：convertToXML 必須吐得出來 ───────────────────────

    @Test
    @DisplayName("convertToXML() 重新輸出時未知 extension element 仍在")
    void convertToXmlKeepsUnknownExtensionElements() {
        var deployment = repositoryService.createDeployment()
                .name("extprobe-out-" + System.nanoTime())
                .addString("extprobe-out.bpmn20.xml", PROBE_XML)
                .deploy();
        BpmnModel model = repositoryService.getBpmnModel(
                repositoryService.createProcessDefinitionQuery()
                        .deploymentId(deployment.getId()).singleResult().getId());

        String out = new String(new BpmnXMLConverter().convertToXML(model), StandardCharsets.UTF_8);

        // 「解析進得去」不等於「吐得出來」。這條紅了代表匯出／繪圖路徑會安靜地
        // 弄掉 webhook 設定 —— 匯出後再部署就沒有設定了。
        // 命名空間前綴在重新輸出時是重新宣告的（moddle 決定用哪個前綴），
        // 因此斷言 local name 與屬性值，而不是整個 tag 字串。
        assertThat(out).contains("webhooks");
        assertThat(out).contains("https://hooks.example/a");
        assertThat(out).contains("https://hooks.example/b");
        assertThat(out).contains("taskListener")
                .as("已知的 flowable:taskListener 也必須在重新輸出時存活 —— "
                        + "否則「匯出後再部署」會把通知機制整個刪掉");
    }
}
