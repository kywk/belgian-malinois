package com.bpm.core.controller;

import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.dto.AuditEvent;
import com.bpm.core.model.DocumentRequest;
import com.bpm.core.repository.DocumentRequestRepository;
import com.bpm.core.service.OrgService;
import org.flowable.engine.RuntimeService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Year;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentRequestRepository docRepo;
    private final RuntimeService runtimeService;
    private final OrgService orgService;
    private final AuditEventPublisher auditPublisher;

    public DocumentController(DocumentRequestRepository docRepo, RuntimeService runtimeService,
                              OrgService orgService, AuditEventPublisher auditPublisher) {
        this.docRepo = docRepo;
        this.runtimeService = runtimeService;
        this.orgService = orgService;
        this.auditPublisher = auditPublisher;
    }

    /** 撞號時的重試次數。併發兩三個請求即可用完一次，5 次已足夠寬裕。 */
    private static final int MAX_NUMBER_ATTEMPTS = 5;

    /**
     * 建立公文並啟動流程。
     *
     * <p>改動前有兩個問題（security-audit P1-11）：
     *
     * <p><b>1. 用 COUNT 當序號，且會撞號。</b>
     * {@code int seq = docRepo.countByPrefix(prefix) + 1} ——
     * 兩個請求同時 count 會得到相同值。{@code documentNumber} 有 unique 約束
     * 會擋掉第二筆，但 COUNT 的語意本身也是錯的：刪掉一筆舊公文之後，
     * 下一個號會與既有編號重複。已改為 MAX(序號) + 1 並在撞號時重試。
     *
     * <p><b>2. 先啟流程、後存檔 → 孤兒流程。</b>
     * 原本的順序是 {@code startProcessInstanceByKey(...)} 然後
     * {@code docRepo.save(req)}，而方法沒有 {@code @Transactional}。
     * 存檔失敗（例如撞號）時<b>流程實例已經啟動且不會回滾</b> ——
     * 留下沒有對應公文列、businessKey 已被佔用的孤兒流程，使用者看到 500。
     *
     * <p>現在改為：先存公文（讓 unique 約束擋下撞號並重試）→ 再啟流程 →
     * 回填 processInstanceId。若啟流程失敗，留下的是一筆<b>看得見</b>的
     * 公文（可查詢、可重試、可清理），而不是一個看不見的孤兒流程。
     */
    @PostMapping
    public DocumentRequest create(@RequestBody DocumentRequest req) {
        // 必定新增。夾帶 id 會讓 save() 走 merge → 改寫他人公文的
        // documentNumber / title（security-audit P0-4）。
        req.setId(null);
        req.setProcessInstanceId(null);

        // Generate document number: DOC-{year}-{deptCode}-{seq}
        String deptCode = orgService.getDeptId(req.getCreatedBy());
        if (deptCode == null) deptCode = "GEN";
        String prefix = "DOC-" + Year.now().getValue() + "-" + deptCode.toUpperCase();

        DocumentRequest saved = saveWithUniqueNumber(req, prefix);

        // 流程啟動在存檔之後：失敗時不會留下孤兒流程。
        Map<String, Object> vars = new HashMap<>();
        vars.put("initiator", saved.getCreatedBy());
        vars.put("documentTitle", saved.getTitle());
        vars.put("urgencyLevel", saved.getUrgencyLevel());
        var pi = runtimeService.startProcessInstanceByKey(
                saved.getCategory() != null ? saved.getCategory() : "leave-approval",
                saved.getDocumentNumber(), vars);

        saved.setProcessInstanceId(pi.getProcessInstanceId());
        saved = docRepo.save(saved);

        auditPublisher.publish(new AuditEvent("PROCESS_START", saved.getCreatedBy(),
                pi.getProcessInstanceId(), null,
                Map.of("documentNumber", saved.getDocumentNumber(), "title", saved.getTitle())));
        return saved;
    }

    /**
     * 取下一個序號並存檔，撞號時重試。
     *
     * <p>序號用 MAX + 1 而非 COUNT + 1。即使如此，「讀 MAX」與「寫入」之間
     * 仍有競爭窗口 —— 真正消除競爭需要 DB 端序號或 {@code SELECT ... FOR UPDATE}。
     * 這裡的做法是讓 unique 約束當最終仲裁，撞號就重算重試：
     * 實作簡單、不需要新 schema，而且在併發量低的公文建立場景上足夠。
     */
    private DocumentRequest saveWithUniqueNumber(DocumentRequest req, String prefix) {
        DataIntegrityViolationException last = null;
        for (int attempt = 0; attempt < MAX_NUMBER_ATTEMPTS; attempt++) {
            int seq = docRepo.maxSequenceForPrefix(prefix) + 1 + attempt;
            req.setDocumentNumber(prefix + "-" + String.format("%03d", seq));
            try {
                return docRepo.saveAndFlush(req);
            } catch (DataIntegrityViolationException e) {
                // 幾乎必然是 documentNumber 的 unique 約束（另一個請求先寫入了同號）。
                // 重算下一號再試；flush 是必要的 —— 不 flush 的話違規會延後到
                // 交易提交時才浮現，那時已經無法在此重試。
                last = e;
            }
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT,
                "公文編號連續 " + MAX_NUMBER_ATTEMPTS + " 次撞號，請重試", last);
    }

    @GetMapping("/{id}")
    public DocumentRequest getById(@PathVariable String id) {
        return docRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @GetMapping
    public List<DocumentRequest> list(@RequestParam(required = false) String createdBy) {
        if (createdBy != null) return docRepo.findByCreatedByOrderByCreatedAtDesc(createdBy);
        return docRepo.findAll();
    }
}
