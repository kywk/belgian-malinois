package com.bpm.core.security;

import com.bpm.core.model.DocumentRequest;
import com.bpm.core.repository.DocumentRequestRepository;
import com.bpm.core.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.flowable.engine.RuntimeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 公文編號的產生（security-audit P1-11）。
 *
 * <p>審查標記為「<b>無法確定</b>」的一項：
 * {@code @Query("... WHERE d.documentNumber LIKE :prefix%")} —— JPQL 不允許
 * 具名參數後面直接接 {@code %}。可能 bootstrap 就拋 QuerySyntaxException，
 * 也可能退化成等值比對使 seq 永遠為 1。而
 * {@code acceptance-test.sh} 沒有任何 {@code /api/documents} 案例，
 * 這條路徑<b>很可能從未被執行過</b>。
 *
 * <p>本測試先把實際行為釘死（應用能啟動，所以不是 bootstrap 失敗），
 * 再驗證修正後的編號產生是正確且可重複的。
 */
class DocumentNumberTest extends IntegrationTestBase {

    @Autowired
    private DocumentRequestRepository docRepo;

    @Autowired
    private RuntimeService runtimeService;

    private DocumentRequest doc(String number) {
        DocumentRequest d = new DocumentRequest();
        d.setDocumentNumber(number);
        d.setTitle("測試公文 " + number);
        d.setCreatedBy("user001");
        d.setCategory("leave-approval");
        return docRepo.save(d);
    }

    @Test
    @DisplayName("countByPrefix 必須真的做前綴比對（改動前 LIKE :prefix% 語意不明）")
    void countByPrefixActuallyCountsByPrefix() {
        String prefix = "DOC-2026-TESTDEPT";
        doc(prefix + "-001");
        doc(prefix + "-002");
        doc("DOC-2026-OTHERDEPT-001");

        assertThat(docRepo.countByPrefix(prefix))
                .as("必須只數到同前綴的兩筆；退化成等值比對會永遠回 0，"
                        + "而 seq 就永遠是 1 → 第二筆公文必定撞號")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("編號序號必須取最大值而非計數 —— 刪除舊公文後不得重複發號")
    void sequenceUsesMaxNotCount() {
        String prefix = "DOC-2026-SEQDEPT";
        doc(prefix + "-001");
        DocumentRequest second = doc(prefix + "-002");
        doc(prefix + "-003");

        // 刪掉中間一筆：COUNT 會從 3 掉到 2 → 下一個號是 003 → 與既有的撞號
        docRepo.delete(second);

        assertThat(docRepo.maxSequenceForPrefix(prefix))
                .as("用 MAX 取得的下一號不受刪除影響")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("沒有任何同前綴公文時，序號起點為 0")
    void emptyPrefixStartsAtZero() {
        assertThat(docRepo.maxSequenceForPrefix("DOC-2026-NOSUCHDEPT"))
                .isEqualTo(0);
    }

    @Test
    @DisplayName("建立公文：編號不重複，且流程實例必須回填")
    void createAssignsUniqueNumberAndLinksProcess() throws Exception {
        var r1 = mockMvc.perform(post("/api/documents")
                        .header("X-User-Id", "user001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"第一份公文\",\"createdBy\":\"user001\","
                                + "\"category\":\"leave-approval\",\"urgencyLevel\":\"普通\"}"))
                .andExpect(status().isOk()).andReturn();
        var r2 = mockMvc.perform(post("/api/documents")
                        .header("X-User-Id", "user001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"第二份公文\",\"createdBy\":\"user001\","
                                + "\"category\":\"leave-approval\",\"urgencyLevel\":\"普通\"}"))
                .andExpect(status().isOk()).andReturn();

        String n1 = numberOf(r1.getResponse().getContentAsString());
        String n2 = numberOf(r2.getResponse().getContentAsString());
        assertThat(n1).isNotEqualTo(n2);

        // 流程實例必須回填 —— 這同時證明「先存檔後啟流程」的順序有走完
        assertThat(r2.getResponse().getContentAsString())
                .as("processInstanceId 必須回填，否則公文與流程失去關聯")
                .contains("processInstanceId");
        assertThat(docRepo.findAll())
                .filteredOn(d -> n2.equals(d.getDocumentNumber()))
                .singleElement()
                .satisfies(d -> assertThat(d.getProcessInstanceId()).isNotBlank());
    }

    @Test
    @DisplayName("編號已被佔用時必須重試取下一號，而不是 500 並留下孤兒流程")
    void collisionRetriesInsteadOfFailing() throws Exception {
        // 先佔住「下一個會被產生的號」。user001 的部門是 dept001。
        String prefix = "DOC-" + java.time.Year.now().getValue() + "-DEPT001";
        int next = docRepo.maxSequenceForPrefix(prefix) + 1;
        doc(prefix + "-" + String.format("%03d", next));

        long processesBefore = runtimeService.createProcessInstanceQuery().count();

        var res = mockMvc.perform(post("/api/documents")
                        .header("X-User-Id", "user001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"撞號測試\",\"createdBy\":\"user001\","
                                + "\"category\":\"leave-approval\",\"urgencyLevel\":\"普通\"}"))
                .andExpect(status().isOk()).andReturn();

        assertThat(numberOf(res.getResponse().getContentAsString()))
                .as("應取到下一個可用號，而非撞號後 500")
                .isNotEqualTo(prefix + "-" + String.format("%03d", next));

        // 改動前：存檔失敗時流程已啟動且不回滾 → 孤兒流程
        assertThat(runtimeService.createProcessInstanceQuery().count())
                .as("不得因重試而多啟動流程")
                .isEqualTo(processesBefore + 1);
    }

    private static String numberOf(String json) {
        return json.replaceAll(".*\"documentNumber\":\"([^\"]*)\".*", "$1");
    }
}
