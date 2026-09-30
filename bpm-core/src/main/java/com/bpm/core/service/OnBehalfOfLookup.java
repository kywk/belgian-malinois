package com.bpm.core.service;

import com.bpm.core.external.ExternalActorIdentity;
import org.flowable.engine.HistoryService;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * 「這張單是代誰發起的」——審核人端的代發標示（#68b）。
 *
 * <h2>為什麼審核人端需要知道</h2>
 *
 * <p>R-20 的規則是：外部系統發起的案件 {@code initiator} 一律是
 * {@code system:<id>}，要代某位員工發起就帶 {@code onBehalfOf}。
 * 結果是<b>主管在待辦清單裡看到的是「主管審核」，而這張單沒有申請人</b> ——
 * {@code initiator} 是 {@code system:erp}，通知信上寫的是系統，
 * 「我的申請」裡也不出現（那裡比對的是 {@code initiator} 或 {@code onBehalfOf}）。
 *
 * <p>於是主管無從判斷該問誰補件。產品上要的是「代某某發起」四個字。
 *
 * <h2>⚠️ 授權：這個欄位<b>不是</b>新的揭露</h2>
 *
 * <p>本工項之前，審核人<b>已經</b>讀得到同一個值：{@code GET
 * /api/process-instances/{id}/variables} 走 {@code requireReadAccess}
 * （關係人 ∪ {@code audit:log:read}，旁路留痕），而它回傳的是
 * {@code runtimeService.getVariables(id)} —— <b>整包流程變數</b>，
 * 裡面就有 {@code onBehalfOf}、{@code initiator}（= {@code system:<id>}）
 * 與 {@code _externalSystemId}。
 *
 * <p>也就是說 {@code DocumentDetail.vue} 每次打開案件，那三個值都已經送到
 * 審核人的瀏覽器裡了。本類別做的事是<b>把它們移到值���該出現的地方</b>
 * （列表與時間軸），不是新增一條讀取路徑。
 *
 * <h3>把值放在哪裡是政策決定，這裡只實作了其中一項</h3>
 *
 * <p>{@code onBehalfOf}（<b>人</b>）放行，理由見上：第一位審核人就是那位員工
 * 的直屬主管（{@code InitialAssigneeResolver}），而同一個人本來就能讀到
 * 整包變數。
 *
 * <p>但 {@code initiator}（<b>哪一套外部系統</b>，即 {@code system:<id>}）
 * <b>刻意沒有</b>加進任務 DTO。它回答的是「這張單是哪個合作方的系統送進來的」，
 * 對「該問誰補件」毫無幫助，卻多一個揭露面（能讀這張單的人同時知道該員工
 * 與某家外部系統有往來）。要顯示它是產品決定，見本工項的報告；
 * 技術上它隨時可加，因為它已經在 {@code /variables} 裡了。
 *
 * <h2>⚠️ 系統身分必須被濾掉（與 {@code ApplicantResolver} 同一條防線）</h2>
 *
 * <p>{@code ExternalApiController.startProcess} 會用
 * {@code orgService.getDirectManager(onBehalfOf)} 驗證 {@code onBehalfOf}
 * 是組織系統認識的人，所以正常呼叫路徑下它不可能是 {@code system:*}。
 * 但這裡仍然濾掉，理由與 {@code ApplicantResolver} 的防禦相同：
 * <b>寫入端的驗證是「目前」成立的事實，顯示端不該把它當成不變式</b>。
 * 而且顯示端的下場更難看 ——「代 system:evil 發起」是一句會被當真的話。
 *
 * <h2>為什麼用「歷史變數」查詢而不是 runtime 變數</h2>
 *
 * <p>{@code HistoricVariableInstanceQuery} 同時涵蓋<b>執行中</b>與<b>已結束</b>
 * 的實例（{@code ProcessInvolvementService} 的類別註解已記載這件事：
 * {@code historicInstanceIdsInitiatedBy} 就是拿它當「歷史（含執行中）」用）。
 *
 * <p>所以 {@code /api/tasks}（任務一定在執行中）與 {@code /api/history/tasks}
 * （可能已結案）<b>共用同一段查詢</b> —— 規則只能有一份，
 * 而「執行中先查、查不到再查歷史」這種兩段式寫成兩份，就是本 repo
 * 反覆記載的缺陷成因。
 *
 * <h2>⚠️ 一次查詢，不是每個任務一次</h2>
 *
 * <p>{@code GET /api/history/tasks} 不帶參數時回傳呼叫者所有已完成任務
 * （{@code Dashboard.vue} 會呼叫），逐案查變數會是 N+1。
 * 因此本類別一律<b>整批一次</b>查完再回傳 {@code pid → userId} 的對照表。
 * MSSQL 的 {@code IN (?, ?, …)} 有 2100 個參數上限，所以沿用
 * {@link ProcessInvolvementService#ID_BATCH} 的分批大小與理由。
 */
@Service
public class OnBehalfOfLookup {

    private final HistoryService historyService;

    public OnBehalfOfLookup(HistoryService historyService) {
        this.historyService = historyService;
    }

    /**
     * 這些案件各自是代誰發起的。
     *
     * @return {@code processInstanceId → 員工 id}。
     *         沒有代發的實例<b>不會出現在結果裡</b>（不是 null 值）——
     *         呼叫端因此可以直接用「查得到就是代發」判斷，不必處理 null。
     */
    public Map<String, String> byProcessInstances(Collection<String> processInstanceIds) {
        Map<String, String> out = new HashMap<>();
        if (processInstanceIds == null || processInstanceIds.isEmpty()) return out;
        for (java.util.List<String> batch : ProcessInvolvementService.partition(processInstanceIds)) {
            for (var v : historyService.createHistoricVariableInstanceQuery()
                    .processInstanceIds(batch)
                    .variableName(InitialAssigneeResolver.ON_BEHALF_OF_VAR)
                    .list()) {
                String pid = v.getProcessInstanceId();
                String value = v.getValue() == null ? null : v.getValue().toString();
                if (pid == null || value == null || value.isBlank()) continue;
                // 見類別註解「系統身分必須被濾掉」
                if (ExternalActorIdentity.isSystemActor(value)) continue;
                // 同一個變數名在子流程／multi-instance 可能有多筆（不同 scope）。
                // 取第一筆：值對每個 scope 都相同，而 map 只能放一個。
                out.putIfAbsent(pid, value);
            }
        }
        return out;
    }
}
