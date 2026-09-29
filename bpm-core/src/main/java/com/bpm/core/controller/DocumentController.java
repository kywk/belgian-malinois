package com.bpm.core.controller;

import org.springframework.transaction.annotation.Transactional;
import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.dto.AuditEvent;
import com.bpm.core.security.CallerId;
import com.bpm.core.model.DocumentRequest;
import com.bpm.core.repository.DocumentRequestRepository;
import com.bpm.core.service.OrgService;
import org.flowable.engine.RuntimeService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
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
    private final TransactionTemplate numberingTx;

    public DocumentController(DocumentRequestRepository docRepo, RuntimeService runtimeService,
                              OrgService orgService, AuditEventPublisher auditPublisher,
                              @Qualifier("primaryTransactionManager")
                              PlatformTransactionManager primaryTransactionManager) {
        this.docRepo = docRepo;
        this.runtimeService = runtimeService;
        this.orgService = orgService;
        this.auditPublisher = auditPublisher;
        // 見 saveWithUniqueNumber：每次取號嘗試必須是獨立交易。
        this.numberingTx = new TransactionTemplate(primaryTransactionManager);
        this.numberingTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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
     *
     * <p><b>3. createdBy 由登入身分決定（#66）。</b>改動前它是
     * {@code @RequestBody} 上的一個純資料欄位，卻被當成三處的權威來源：
     * 公文編號的部門代碼、流程變數 {@code initiator}（下游用它解析主管
     * 與補件任務的 assignee）、以及稽核的 operatorId。於是任何登入者都能
     * 以別人的名義建立公文 —— 與 {@code POST /api/process-instances}
     * 同一型缺陷，兩條路都能冒用。
     */
    @PostMapping
    @Transactional("primaryTransactionManager")
    public DocumentRequest create(@RequestBody DocumentRequest req,
                                  @CallerId String callerId) {
        // 與 ProcessController 同一理由：未認證就拒絕，不 fallback 到標頭。
        // 見 CallerIdArgumentResolver 類別註解 —— 退回讀標頭會讓整套認證
        // 變成裝飾。而這條路會寫入稽核（fail-closed 前提），所以更不能
        // 讓稽核記到一個編造的身分。
        if (callerId == null || callerId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "無法確認建立人身分，請先登入");
        }

        // 必定新增。夾帶 id 會讓 save() 走 merge → 改寫他人公文的
        // documentNumber / title（security-audit P0-4）。
        req.setId(null);
        req.setProcessInstanceId(null);

        // createdBy = 登入者。
        //
        // ⚠️ 明確 400 拒絕冒用，而不是「先接受再覆寫」：覆寫會讓呼叫端
        // 以為自己指定的值生效了（與 ProcessController 的 initiator、
        // ExternalApiController 的 initiator 同一取捨）。
        //
        // 為什麼是「不同才拒絕」而不是「body 有帶就拒絕」：initiator 是
        // 一個專用 DTO 欄位，server 可以用 containsKey 判斷「有沒有送」；
        // createdBy 是 JPA entity 上的一個欄位，同時也是回應的一部分，
        // Jackson 反序列化後無法分辨「沒送」與「送了 null」。而送出與自己
        // 相同的身分並不構成冒用，拒絕它只會製造無意義的破壞。
        // server 一律在下面覆寫成 callerId，因此 body 帶的值無論如何都蓋不掉。
        String claimed = req.getCreatedBy();
        if (claimed != null && !claimed.isBlank() && !callerId.equals(claimed)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "createdBy 由登入身分（" + callerId + "）決定，不可指定他人。"
                            + "請移除 body 的 createdBy 欄位或改為登入身分。");
        }
        req.setCreatedBy(callerId);

        // Generate document number: DOC-{year}-{deptCode}-{seq}
        // ⚠️ 必須在 setCreatedBy 之後：部門代碼是從 createdBy 查出來的，
        // 用 body 的值會讓公文編號掛在別人的部門下。
        String deptCode = orgService.getDeptId(req.getCreatedBy());
        if (deptCode == null) deptCode = "GEN";
        String prefix = "DOC-" + Year.now().getValue() + "-" + deptCode.toUpperCase();

        DocumentRequest saved = saveWithUniqueNumber(req, prefix);

        // 流程啟動在存檔之後：失敗時不會留下孤兒流程。
        Map<String, Object> vars = new HashMap<>();
        vars.put("initiator", callerId);
        vars.put("documentTitle", saved.getTitle());
        vars.put("urgencyLevel", saved.getUrgencyLevel());
        var pi = runtimeService.startProcessInstanceByKey(
                saved.getCategory() != null ? saved.getCategory() : "leave-approval",
                saved.getDocumentNumber(), vars);

        saved.setProcessInstanceId(pi.getProcessInstanceId());
        saved = docRepo.save(saved);

        // operatorId 用 callerId：稽核要記「誰做的」，不是「單子掛在誰名下」。
        auditPublisher.publish(new AuditEvent("PROCESS_START", callerId,
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
                // ⚠️ 每次嘗試必須是獨立交易（REQUIRES_NEW）。
                //
                // create() 有 @Transactional（稽核 fail-closed，P1-14）。若直接在外層交易
                // 裡 saveAndFlush，撞號的約束違規會把外層交易標成 rollback-only、
                // Hibernate session 也不再可用 —— 重試永遠不會成功，commit 時還會變成
                // UnexpectedRollbackException。
                //
                // 代價：公文列在流程啟動前就已 commit。流程啟動或稽核失敗時留下一筆
                // processInstanceId 為空的公文 —— 這正是上面 create() 註解所說
                // 「看得見、可清理」的那種失敗狀態，與改動前一致。
                return numberingTx.execute(status -> docRepo.saveAndFlush(req));
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
