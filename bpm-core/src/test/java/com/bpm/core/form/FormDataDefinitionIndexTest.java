package com.bpm.core.form;

import com.bpm.core.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #59 遺留：{@code bpm_form_data(form_definition_id)} 的索引（V6 migration）。
 *
 * <h2>缺陷形狀是「效能隨資料量靜默劣化」</h2>
 *
 * <p>封存表單前的使用中檢查（{@code FormService.requireNotUsedByRunningProcess}）
 * 先問 {@code FormDataRepository.findDistinctProcessInstanceIdsByFormDefinitionId}：
 * {@code SELECT DISTINCT process_instance_id FROM bpm_form_data WHERE form_definition_id = ?}。
 * 改動前 {@code form_definition_id} 上沒有索引（V1 只建了
 * {@code process_instance_id} 的 {@code idx_form_data_process}），
 * 這個查詢是全表掃描；而 {@code bpm_form_data} 只增不減。
 * 它不會壞、不會拋例外，只會越來越慢 —— 所以只能用「索引真的存在」
 * 來釘住，行為測試在資料量小的測試庫上永遠是綠的。
 *
 * <h2>為什麼直接查 {@code sys.indexes} 而不是跑 EXPLAIN</h2>
 *
 * <p>這裡要驗的是 migration 的<b>產物</b>：索引存在、名稱確定、
 * 鍵欄位正確。查詢計畫的選擇會隨統計值與最佳化器版本變動，
 * 拿它當驗收條件會製造不穩定的紅燈，而不是更嚴格的保證。
 *
 * <h2>負向控制組（2026-10-03 實測）</h2>
 *
 * <p>把 {@code V6__form_data_form_definition_id_index.sql} 暫時改名
 * （等同「這個 migration 不存在」），在全新的測試容器上重跑本類別：
 * <b>1 紅 0 綠</b> —— {@code keyColumnsOf} 回空清單，斷言失敗。
 * 還原檔名後重跑恢復綠燈。這證明本測試測的是 V6 的產物，不是
 * 其他 migration 或 Hibernate 順手建的索引（{@code ddl-auto: none}）。
 */
class FormDataDefinitionIndexTest extends IntegrationTestBase {

    /**
     * 某個索引在 {@code bpm_form_data} 上的鍵欄位（依 key_ordinal 排序）。
     *
     * <p>{@code is_included_column = 0} 把 INCLUDE 欄位排除在外 ——
     * 本 migration 建的是普通索引，沒有 INCLUDE；若日後加了，鍵欄位
     * 仍是這個查詢要看的東西。
     */
    private List<String> keyColumnsOf(String indexName) {
        List<String> columns = new ArrayList<>();
        withFormConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT col.name FROM sys.indexes i "
                            + "JOIN sys.index_columns ic ON ic.object_id = i.object_id "
                            + "AND ic.index_id = i.index_id "
                            + "JOIN sys.columns col ON col.object_id = ic.object_id "
                            + "AND col.column_id = ic.column_id "
                            + "WHERE i.object_id = OBJECT_ID('bpm_form_data') "
                            + "AND i.name = ? AND ic.is_included_column = 0 "
                            + "ORDER BY ic.key_ordinal")) {
                ps.setString(1, indexName);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) columns.add(rs.getString(1));
                }
            }
        });
        return columns;
    }

    @Test
    @DisplayName("#59 遺留：bpm_form_data 必須有 form_definition_id 的索引（封存檢查的 DISTINCT 查詢）")
    void formDefinitionIdIsIndexed() {
        assertThat(keyColumnsOf("idx_form_data_definition"))
                .as("V6 migration 必須建立 idx_form_data_definition(form_definition_id)；"
                        + "沒有它，封存檢查的 DISTINCT 查詢就是全表掃描")
                .containsExactly("form_definition_id");
    }
}
