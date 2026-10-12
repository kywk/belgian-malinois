package com.bpm.core.form;

import org.junit.jupiter.api.DisplayName;
import com.bpm.core.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * seed 資料的字元編碼迴歸測試。
 *
 * <p><b>這個測試的由來。</b>2026-09-28 在 dev 環境實測發現，4 個表單的
 * {@code name} 在 DB 裡全都是 {@code ?????}。根因是 {@code data.sql} 的
 * {@code name} 字面值寫成 {@code '請假申請表'} —— 沒有 {@code N} 前綴。
 * MSSQL 會先把它當成該 DB 定序下的 VARCHAR，非 ASCII 字元在<b>寫入
 * NVARCHAR 欄位之前</b>就已經被換成問號，因此欄位型別正確也救不回來。
 *
 * <p>{@code schema_json} 剛好有 {@code N} 前綴，所以欄位 label（假別、事由…）
 * 一直是正常的 —— 這也是為什麼這個 bug 能長期存在而沒被注意到：
 * 表單畫面看起來完全正常，只有表單「名稱」是問號。
 *
 * <p>這類錯誤無法用 H2 或單元測試抓到，必須對真實 MSSQL 跑真實的 seed 路徑。
 *
 * <p>⚠️ uni-form 重構（V7 migration）後 seed 只剩<b>兩張</b>申請表
 * （review 表已刪除，權限改由 BPMN {@code uniform:access} 表達）；
 * {@code schema_json} 的內容也換成 DSL。這條測試的命題不變：
 * <b>中文必須原樣寫進 NVARCHAR</b>。
 */
class FormSeedEncodingTest extends IntegrationTestBase {

    @Test
    @DisplayName("seed 的表單名稱必須保留中文，不能變成問號")
    void seededFormNamesKeepChinese() {
        List<String> names = new ArrayList<>();
        withFormConnection(c -> {
            // 只查這 2 個 seed formKey，不查全表。
            // 容器是 static 且由所有測試共用，其他測試會新增表單 ——
            // 對全表計數會讓本測試隨執行順序而壞掉。
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT form_key, name FROM bpm_form_definition "
                         + "WHERE form_key IN ('leave-request','purchase-request') "
                         + "ORDER BY form_key")) {
                while (rs.next()) {
                    names.add(rs.getString("form_key") + "=" + rs.getString("name"));
                }
            }
        });

        assertThat(names).as("seed 應建立這 2 個表單定義").hasSize(2);
        assertThat(names)
                .as("表單名稱不得含問號 —— 出現問號就是 migration 少了 N 前綴")
                .noneMatch(n -> n.contains("?"))
                .containsExactly(
                        "leave-request=請假申請表",
                        "purchase-request=採購申請表");
    }

    @Test
    @DisplayName("schema_json 是 DSL 且欄位 label 保留中文")
    void seededSchemaJsonKeepsChinese() {
        withFormConnection(c -> {
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT schema_json FROM bpm_form_definition WHERE form_key = 'leave-request'")) {
                assertThat(rs.next()).isTrue();
                String json = rs.getString(1);
                // 這幾個 label 是前端畫面直接顯示的字串，壞掉會立刻被使用者看到。
                assertThat(json).contains("假別", "請假期間", "事由");
                assertThat(json).doesNotContain("?");
                // V7 不再是扁平 fields[]：schemaJson 的世界只有 DSL。
                assertThat(json).contains("\"$uniform\":\"1.0\"");
                assertThat(json).doesNotContain("\"fields\"");
            }
        });
    }
}
