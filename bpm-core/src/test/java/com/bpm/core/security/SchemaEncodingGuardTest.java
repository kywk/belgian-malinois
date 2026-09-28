package com.bpm.core.security;

import com.bpm.core.model.DocumentRequest;
import com.bpm.core.model.FileAttachment;
import com.bpm.core.model.NotifyTemplate;
import com.bpm.core.model.ProcessVariableSpec;
import com.bpm.core.repository.DocumentRequestRepository;
import com.bpm.core.repository.FileAttachmentRepository;
import com.bpm.core.repository.NotifyTemplateRepository;
import com.bpm.core.repository.ProcessVariableSpecRepository;
import com.bpm.core.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * schema 的字元編碼守衛。
 *
 * <p><b>這組測試存在的理由。</b>三個 DB 的定序是
 * {@code SQL_Latin1_General_CP1_CI_AS}，而 Hibernate 預設把 String 對映成
 * {@code VARCHAR}。中文寫入 VARCHAR 時會被<b>靜默</b>換成問號 ——
 * 沒有例外、沒有警告、沒有錯誤日誌。
 *
 * <p>這個缺陷已經實際造成三次損害：表單名稱全部變 {@code ?????}、
 * 通知信主旨全部亂碼、以及稽核的 {@code operator_name} 中文姓名損毀後，
 * 由於 v2 hash 涵蓋該欄位，{@code integrityCheck} 把<b>每一筆</b>都誤報為
 * 遭篡改。因為完全沒有錯誤訊號，它每次都是在別的地方壞掉才被發現。
 *
 * <p>因此這裡用兩道守衛：
 * <ol>
 *   <li>{@link #noVarcharColumnsRemain()} —— schema 層。掃描我們自有的表，
 *       任何 VARCHAR 欄位都讓測試失敗。新增欄位若寫成 VARCHAR（或漏了
 *       migration）會立刻被擋下，而不是等到某個使用者輸入中文才發現。</li>
 *   <li>{@link #chineseRoundTripsThroughEntities()} —— 行為層。實際寫入中文
 *       並讀回比對，確認 entity → JDBC → 欄位 → 讀回這條路真的不掉字。</li>
 * </ol>
 *
 * <p>Flowable 自己的 {@code ACT_*}／{@code FLW_*} 不在檢查範圍 ——
 * 那些由 Flowable 建立且本來就是 NVARCHAR。
 */
class SchemaEncodingGuardTest extends IntegrationTestBase {

    /**
     * 刻意允許保留 VARCHAR 的欄位。
     *
     * <p>目前是空的：全部轉為 NVARCHAR，因為 sendStringParametersAsUnicode
     * 預設為 true，NVARCHAR 參數對 VARCHAR 欄位比較會讓索引無法 seek，
     * 純 ASCII 欄位留 VARCHAR 反而有效能代價。
     *
     * <p>若日後真有欄位需要留 VARCHAR（例如極大量、確定純 ASCII 且不參與
     * 字串比較的欄位），加進這裡並在此註明理由 —— 讓例外是一個明確的決定，
     * 而不是一次遺漏。
     */
    private static final Set<String> ALLOWED_VARCHAR = Set.of();

    @Autowired
    private DataSource primaryDataSource;

    @Autowired
    private NotifyTemplateRepository templateRepo;

    @Autowired
    private DocumentRequestRepository docRepo;

    @Autowired
    private FileAttachmentRepository attachRepo;

    @Autowired
    private ProcessVariableSpecRepository specRepo;

    private static List<String> varcharColumns(Connection c) throws Exception {
        List<String> found = new ArrayList<>();
        String sql = """
                SELECT TABLE_NAME, COLUMN_NAME
                  FROM INFORMATION_SCHEMA.COLUMNS
                 WHERE DATA_TYPE IN ('varchar', 'char', 'text')
                   AND TABLE_NAME LIKE 'bpm[_]%'
                 ORDER BY TABLE_NAME, COLUMN_NAME
                """;
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                found.add(rs.getString(1) + "." + rs.getString(2));
            }
        }
        return found;
    }

    @Test
    @DisplayName("自有資料表不得留下任何 VARCHAR 欄位（core 與 audit 兩個 DB）")
    void noVarcharColumnsRemain() throws Exception {
        List<String> offenders = new ArrayList<>();
        try (Connection c = primaryDataSource.getConnection()) {
            offenders.addAll(varcharColumns(c));
        }
        withAuditConnection(c -> offenders.addAll(varcharColumns(c)));

        offenders.removeAll(ALLOWED_VARCHAR);
        assertThat(offenders)
                .as("這些欄位是 VARCHAR，在 Latin1 定序下會靜默吃掉中文。"
                        + "若確定要保留，請加入 ALLOWED_VARCHAR 並註明理由")
                .isEmpty();
    }

    @Test
    @DisplayName("中文必須能經由 entity 完整寫入並讀回（不得變成問號）")
    void chineseRoundTripsThroughEntities() {
        String cn = "陳小明－採購申請（緊急）";

        NotifyTemplate t = new NotifyTemplate();
        t.setName("中文模板名稱");
        t.setChannel("EMAIL");
        t.setSubjectTemplate("【BPM】您有新的待辦事項");
        t.setBodyTemplate("內容");
        t = templateRepo.save(t);
        NotifyTemplate rt = templateRepo.findById(t.getId()).orElseThrow();
        assertThat(rt.getName()).isEqualTo("中文模板名稱");
        assertThat(rt.getSubjectTemplate())
                .as("通知信主旨曾經全是亂碼 —— 這是該缺陷最直接的使用者可見後果")
                .isEqualTo("【BPM】您有新的待辦事項");

        DocumentRequest d = new DocumentRequest();
        d.setTitle(cn);
        d.setCategory("採購類");
        d.setUrgencyLevel("緊急");
        // document_number 只有 30 字元，不能塞完整 UUID
        d.setDocumentNumber("DOC-T-" + UUID.randomUUID().toString().substring(0, 8));
        d.setCreatedBy("user001");
        d = docRepo.save(d);
        DocumentRequest rd = docRepo.findById(d.getId()).orElseThrow();
        assertThat(rd.getTitle()).as("公文標題").isEqualTo(cn);
        assertThat(rd.getCategory()).isEqualTo("採購類");
        assertThat(rd.getUrgencyLevel()).isEqualTo("緊急");

        FileAttachment a = new FileAttachment();
        a.setProcessInstanceId("proc-cn");
        a.setFileName("請假申請單－陳小明.pdf");
        a.setFilePath("/app/uploads/proc-cn/uuid");
        a.setFileSize(1L);
        a.setUploadedBy("user001");
        a = attachRepo.save(a);
        assertThat(attachRepo.findById(a.getId()).orElseThrow().getFileName())
                .as("中文檔名在企業環境極常見")
                .isEqualTo("請假申請單－陳小明.pdf");

        ProcessVariableSpec s = new ProcessVariableSpec();
        s.setProcessDefinitionKey("leave-approval");
        s.setVariableName("cnSpec" + UUID.randomUUID().toString().substring(0, 8));
        s.setVariableType("string");
        s.setRequired(false);
        s.setDescription("請假天數，須為正整數");
        s.setExample("三天");
        s = specRepo.save(s);
        ProcessVariableSpec rs = specRepo.findById(s.getId()).orElseThrow();
        assertThat(rs.getDescription()).isEqualTo("請假天數，須為正整數");
        assertThat(rs.getExample()).isEqualTo("三天");
    }
}
