package com.bpm.core.form.controller;

import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.audit.model.OperationType;
import com.bpm.core.dto.AuditEvent;
import com.bpm.core.form.model.FormData;
import com.bpm.core.form.service.FormService;
import com.bpm.core.security.CallerId;
import com.bpm.core.security.ProcessAccessGuard;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 表單資料的讀取端。
 *
 * <h2>uni-form 重構（2026-10-12）：寫入端已移除</h2>
 *
 * <p>改動前這裡有三個端點（POST／GET／PUT）。uni-form 重構把表單提交
 * 收斂到流程節點上，因此：
 *
 * <ul>
 *   <li><b>{@code POST /api/form-data} 移除</b>：表單資料一律隨流程動作
 *       落地（{@code POST /api/process-instances} 的 {@code formData}、
 *       {@code PUT /api/tasks/{id}} 的 {@code formData}）—— 這兩條路徑都會
 *       先跑 SDK 的 {@code validateV1}、再寫入、再掛跨 DB 補償。
 *       一個獨立的「直接塞表單資料」入口只會是繞過流程語意的旁路。</li>
 *   <li><b>{@code PUT /api/form-data/{id}} 移除</b>：補件改走
 *       {@code PUT /api/tasks/{id}} 的 {@code formData}（版本化語意不變：
 *       新列生效、舊列保留、{@code FORM_UPDATE} 稽核串起新舊列）。</li>
 *   <li><b>{@code GET /api/form-data/{processInstanceId}} 保留</b>：
 *       稽核／API 讀取仍需要列出某案件的所有版本列（含歷史）。</li>
 * </ul>
 *
 * <h2>讀端的授權政策（未變）</h2>
 *
 * <p>{@link ProcessAccessGuard#requireReadAccess}：參與者，或持有
 * {@code audit:log:read}（<b>不認 {@code ROLE_ADMIN}</b>）的稽核人員；
 * 其餘 404（不是 403 —— 403 會確認「這個案件存在」，對可枚舉的 id
 * 等於把枚舉管道留著）。旁路留痕、參與者不留痕，理由與
 * {@code ProcessController.getVariables} 相同。
 *
 * <p>⚠️ 與 getVariables 刻意差異化的一處：<b>已結束的案件在這裡回 200 + 資料</b>，
 * 那裡是 200 + {}。表單資料在 {@code bpm_form_db} 是持久列，不像 runtime
 * 流程變數會被 Flowable 在結案時清除 —— 審結的單仍然應該看得到當時填了什麼。
 *
 * <p>刻意<b>不</b>加 {@code @Transactional}：這是唯讀查詢 ＋ 一次稽核寫入，
 * 與 {@code ProcessController.getVariables}／{@code AttachmentController.list}
 * 同一型（稽核失敗 → 503，見 {@code AuditFailClosedTest}）。
 */
@RestController
@RequestMapping("/api/form-data")
public class FormDataController {

    private final FormService formService;
    private final AuditEventPublisher auditPublisher;
    private final ProcessAccessGuard accessGuard;

    public FormDataController(FormService formService, AuditEventPublisher auditPublisher,
                              // #71 抽出的共用授權判斷。附件、variables、表單資料
                              // 三者必須共用同一份 isParticipant：兩處各自維護時，
                              // 只要有人改了其中一處，就會出現「這個看得到、那個看不到」
                              // 的組合，而那種差異比沒有檢查更難察覺。
                              ProcessAccessGuard accessGuard) {
        this.formService = formService;
        this.auditPublisher = auditPublisher;
        this.accessGuard = accessGuard;
    }

    // ── GET /api/form-data/{processInstanceId} ─────────────────────

    /**
     * 某案件的表單資料（全部版本列，依 {@code submittedAt} 遞減）。
     *
     * <p>{@code dataJson} 現在是 uni-form canonical JSON（例如 dateRange 是
     * {@code {start,end}}），不是舊版的扁平格式 —— 讀取端原樣回傳。
     */
    @GetMapping("/{processInstanceId}")
    public List<FormData> getByProcess(@PathVariable String processInstanceId,
                                       @CallerId String callerId) {
        if (accessGuard.requireReadAccess(processInstanceId, callerId)) {
            // 參與者讀自己的表單不留痕（那是日常操作）；稽核旁路必須留痕。
            auditPublisher.publish(new AuditEvent(OperationType.DATA_ACCESS.name(), callerId,
                    processInstanceId, null,
                    Map.of("action", "get_form_data", "auditBypass", true)));
        }
        return formService.getDataByProcess(processInstanceId);
    }
}
