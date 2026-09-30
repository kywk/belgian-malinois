package com.bpm.core.repository;

import com.bpm.core.model.ProcessVariableSpec;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface ProcessVariableSpecRepository extends JpaRepository<ProcessVariableSpec, String> {
    List<ProcessVariableSpec> findByProcessDefinitionKeyOrderByVariableName(String processDefinitionKey);

    /**
     * 一次 SQL 刪掉某個流程底下的全部變數規格（#86）。
     *
     * <h2>⚠️ 為什麼不能用衍生刪除（{@code deleteByProcessDefinitionKey}）</h2>
     *
     * <p>衍生刪除是「先 SELECT 出一列列 entity，再對每列呼叫 {@code em.remove()}」。
     * {@code em.remove()} 只是<b>排程</b>一次刪除，SQL 要等到 flush 才送出；
     * 而 Hibernate 的 flush 順序是 <b>INSERT 在 DELETE 之前</b>
     * （{@code ActionQueue} 的既定排序，{@code hibernate.order_updates} 調不動）。
     *
     * <p>「整批取代」這個端點正好是「刪掉全部 → 寫回全部」，
     * 兩批動作在同一個 flush 裡，於是每一筆 INSERT 都撞上
     * 還沒被刪掉的同名舊列，回
     * {@code Violation of UNIQUE KEY constraint 'uk_bpm_process_variable_spec_key_name'} → 500。
     *
     * <p><b>實測（缺陷期間）：</b>對同一個 key 連續呼叫兩次
     * {@code POST /api/admin/process-definitions/{key}/variable-spec}（內容相同），
     * 第一次 200、第二次 500。而前端 {@code ProcessVariableSpecAdmin.vue} 的
     * 「儲存」按鈕正是走這條路徑，所以管理頁第二次按儲存必定壞。
     *
     * <p><b>⚠️ 觸發條件是「新批次與既有規格有同名變數」</b>，不是「key 已有資料」。
     * 新舊完全不重疊、或送空陣列，缺陷期間都是 200（沒有 INSERT 會撞到同名舊列）。
     * 所以手動試一次很容易剛好試在綠的那一側。
     *
     * <h2>為什麼是原生 SQL 刪除而不是「衍生刪除後再 flush()」</h2>
     *
     * <p>{@code deleteBy…} 後面補 {@code flush()} 確實也能讓 DELETE 先落地，
     * 但那是<b>兩條 SQL</b>（N 次 SELECT + N 次 DELETE）而且要依賴呼叫端記得補上
     * {@code flush()} —— 規則散在兩處，下一個人改這個方法時很容易漏掉。
     * 這裡的取捨是<b>讓「刪除」只有一種形狀</b>：原生 SQL 在呼叫時就直接送到資料庫，
     * 完全不進 Hibernate 的動作佇列，INSERT 之後怎麼排序都影響不到它。
     * （與 {@code NotifyAdminController.requireExistingTemplate} 同一個理由：
     * 缺陷本身是規則分散造成的。）
     *
     * <p>⚠️ <b>原生刪除不會經過 persistence context</b>，所以呼叫端若在
     * 同一個交易裡還持有這些 entity，它們會變成過期狀態。
     * 目前唯一的呼叫端 {@code ProcessVariableSpecController.batchSave}
     * 刪除之後只會 {@code saveAll} 全新 entity、且不重用舊 entity，所以安全。
     * <b>新增呼叫端時必須注意這點。</b>
     *
     * <p>刻意<b>不</b>做「比對後只刪真正消失的那幾筆」：那會讓舊列保留原 id，
     * 而 {@code batchSave} 強制 {@code setId(null)} 是 security-audit P0-4 的防護
     * （夾帶 id 會讓 {@code save()} 走 {@code merge()} 覆寫任意資料列）。
     * 保留 id 等於讓那條防護出現一個繞道。
     */
    @Modifying
    @Query("delete from ProcessVariableSpec s where s.processDefinitionKey = :processDefinitionKey")
    void deleteAllByProcessDefinitionKey(@Param("processDefinitionKey") String processDefinitionKey);
}
