package com.bpm.core.form.service;

import com.bpm.core.form.model.FormData;
import com.bpm.core.form.model.FormDefinition;
import com.bpm.core.form.repository.FormDataRepository;
import com.bpm.core.form.repository.FormDefinitionRepository;
import org.flowable.engine.RuntimeService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class FormService {

    private final FormDefinitionRepository defRepo;
    private final FormDataRepository dataRepo;
    private final RuntimeService runtimeService;

    private final TransactionTemplate versionTx;

    /**
     * 一次問 Flowable runtime 的案件數上限。
     *
     * <p>SQL Server 的單一查詢參數上限是 2100，500 遠低於它。這不是調校值，
     * 只是讓「歷史資料很多」不會變成一次爆掉的查詢 —— 搭配短路，最壞情況
     * 也只有使用中的表單會付這個成本（見 {@link #requireNotUsedByRunningProcess}）。
     */
    private static final int RUNTIME_QUERY_BATCH = 500;

    public FormService(FormDefinitionRepository defRepo, FormDataRepository dataRepo,
                       RuntimeService runtimeService,
                       @Qualifier("formTransactionManager") PlatformTransactionManager formTransactionManager) {
        this.defRepo = defRepo;
        this.dataRepo = dataRepo;
        this.runtimeService = runtimeService;
        // 見 saveAllocatingVersion：每次取號嘗試必須是獨立交易。
        this.versionTx = new TransactionTemplate(formTransactionManager);
        this.versionTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 建立一個<b>全新</b>的 formKey（第 1 版 draft）。
     *
     * <p>對既有 formKey 呼叫會被明確擋下並指向改版端點。改動前不擋，
     * 結果是撞 {@code (formKey, version)} 唯一約束 → 500，
     * 而使用者得到的訊息完全看不出該怎麼做（security-audit P1-12）。
     */
    @Transactional("formTransactionManager")
    public FormDefinition create(FormDefinition def) {
        // 第二層防線。entity 的 id 已標 READ_ONLY（Jackson 不會反序列化它），
        // 但 create 的語意就是「必定新增」，因此在此顯式歸零 ——
        // 這同時涵蓋非 HTTP 的呼叫路徑，而且不依賴序列化層的設定。
        def.setId(null);
        if (def.getFormKey() == null || def.getFormKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "formKey 為必填");
        }
        if (defRepo.existsByFormKey(def.getFormKey())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "formKey '" + def.getFormKey() + "' 已存在。"
                            + "要修改已發布的表單請改用 POST /api/forms/"
                            + def.getFormKey() + "/revisions 建立新版 draft。");
        }
        def.setVersion(1);
        def.setStatus("draft");
        return defRepo.save(def);
    }

    /**
     * 為既有 formKey 建立下一版 draft —— <b>已發布表單的改版路徑</b>。
     *
     * <p>改動前完全沒有這個路徑（security-audit P1-12）：{@code create()} 無條件
     * {@code setVersion(1)}、published 列不能 update 也不能 delete，
     * 因此 {@code data.sql} 種下的四張 published 表單<b>透過 API 完全不可修改</b>。
     * 想改只能呼叫 {@code publish()} 產生一份內容完全相同的 v2，
     * 再也沒有辦法把新的 schemaJson 放進去 ——
     * 這直接堵死「讓業務人員自行設計、維運」的產品目標。
     *
     * <p>新 draft 以「目前最新版」的內容為起點（複製 name／schemaJson），
     * 讓改版是增量編輯而不是從零重寫。
     *
     * <p>同一個 formKey <b>同時只允許一份 draft</b>：允許多份會出現兩個編輯者
     * 各自 publish、互相覆蓋版本號的情況，而且 UI 無法判斷哪一份才是「當前草稿」。
     */
    @Transactional("formTransactionManager")
    public FormDefinition createNextDraft(String formKey, String createdBy) {
        if (!defRepo.existsByFormKey(formKey)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "formKey 不存在: " + formKey);
        }
        defRepo.findByFormKeyAndStatus(formKey, "draft").ifPresent(existing -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "formKey '" + formKey + "' 已有一份未發布的 draft（版本 "
                            + existing.getVersion() + "）。請先發布或刪除它。");
        });

        // 以最新版為起點；沒有 published 版本時退回版本號最大的那一份。
        FormDefinition base = defRepo.findLatestPublished(formKey)
                .orElseGet(() -> defRepo.findByFormKeyAndVersion(
                                formKey, defRepo.findMaxVersion(formKey))
                        .orElseThrow(() -> new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "找不到可作為基礎的版本")));

        FormDefinition draft = new FormDefinition();
        draft.setFormKey(formKey);
        draft.setName(base.getName());
        draft.setSchemaJson(base.getSchemaJson());
        draft.setCreatedBy(createdBy != null ? createdBy : base.getCreatedBy());
        draft.setVersion(defRepo.findMaxVersion(formKey) + 1);
        draft.setStatus("draft");
        return saveAllocatingVersion(draft, formKey);
    }

    /**
     * 存檔並處理版本號競爭。
     *
     * <p>{@code findMaxVersion()} 的讀取與寫入之間有競爭窗口，而
     * {@code (formKey, version)} 有唯一約束。兩個併發的改版請求會有一個撞約束
     * —— 此時重算版本號重試，而不是把 500 丟給使用者。
     */
    private FormDefinition saveAllocatingVersion(FormDefinition draft, String formKey) {
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                // ⚠️ 每次嘗試必須是獨立交易（REQUIRES_NEW），理由與
                // DocumentController.saveWithUniqueNumber 相同：在外層交易裡撞約束，
                // 交易會被標成 rollback-only、Hibernate session 也不再可用，重試永遠不會成功。
                //
                // 改動前「剛好」能重試，是因為本類別的 @Transactional 未限定管理器、
                // 開在 bpm_core_db 上，form repository 的每次呼叫其實各自 commit。
                // 限定為 formTransactionManager 之後（P1-14）才讓這個問題浮現。
                //
                // 代價：draft 在外層交易之前就 commit。外層（例如稽核寫入）失敗時會留下
                // 一份 draft，下一次改版會被「已有未發布 draft」擋下 —— 看得見、可刪除。
                return versionTx.execute(status -> defRepo.saveAndFlush(draft));
            } catch (DataIntegrityViolationException e) {
                draft.setVersion(defRepo.findMaxVersion(formKey) + 1 + attempt);
            }
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT,
                "版本號連續撞號，請重試");
    }

    @Transactional(value = "formTransactionManager", readOnly = true)
    public Page<FormDefinition> list(Pageable pageable) {
        return defRepo.findByStatusNotOrderByUpdatedAtDesc("archived", pageable);
    }

    @Transactional(value = "formTransactionManager", readOnly = true)
    public FormDefinition getSchema(String formKey, Integer version) {
        if (version != null) {
            return defRepo.findByFormKeyAndVersion(formKey, version)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        }
        return defRepo.findLatestPublished(formKey)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @Transactional("formTransactionManager")
    public FormDefinition update(String id, FormDefinition updated) {
        FormDefinition existing = defRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!"draft".equals(existing.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only draft forms can be updated");
        }
        existing.setName(updated.getName());
        existing.setSchemaJson(updated.getSchemaJson());
        return defRepo.save(existing);
    }

    /**
     * 發布 draft。
     *
     * <p>改動前有三個問題（security-audit P1-12），全部源自「發布 = 複製一份新版」
     * 這個錯誤的實作方式：
     *
     * <ol>
     *   <li><b>一次 publish 產生兩筆 published</b>：clone 出一份新版標為
     *       published，又把來源 draft 也標成 published → UI 出現重複表單。</li>
     *   <li><b>無狀態守衛</b>：對 archived 呼叫會把它<b>復活</b>；
     *       對 published 重複呼叫則版本號無限膨脹。</li>
     *   <li><b>版本號讀寫非原子</b>：findMaxVersion 之後才寫入。</li>
     * </ol>
     *
     * <p>現在的語意是「把這份 draft 轉為 published」—— 版本號在建立 draft 時
     * 就已經配好（見 {@link #createNextDraft}），發布只是狀態轉換，
     * 因此上述三個問題同時消失。
     */
    @Transactional("formTransactionManager")
    public FormDefinition publish(String id) {
        FormDefinition existing = defRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!"draft".equals(existing.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "只有 draft 可以發布，目前狀態為 " + existing.getStatus()
                            + "。要修改已發布的表單請用 POST /api/forms/"
                            + existing.getFormKey() + "/revisions。");
        }
        existing.setStatus("published");
        return defRepo.save(existing);
    }

    /**
     * 封存 published 表單（#59：執行中的案件仍在使用時不可封存）。
     *
     * <p>封存的產品語意是「這份版本不再提供給新的流程使用」
     * （{@code findLatestPublished} 只找 status=published）。已結束案件的
     * 歷史資料不受影響，所以封存本身是合法且必要的生命週期終點 ——
     * 但一份<b>正在被跑的案件使用</b>的表單不該封存：版本鎖定只覆蓋
     * 啟流程當下解析成功的 formKey，鎖定失敗的舊案件會退回查
     * 「最新 published」，一封存就再也找不到它。
     *
     * <p>判定與跨交易的取捨見 {@link #requireNotUsedByRunningProcess}。
     */
    @Transactional("formTransactionManager")
    public FormDefinition archive(String id) {
        FormDefinition existing = defRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!"published".equals(existing.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Only published forms can be archived");
        }
        requireNotUsedByRunningProcess(id);
        existing.setStatus("archived");
        return defRepo.save(existing);
    }

    /**
     * 「這份表單正被執行中的流程使用」時擋下（409）。
     *
     * <h2>為什麼用 FormData 而不是 {@code _formVersions}</h2>
     *
     * <p>{@code FormVersionLocker} 在流程啟動時把 BPMN 裡所有 formKey 的
     * 已發布版本寫進流程變數 {@code _formVersions}（formKey→version）。
     * 它是「這個流程定義會用到哪些表單」的<b>快照</b>，不是「這份版本
     * 已經被填了」的證據：
     *
     * <ul>
     *   <li>BPMN 裡有、但案件還沒走到的表單也會被鎖定 —— 用 {@code _formVersions}
     *       判定會把「只是被流程定義引用」誤判成「使用中」，連從未被填過的
     *       表單都不能封存。</li>
     *   <li>它是一個 Map 型流程變數，要反查「哪些執行中案件用到版本 X」
     *       只能掃描所有執行中實例再逐一讀變數 —— 沒有可用的索引，
     *       而且版本鎖定失敗（formKey 當下沒有 published）時這個 key
     *       根本不存在。</li>
     * </ul>
     *
     * <p>{@code bpm_form_data} 的 {@code formDefinitionId} 才精確指向
     * 「這一版」，{@code processInstanceId} 則記著它屬於哪個案件。
     * 已知的限制：<b>案件已啟動但尚未送出表單資料時不算「使用中」</b>。
     * 這個缺口是可接受的 —— 版本鎖定會讓該案件繼續用被封存的版本
     * （{@code getSchema(formKey, version)} 不篩 status），封存只影響
     * 「新流程不再取得這份版本」，不會讓進行中的案件壞掉。
     *
     * <h2>已結束的案件不算使用中</h2>
     *
     * <p>歷史資料必須能封存，不然表單的生命週期永遠不會結束。這正是
     * 必須問 runtime 而不是「有沒有 FormData」的原因：只要有任何一筆
     * FormData 就擋，等於這張表單用過一次就再也封存不了。
     *
     * <h2>⚠️ 跨交易管理器</h2>
     *
     * <p>本方法在 {@code formTransactionManager} 的交易內執行，而
     * {@code runtimeService} 的查詢走 {@code primaryTransactionManager}。
     * 唯讀查詢可接受：這裡不對 Flowable 寫入，也不 catch Flowable 例外
     * （Flowable 命令在外層交易中拋例外會把交易標成 rollback-only，
     * 見 {@code ProcessAccessGuard.initiatorOf} 的說明）。同型的先例是
     * {@code FormDataController.submit}：它也在 formTransactionManager
     * 交易內呼叫 {@code ProcessAccessGuard.requireParticipant}（同樣打 runtime）。
     *
     * <h2>量</h2>
     *
     * <p>一個 formDefinitionId 的歷史資料可能很多（已結束案件持續累積），
     * 因此用 {@code processInstanceIds} 分批問、任一批有執行中實例就短路；
     * 找到時順便取回一筆案件編號放進訊息，讓管理者知道是哪個案件在跑。
     */
    private void requireNotUsedByRunningProcess(String formDefinitionId) {
        List<String> processInstanceIds =
                dataRepo.findDistinctProcessInstanceIdsByFormDefinitionId(formDefinitionId);
        for (int from = 0; from < processInstanceIds.size(); from += RUNTIME_QUERY_BATCH) {
            Set<String> batch = new HashSet<>(processInstanceIds.subList(
                    from, Math.min(from + RUNTIME_QUERY_BATCH, processInstanceIds.size())));
            var running = runtimeService.createProcessInstanceQuery()
                    .processInstanceIds(batch)
                    .listPage(0, 1);
            if (!running.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "此表單正被執行中的流程使用（案件 " + running.get(0).getId()
                                + "），不可封存。已結束的案件不受影響。");
            }
        }
    }

    /** 依 id 取得（刪除前要記錄它的內容，因此需要這個查詢）。 */
    @Transactional(value = "formTransactionManager", readOnly = true)
    public FormDefinition getById(String id) {
        return defRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /**
     * 刪除 draft。
     *
     * <p>改動前只擋 published／archived，draft 就算已經有表單資料指向它
     * 也刪得掉（#59）。表單定義與表單資料之間沒有 FK，
     * {@code bpm_form_data.form_definition_id} 會留下一筆指向不存在表單的
     * 孤兒資料 —— 案件頁面讀得到內容，卻再也查不到它用的是哪張表單。
     *
     * <p>這裡<b>不</b>區分案件是否已結束：已結束的歷史資料也一樣是資料，
     * 刪掉定義只會讓它變成孤兒。要讓表單下架請用 archive。
     */
    @Transactional("formTransactionManager")
    public void delete(String id) {
        FormDefinition existing = defRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if ("published".equals(existing.getStatus()) || "archived".equals(existing.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Published/archived forms cannot be deleted. Archive instead.");
        }
        if (dataRepo.existsByFormDefinitionId(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "此表單已有表單資料指向它，刪除會留下孤兒資料，不可刪除。");
        }
        defRepo.delete(existing);
    }

    // FormData operations
    @Transactional("formTransactionManager")
    public FormData submitData(FormData data) {
        // 同 create()：送出表單資料必定是新增，不可因 body 夾帶 id
        // 而變成覆寫他人已送出的資料（submittedAt 不可更新 → 篡改無跡）。
        data.setId(null);
        return dataRepo.save(data);
    }

    /**
     * 某案件的所有表單資料列，依 {@code submittedAt} 遞減 ——
     * <b>最新版本排最前面</b>。
     *
     * <p>#58 起同一個案件可以有<b>多列</b>同一份表單：每一次退回修改都是
     * 新增一列（見 {@link #updateData}），舊版本保留。呼叫端若只要「目前
     * 的內容」取第一筆即可；全部列出則可以還原修改歷史。
     */
    @Transactional(value = "formTransactionManager", readOnly = true)
    public List<FormData> getDataByProcess(String processInstanceId) {
        return dataRepo.findByProcessInstanceIdOrderBySubmittedAtDesc(processInstanceId);
    }

    /**
     * 依 id 取得一筆表單資料。
     *
     * <p>存在的原因不只是「給 delete 記錄內容用」（那是 {@link #getById} 的用途），
     * 而是<b>物件層授權需要知道這筆資料掛在哪個案件上、以及誰是送件人</b>：
     * {@code PUT /api/form-data/{id}} 的路徑參數只有 {@code id}，而
     * {@code processInstanceId} 與 {@code submittedBy} 都在 DB 裡 ——
     * 守衛要拿前者去問 {@code ProcessAccessGuard.requireParticipant}，
     * 拿後者做 #58 的送件人比對。
     *
     * <p>⚠️ 不可改成「把守衛塞進 {@link #updateData}」：授權判斷必須留在 controller，
     * 否則 service 會同時被 HTTP 與非 HTTP 的呼叫路徑共用，卻只有其中一條
     * 有辦法做「呼叫者是誰」的判斷。{@link FormDefinitionController#delete}
     * 也是同樣的形狀：先 {@code getById} 取得內容，再執行變更。
     */
    @Transactional(value = "formTransactionManager", readOnly = true)
    public FormData getDataById(String id) {
        return dataRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /**
     * 修改表單資料 —— <b>版本化</b>：新增一列，不覆寫原列（#58）。
     *
     * <h2>為什麼不是「把舊列改掉」</h2>
     *
     * <p>表單資料是「這張單填了什麼」的證據，薪資、理由、金額都在裡面。
     * 改動前這裡直接 {@code existing.setDataJson(updated.getDataJson())}：
     * 原始送件當場消失，而 {@code submittedAt} 是 {@code updatable = false}
     * —— 連「什麼時候被改的」都不會變。稽核雖然記了 {@code FORM_UPDATE}，
     * 卻沒有留下「改動前的內容」，事後調查只能看到結果。
     *
     * <p>現在每次修改新增一列：{@code processInstanceId}／
     * {@code formDefinitionId}／{@code submittedBy}／{@code taskId} 沿用舊列，
     * {@code dataJson} 是新的，{@code submittedAt} 由 {@code @PrePersist}
     * 寫成當下時間。舊列完整保留，版本鏈由 {@code FORM_UPDATE} 稽核的
     * {@code formDataId}（新列）與 {@code supersededFormDataId}（被取代的舊列）
     * 串起（見 {@code FormDataController.update}）。
     *
     * <p>⚠️ {@code taskId} 沿用舊列而不是換成現任補件任務：{@code PUT} 的
     * body 只保證帶 {@code dataJson}，換成別的來源會讓非 HTTP 呼叫路徑
     * 拿到不同的結果。這是已知的簡化，不影響「誰送的、什麼時候送的」。
     *
     * <p>⚠️ 授權與退回狀態<b>不在</b>這裡：只有 controller 知道呼叫者是誰
     * （見 {@link #getDataById} 的說明）。本方法只負責「新增版本」這個資料動作。
     *
     * @return 新版本列（呼叫端要拿它的 id 寫稽核）
     */
    @Transactional("formTransactionManager")
    public FormData updateData(String id, FormData updated) {
        FormData existing = dataRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        FormData revision = new FormData();
        revision.setFormDefinitionId(existing.getFormDefinitionId());
        revision.setProcessInstanceId(existing.getProcessInstanceId());
        revision.setTaskId(existing.getTaskId());
        revision.setSubmittedBy(existing.getSubmittedBy());
        revision.setDataJson(updated.getDataJson());
        // submittedAt 刻意留 null → @PrePersist 寫入當下時間。
        // 不可沿用 existing.getSubmittedAt()：那會讓新版本在
        // getDataByProcess 的遞減排序中與舊版本同時間（甚至排在後面），
        // 「最新版本排最前面」就不再成立。
        return dataRepo.save(revision);
    }
}
