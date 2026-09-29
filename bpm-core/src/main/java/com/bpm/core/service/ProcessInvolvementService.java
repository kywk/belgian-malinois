package com.bpm.core.service;

import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 「我參與的案件」——以查詢為驅動，而不是逐一檢查每個案件（#71）。
 *
 * <h2>為什麼不做成「列出所有案件再對每個案件跑 isParticipant」</h2>
 *
 * <p>那個寫法看起來最直接，但它是 N+1 而且乘以三：{@code isParticipant} 對一個
 * 案件要做 1 次實例查詢 + 1 次變數查詢 + 1 次 runtime 任務計數 + 1 次歷史任務計數。
 * 一頁 20 筆就是 80 次資料庫往返，而且<b>成本與公司規模成正比</b> ——
 * 全公司 100 個執行中案件、1000 個歷史案件時，這個端點會送出上千次查詢，
 * 然後把使用者自己的結果埋在最後。
 *
 * <p>正確做法是把查詢方向反過來：<b>先問「我參與了哪些任務」，再用那些
 * processInstanceId 去撈案件</b>。查詢次數變成固定的 4 次，與資料量無關。
 *
 * <h2>⚠️ 這是全表掃描（實測確認）</h2>
 *
 * <p>{@code ACT_RU_TASK} 與 {@code ACT_HI_TASKINST} <b>沒有 {@code USER_ID_} 或
 * {@code ASSIGNEE_} 的索引</b>（已從 Flowable 的 mssql 建表 SQL 確認），
 * 而 {@code taskInvolvedUser} 是走 identity link 的 {@code USER_ID_} 比對 ——
 * 也就是 {@code ACT_HI_IDENTITYLINK}。因此 {@code historicInstanceIdsTouchedBy}
 * 會掃 {@code ACT_HI_TASKINST ⋈ ACT_HI_IDENTITYLINK}。
 *
 * <p>⚠️ <b>實測（Testcontainers 的 MSSQL 2022，直接灌 {@code ACT_HI_TASKINST}
 * + {@code ACT_HI_IDENTITYLINK} 後量測各段查詢的平均耗時）：</b>
 *
 * <pre>
 *   表格筆數      taskInvolvedUser（本類別的驅動查詢）   其他三段查詢
 *    10,000              44 ms                              2 ms
 *    50,000             222 ms                              3 ms
 * </pre>
 *
 * <p>也就是<b>線性成長</b>，且是這個端點<b>唯一</b>的全表掃描 ——
 * 其餘三次都走 {@code ID_} 主鍵或變數索引，資料量增加時完全不變。
 * 10,000 筆歷史任務時它已經佔端點總耗時的九成以上。
 *
 * <p>取捨：目前<b>不</b>為它加索引（Flowable 的 schema 升級會覆蓋手動 DDL，
 * 索引必須跟著引擎版本走，不能自己釘一個會在某次升級後默默消失的索引），
 * 也<b>不</b>加分頁。理由是參與清單的結果集本來就隨使用者的參與度線性成長 ——
 * 對參與 50 張單的人回 50 筆是合理的，真正離譜的是「參與 50,000 張單」，
 * 而那種人的使用模式本身就該是「只看未處理」而不是「列出歷史全部」。
 * 若資料量成長到需要處理，正確的方向是<b>限制回傳筆數並告知呼叫端</b>
 * （讓前端顯示「僅顯示最近 N 筆，共 M 筆」），而不是偷偷截斷 ——
 * 截斷會讓人以為那就是全部，那是本專案已經付出過代價的錯誤取捨
 * （見 {@code DuplicateApprovalFilterTest}）。
 * 唯一現在就必須處理的是 {@code IN (…)} 的參數上限，見 {@link #partition}。
 */
@Service
public class ProcessInvolvementService {

    /**
     * {@code IN (?, ?, …)} 的分批大小。
     *
     * <p>MSSQL 的參數上限是 2100。一個參與過上萬張單的使用者，
     * 其 processInstanceId 清單會直接超過這個數 → SQL 錯誤 → 整個端點 500。
     * 所以分批是<b>正確性需求</b>，不是效能優化。
     */
    private static final int ID_BATCH = 1000;

    private final TaskService taskService;
    private final RuntimeService runtimeService;
    private final HistoryService historyService;

    public ProcessInvolvementService(TaskService taskService, RuntimeService runtimeService,
                                     HistoryService historyService) {
        this.taskService = taskService;
        this.runtimeService = runtimeService;
        this.historyService = historyService;
    }

    /**
     * 呼叫者參與過的<b>歷史</b>案件 id（含仍在執行中的）。
     *
     * <p>刻意用歷史表當驅動表而不是 {@code ACT_RU_TASK}：使用者在第一關按完
     * 「同意」之後，那個任務就從 runtime 移走了。若以 runtime 任務為驅動，
     * 「我已經審過、案子現在在別人的關卡」這個最常見的情境會整個消失。
     *
     * <p>反向也成立：每個 runtime 任務在建立當下就有一筆 {@code ACT_HI_TASKINST}，
     * 所以這個集合<b>包含</b>目前仍開著的任務（包含候選人身份連結）。
     */
    public Set<String> historicInstanceIdsTouchedBy(String callerId) {
        Set<String> ids = new LinkedHashSet<>();
        for (HistoricTaskInstance t : historyService.createHistoricTaskInstanceQuery()
                .taskInvolvedUser(callerId).list()) {
            if (t.getProcessInstanceId() != null) ids.add(t.getProcessInstanceId());
        }
        return ids;
    }

    /**
     * 執行中、由呼叫者發起（含 R-20 代發）的案件 id。
     *
     * <p>對應 {@code isParticipant} 的條件 1。若只用「參與過的任務」當驅動，
     * 會漏掉一個情境：申請人還沒送出第一關、但這張單本來就是他的。
     */
    public Set<String> runningInstanceIdsInitiatedBy(String callerId) {
        Set<String> ids = new LinkedHashSet<>();
        for (var pi : runtimeService.createProcessInstanceQuery()
                .variableValueEquals("initiator", callerId).list()) {
            ids.add(pi.getProcessInstanceId());
        }
        for (var pi : runtimeService.createProcessInstanceQuery()
                .variableValueEquals(InitialAssigneeResolver.ON_BEHALF_OF_VAR, callerId).list()) {
            ids.add(pi.getProcessInstanceId());
        }
        return ids;
    }

    /**
     * 歷史（含執行中）由呼叫者發起或代發的案件 id。
     *
     * <p>用 {@code variableValueEquals} 而不是在 Java 端過濾變數：
     * 那個過濾會把所有案件的變數都拉回記憶體，正是這次要消掉的 N+1。
     */
    public Set<String> historicInstanceIdsInitiatedBy(String callerId) {
        Set<String> ids = new LinkedHashSet<>();
        for (var pi : historyService.createHistoricProcessInstanceQuery()
                .variableValueEquals("initiator", callerId).list()) {
            ids.add(pi.getId());
        }
        for (var pi : historyService.createHistoricProcessInstanceQuery()
                .variableValueEquals(InitialAssigneeResolver.ON_BEHALF_OF_VAR, callerId).list()) {
            ids.add(pi.getId());
        }
        return ids;
    }

    /**
     * 呼叫者參與的案件 id（執行中）。
     *
     * <p>與 {@link com.bpm.core.security.ProcessAccessGuard#isParticipant} 的
     * 三個條件<b>刻意保持一致</b>，否則會出現「列表看得到、點進去 404」
     * 或反過來 —— 那比其中任何一個錯誤都更難讓使用者自行判斷。
     */
    public Set<String> involvedRunningInstanceIds(String callerId) {
        Set<String> ids = new LinkedHashSet<>(historicInstanceIdsTouchedBy(callerId));
        ids.addAll(runningInstanceIdsInitiatedBy(callerId));
        return ids;
    }

    /** 呼叫者參與的案件 id（執行中 + 已結束）。 */
    public Set<String> involvedHistoricInstanceIds(String callerId) {
        Set<String> ids = new LinkedHashSet<>(historicInstanceIdsTouchedBy(callerId));
        ids.addAll(historicInstanceIdsInitiatedBy(callerId));
        return ids;
    }

    /**
     * 執行中的流程實例，依 id 分批撈回。
     *
     * <p>查詢本身即為「仍在執行中」的交集，不需要另外過濾。
     */
    public List<org.flowable.engine.runtime.ProcessInstance> findRunning(Collection<String> ids) {
        List<org.flowable.engine.runtime.ProcessInstance> out = new ArrayList<>();
        for (List<String> batch : partition(ids)) {
            // ⚠️ 方法名是 processInstanceIds（不是 processInstanceIdIn）：
            // 已用 javap 對 flowable-engine-7.2.0 的 ProcessInstanceQuery 確認，
            // 而且它收的是 Set 不是 List。
            out.addAll(runtimeService.createProcessInstanceQuery()
                    .processInstanceIds(new java.util.LinkedHashSet<>(batch))
                    .orderByStartTime().desc()
                    .list());
        }
        return out;
    }

    /** 歷史流程實例（執行中 + 已結束），依 id 分批撈回。 */
    public List<org.flowable.engine.history.HistoricProcessInstance> findHistoric(Collection<String> ids) {
        List<org.flowable.engine.history.HistoricProcessInstance> out = new ArrayList<>();
        for (List<String> batch : partition(ids)) {
            out.addAll(historyService.createHistoricProcessInstanceQuery()
                    .processInstanceIds(new java.util.LinkedHashSet<>(batch))
                    .orderByProcessInstanceStartTime().desc()
                    .list());
        }
        return out;
    }

    /**
     * 這些案件目前的未完成任務（一次撈回，供呼叫端組 currentTask）。
     *
     * <p>不逐案查詢：{@code GET /api/process-instances} 目前是每個案件一次
     * 任務查詢（那是既有端點的 N+1，不在本次範圍）。新端點刻意一次撈回 ——
     * 反正我們已經知道所有 id 了。
     */
    public List<org.flowable.task.api.Task> findOpenTasks(Collection<String> processInstanceIds) {
        List<org.flowable.task.api.Task> out = new ArrayList<>();
        for (List<String> batch : partition(processInstanceIds)) {
            out.addAll(taskService.createTaskQuery()
                    .processInstanceIdIn(batch)
                    .orderByTaskCreateTime().asc()
                    .list());
        }
        return out;
    }

    /** 依 {@link #ID_BATCH} 分批。空集合回空清單（{@code IN ()} 會讓 MSSQL 直接報錯）。 */
    static List<List<String>> partition(Collection<String> ids) {
        List<List<String>> batches = new ArrayList<>();
        if (ids == null || ids.isEmpty()) return batches;
        List<String> current = new ArrayList<>();
        for (String id : ids) {
            current.add(id);
            if (current.size() >= ID_BATCH) {
                batches.add(current);
                current = new ArrayList<>();
            }
        }
        if (!current.isEmpty()) batches.add(current);
        return batches;
    }
}
