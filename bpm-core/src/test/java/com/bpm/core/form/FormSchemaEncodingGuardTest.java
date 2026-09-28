package com.bpm.core.form;

import com.bpm.core.form.model.FormDefinition;
import com.bpm.core.form.repository.FormDefinitionRepository;
import org.junit.jupiter.api.DisplayName;
import com.bpm.core.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * form-service 的 schema 字元編碼守衛。理由與 bpm-core 的同名測試相同：
 * DB 定序為 Latin1，VARCHAR 會靜默吃掉中文，而本模組正是第一個被發現
 * 受害的地方（4 個表單名稱全部變成 {@code ?????}）。
 */
class FormSchemaEncodingGuardTest extends IntegrationTestBase {

    /** 刻意允許保留 VARCHAR 的欄位；目前為空。加入前請註明理由。 */
    private static final Set<String> ALLOWED_VARCHAR = Set.of();

    @Autowired
    private FormDefinitionRepository defRepo;

    @Test
    @DisplayName("自有資料表不得留下任何 VARCHAR 欄位")
    void noVarcharColumnsRemain() {
        List<String> offenders = new ArrayList<>();
        withFormConnection(c -> {
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT TABLE_NAME, COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS "
                         + "WHERE DATA_TYPE IN ('varchar','char','text') "
                         + "AND TABLE_NAME LIKE 'bpm[_]%' ORDER BY TABLE_NAME, COLUMN_NAME")) {
                while (rs.next()) offenders.add(rs.getString(1) + "." + rs.getString(2));
            }
        });
        offenders.removeAll(ALLOWED_VARCHAR);
        assertThat(offenders)
                .as("這些欄位是 VARCHAR，在 Latin1 定序下會靜默吃掉中文")
                .isEmpty();
    }

    @Test
    @DisplayName("中文必須能經由 entity 完整寫入並讀回")
    void chineseRoundTripsThroughEntities() {
        FormDefinition d = new FormDefinition();
        d.setFormKey("cn-" + UUID.randomUUID().toString().substring(0, 8));
        d.setName("出差申請表（海外）");
        d.setVersion(1);
        d.setStatus("draft");
        d.setCreatedBy("user001");
        d.setSchemaJson("{\"fields\":[{\"id\":\"dest\",\"label\":\"目的地\"}]}");
        d = defRepo.save(d);

        FormDefinition r = defRepo.findById(d.getId()).orElseThrow();
        assertThat(r.getName()).isEqualTo("出差申請表（海外）");
        assertThat(r.getSchemaJson()).contains("目的地");
    }
}
