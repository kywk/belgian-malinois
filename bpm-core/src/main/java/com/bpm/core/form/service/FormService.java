package com.bpm.core.form.service;

import com.bpm.core.form.model.FormData;
import com.bpm.core.form.model.FormDefinition;
import com.bpm.core.form.repository.FormDataRepository;
import com.bpm.core.form.repository.FormDefinitionRepository;
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

import java.util.List;

@Service
public class FormService {

    private final FormDefinitionRepository defRepo;
    private final FormDataRepository dataRepo;

    private final TransactionTemplate versionTx;

    public FormService(FormDefinitionRepository defRepo, FormDataRepository dataRepo,
                       @Qualifier("formTransactionManager") PlatformTransactionManager formTransactionManager) {
        this.defRepo = defRepo;
        this.dataRepo = dataRepo;
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

    @Transactional("formTransactionManager")
    public FormDefinition archive(String id) {
        FormDefinition existing = defRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!"published".equals(existing.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Only published forms can be archived");
        }
        existing.setStatus("archived");
        return defRepo.save(existing);
    }

    /** 依 id 取得（刪除前要記錄它的內容，因此需要這個查詢）。 */
    @Transactional(value = "formTransactionManager", readOnly = true)
    public FormDefinition getById(String id) {
        return defRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @Transactional("formTransactionManager")
    public void delete(String id) {
        FormDefinition existing = defRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if ("published".equals(existing.getStatus()) || "archived".equals(existing.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Published/archived forms cannot be deleted. Archive instead.");
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

    @Transactional(value = "formTransactionManager", readOnly = true)
    public List<FormData> getDataByProcess(String processInstanceId) {
        return dataRepo.findByProcessInstanceIdOrderBySubmittedAtDesc(processInstanceId);
    }

    /**
     * 依 id 取得一筆表單資料。
     *
     * <p>存在的原因不只是「給 delete 記錄內容用」（那是 {@link #getById} 的用途），
     * 而是<b>物件層授權需要知道這筆資料掛在哪個案件上</b>：
     * {@code PUT /api/form-data/{id}} 的路徑參數只有 {@code id}，而
     * {@code processInstanceId} 在 DB 裡 —— 守衛要拿它去問
     * {@code ProcessAccessGuard.requireParticipant}。
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

    @Transactional("formTransactionManager")
    public FormData updateData(String id, FormData updated) {
        FormData existing = dataRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        existing.setDataJson(updated.getDataJson());
        return dataRepo.save(existing);
    }
}
