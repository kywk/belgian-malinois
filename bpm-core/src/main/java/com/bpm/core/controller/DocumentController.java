package com.bpm.core.controller;

import org.springframework.transaction.annotation.Transactional;
import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.audit.model.OperationType;
import com.bpm.core.dto.AuditEvent;
import com.bpm.core.security.CallerId;
import com.bpm.core.security.ProcessAccessGuard;
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
    private final ProcessAccessGuard accessGuard;
    private final TransactionTemplate numberingTx;

    public DocumentController(DocumentRequestRepository docRepo, RuntimeService runtimeService,
                              OrgService orgService, AuditEventPublisher auditPublisher,
                              ProcessAccessGuard accessGuard,
                              @Qualifier("primaryTransactionManager")
                              PlatformTransactionManager primaryTransactionManager) {
        this.docRepo = docRepo;
        this.runtimeService = runtimeService;
        this.orgService = orgService;
        this.auditPublisher = auditPublisher;
        // #80：授權判斷不寫在 controller 裡。放在這裡就會有第二份
        // isParticipant（第一份在 ProcessAccessGuard），而兩份規則各自演化
        // 出來的差異比沒有檢查更難察覺 —— 見該類別註解「規則只能有一份」。
        this.accessGuard = accessGuard;
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

    /**
     * 公文詳情（#80：補上物件層授權）。
     *
     * <h2>改動前是什麼</h2>
     *
     * <p>{@code docRepo.findById(id).orElseThrow(404)} —— 沒有 {@code @CallerId}、
     * 沒有任何檢查。任何登入者拿任一 documentId 就拿得到整筆公文，
     * <b>包含 {@code processInstanceId}</b>。
     *
     * <p>它與 {@link #list} 是同一個洞的兩面，而<b>只修 {@code list} 等於沒修</b>：
     * 列表被收斂成「我的公文」之後，documentId 仍然可從稽核紀錄
     * （{@code PROCESS_START} 的 detail 帶 {@code documentNumber}／{@code title}）
     * 與任何已知或猜測到的 id 取得，然後逐筆列出來。那正是本 repo 反覆記錄過的
     * 「在沒有門牌的地址上加門鎖」（見 {@code HistoryController} 的類別註解）。
     *
     * <h2>守衛：{@code requireReadAccess}，與 attachments／variables 同一條</h2>
     *
     * <p>公文是「一張單的內容」，而且它的 {@code processInstanceId}
     * 就是通往那張單的鑰匙 —— 與 {@code AttachmentController.download}
     * 由 {@code att.getProcessInstanceId()} 取守衛對象是<b>完全相同</b>的形狀。
     * 因此規則只有一份：關係人，<b>或</b>持有 {@code audit:log:read} 的稽核人員，
     * 且旁路每次留痕；其餘 404（非 403 —— 403 會確認這筆公文存在）。
     *
     * <h2>⚠️ 沒有關聯案件的公文（{@code processInstanceId} 為 null）</h2>
     *
     * <p>這種資料列真的存在：{@link #create} 是<b>先存公文、再啟動流程</b>
     * （見該方法註解第 2 點，為的是不留下看不見的孤兒流程），
     * 因此流程啟動失敗時會留下一筆 {@code processInstanceId} 為空的公文。
     *
     * <p>它沒有案件可以「參與」，所以 {@code requireReadAccess} 無從套用
     * （傳 null 進去會讓守衛去查一個不存在的實例，然後對每個人都回 404 ——
     * 連建立人自己也看不到，那筆資料就永遠沒有人能清理）。
     * 因此這個分支的規則是：<b>只有建立人本人讀得到</b>。
     * 這不是第四組政策，而是「這個物件不屬於任何案件」時唯一還成立的關係。
     *
     * <p>拒絕時走 {@link ProcessAccessGuard#denyNonParticipant}（404 ＋ 留痕）
     * 而不是自己寫 {@code throw}：拒絕的<b>形狀</b>必須只有一種，
     * 否則稽核上會出現兩種「被拒絕」而沒有人知道它們其實是同一件事。
     */
    @GetMapping("/{id}")
    public DocumentRequest getById(@PathVariable String id, @CallerId String callerId) {
        DocumentRequest doc = docRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        requireReadable(doc, callerId);
        return doc;
    }

    /** 公文的讀取授權。見 {@link #getById} 的說明。 */
    private void requireReadable(DocumentRequest doc, String callerId) {
        String pid = doc.getProcessInstanceId();
        if (pid == null || pid.isBlank()) {
            // 沒有案件的公文：唯一成立的關係是建立人本人。見 getById 的說明。
            if (callerId != null && callerId.equals(doc.getCreatedBy())) return;
            accessGuard.denyNonParticipant(pid, callerId);
            return; // 不會執行到：denyNonParticipant 一定拋例外
        }
        if (accessGuard.requireReadAccess(pid, callerId)) {
            // 參與者讀自己案件的公文不留痕（那是日常操作）；稽核旁路必須留痕。
            auditPublisher.publish(new AuditEvent(OperationType.DATA_ACCESS.name(), callerId,
                    pid, null,
                    Map.of("action", "get_document", "documentId", doc.getId(),
                            "auditBypass", true)));
        }
    }

    /**
     * 公文列表（#80：補上物件層授權）。
     *
     * <h2>改動前是什麼</h2>
     *
     * <p>{@code if (createdBy != null) …; return docRepo.findAll();} ——
     * <b>不帶參數就是 {@code findAll()}</b>，回傳<b>全公司</b>所有公文，
     * 每筆都含 {@code processInstanceId}、{@code documentNumber}、{@code title}、
     * {@code urgencyLevel}。
     *
     * <p>{@code ?createdBy=} 也不是身分檢查 —— 帶任何人的 id 都會回傳那個人的全部公文。
     * 所以這裡有<b>兩個</b>缺口，而它們是同一個政策問題的兩種表現。
     *
     * <h2>政策決定：列出「自己參與的」還是「全部」→ 選<b>自己建立的</b></h2>
     *
     * <p>理由，逐條：
     *
     * <ol>
     *   <li><b>它就是 pid 的第二個枚舉來源，而枚舉來源必須關掉。</b>
     *       這正是本工項存在的理由。#71 關掉的是第一個來源
     *       （{@code GET /api/process-instances}），本端點是第二個。
     *       只要它還在，#71 的成果就只關掉一半。</li>
     *   <li><b>{@code createdBy} 是「身分欄位」，不是篩選條件。</b>
     *       本 repo 對這類參數的既定政策是 {@code requireSelf}（#71 定義）：
     *       帶了與自己不符的值 → 明確 400；省略 → 呼叫者自己。
     *       另一個選項是「對每筆公文各跑一次 {@code requireReadAccess}」，
     *       但那是 N+1（每筆 1 次實例查詢 ＋ 1 次變數 ＋ 2 次任務計數），
     *       而且會讓回應<b>部分可見</b> —— 呼叫端分不出「我沒有這些公文」
     *       與「這些公文被過濾掉了」，那是比全開更難察覺的失敗型態。</li>
     *   <li><b>「審核人看得到自己審的那張單的公文」這個需求已經被滿足了</b>，
     *       走的是另一條路：{@code GET /api/process-instances/involved}
     *       列出我參與的案件（同一條 {@code isParticipant}），
     *       再用 {@link #getById} 逐筆取公文（守衛相同）。
     *       換句話說<b>不需要為它發明第四組政策</b>。</li>
     *   <li><b>沒有任何前端呼叫這個端點</b>
     *       （{@code grep -rn "api/documents" bpm-frontend/src} 零命中），
     *       所以收斂範圍不會讓任何畫面壞掉 —— 這是「先關再議」的關鍵前提。
     *       若日後有人要用，它是<b>新增</b>一個端點（例如
     *       {@code /api/documents/involved}），而不是把這個放寬回去。</li>
     * </ol>
     *
     * <h2>不開稽核旁路（與 {@code GET /api/process-instances} 同一個理由）</h2>
     *
     * <p>本端點的回應<b>永遠是「呼叫者自己建立的公文」</b>，不是別人的資料。
     * 稽核人員要查別人的案件請走稽核 API（{@code PROCESS_START} 事件帶
     * {@code documentNumber} 與 {@code title}）或
     * {@link #getById}（那裡有旁路且每次留痕）。在「我的清單」上開旁路等於
     * 給稽核職能一個沒有對應需求的讀取權，而稽核人員的職責是<b>查閱</b>不是<b>代覽</b>。
     *
     * <h2>⚠️ 空白 {@code createdBy} 的行為變了（空白視同省略）</h2>
     *
     * <p>改動前 {@code ?createdBy=}（空字串）會走進 repository 查一個空字串，
     * 回 {@code []} —— 那是一個看起來像「我沒有公文」的<b>謊話</b>
     * （實際上只是查錯了）。改動後它等同省略，回傳呼叫者自己的公文。
     * 這與 {@code ProcessAccessGuard.requireSelf} 對空白的處理一致
     * （空白不可能指向別人，拒絕它只製造無意義的破壞）。
     *
     * <h2>刻意不加 {@code @Transactional}</h2>
     *
     * <p>唯讀查詢 ＋ 最多一次稽核寫入（只有稽核旁路才有，而本端點不開旁路），
     * 與 {@link ProcessAccessGuard} 的其他讀端呼叫端同一型。
     */
    @GetMapping
    public List<DocumentRequest> list(@RequestParam(required = false) String createdBy,
                                     @CallerId String callerId) {
        String self = accessGuard.requireSelf(createdBy, callerId, "createdBy");
        return docRepo.findByCreatedByOrderByCreatedAtDesc(self);
    }
}
