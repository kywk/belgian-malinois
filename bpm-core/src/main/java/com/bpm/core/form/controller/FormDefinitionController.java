package com.bpm.core.form.controller;

import org.springframework.transaction.annotation.Transactional;
import com.bpm.core.security.CallerId;
import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.dto.AuditEvent;
import com.bpm.core.form.model.FormDefinition;
import com.bpm.core.form.service.FormService;
import com.bpm.core.security.ProcessAccessGuard;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 表單定義（審核表的 schema）。
 *
 * <h2>#81：{@code POST} 的 {@code createdBy} 曾可冒用</h2>
 *
 * <p>改動前 {@code FormService.create()} 完全不碰 {@code createdBy}
 * —— 它只用 {@code @CallerId} 餵稽核，資料列那一欄則是「body 送什麼就是什麼」。
 * 而 {@code createdBy} 有 setter（<b>刻意沒有</b>標
 * {@code @JsonProperty(READ_ONLY)}，所以 Jackson 會反序列化它），
 * 結果是<b>任何持有 {@code bpm:form:design} 的人都能把審核表記成別人做的</b>。
 *
 * <p>危害是「這張審核表是誰做的」不可信，而這個欄位正是日後追查
 * 「誰改了審核規則」的入口。
 *
 * <p>⚠️ <b>負向控制組實測：後果比「可冒用」更嚴重。</b>缺陷期間<b>省略</b>
 * {@code createdBy} 時，資料庫存的是 {@code null} 而不是呼叫者
 * （測試印出 {@code expected: "mgr001" but was: null}）——
 * 也就是說正常呼叫下這欄根本是空的，<b>每一張經由本端點建立的審核表都沒有作者</b>。
 * 修好之後順帶把「正常情況下也記得到作者」這件事補回來了。
 *
 * <p>⚠️ <b>與同一個 controller 的 {@code createRevision} 不一致</b>：
 * 那條路徑走 {@code createNextDraft(formKey, userId)}，{@code createdBy} 由
 * {@code @CallerId} 決定。同一個概念、兩個端點、兩套規則 ——
 * 那種組合型式的差異比沒有檢查更難察覺，因為兩邊單獨看起來都合理。
 *
 * <h2>三條規則沿用 #66／#71／#72 的既有政策，不另創第四組</h2>
 *
 * <ol>
 *   <li><b>帶了與自己不符的值 → 明確 400</b>（{@link ProcessAccessGuard#requireSelf}）。</li>
 *   <li><b>省略／空白 → 呼叫者</b>，並且一定要<b>寫入</b>而不是留 null。</li>
 *   <li><b>送出與自己相同的值不算冒用</b>：{@code createdBy} 是 JPA entity 欄位，
 *       Jackson 反序列化後無法分辨「沒送」與「送了 null」。</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/forms")
public class FormDefinitionController {

    private final FormService formService;
    private final AuditEventPublisher auditPublisher;
    // #81 抽出來的身分欄位守衛。與 FormDataController 的 submittedBy、
    // DocumentController 的 createdBy 必須共用同一份 requireSelf：
    // 授權規則只能有一份（見 ProcessAccessGuard 類別註解）。
    private final ProcessAccessGuard accessGuard;

    public FormDefinitionController(FormService formService, AuditEventPublisher auditPublisher,
                                    ProcessAccessGuard accessGuard) {
        this.formService = formService;
        this.auditPublisher = auditPublisher;
        this.accessGuard = accessGuard;
    }

    /**
     * 建立全新的 formKey（第 1 版 draft）。
     *
     * <h2>⚠️ 為什麼是明確 400 拒絕冒用，而不是「先接受再覆寫成呼叫者」</h2>
     *
     * <p>覆寫會讓呼叫端<b>以為它自己指定的值生效了</b>，而實際改到別的地方 ——
     * 下一次除錯會從錯誤的方向開始。呼叫端送來的身分與它想改的東西對不上，
     * 這是<b>呼叫端的 bug</b>，把它講出來比假裝沒事更有用。
     * 理由與 {@code FormDataController.submit} 的 {@code submittedBy}、
     * {@code DocumentController.create} 的 {@code createdBy} 完全相同
     * （#66／#72 已定案）。
     *
     * <h2>⚠️ 為什麼守衛在 service <b>之前</b>（順序決定有沒有副作用）</h2>
     *
     * <p>{@code formService.create} 內部有 formKey 已存在與空白的檢查。
     * 冒用與「formKey 已存在」同時成立時，必須先回身分這一個錯誤 ——
     * 反過來會讓呼叫端以為只要換一個 formKey 就能把冒用送出去。
     *
     * <h2>⚠️ 為什麼稽核 operatorId 不用 {@code def.getCreatedBy()}</h2>
     *
     * <p>它本來就用 {@code @CallerId}，所以稽核紀錄從頭到尾沒有被冒用污染；
     * 缺陷的影響面只有資料列那一欄。守衛擋下時 controller 整個方法中止，
     * {@code audit(...)} 不會被執行 → <b>不會有「宣稱發生、實際沒發生」的
     * {@code FORM_UPDATE}</b>（#66／#72／#85 的稽核誠實性政策）。
     */
    @PostMapping
    @Transactional("formTransactionManager")
    public FormDefinition create(@RequestBody FormDefinition def,
                                 @CallerId
                                 String userId) {
        // createdBy = 登入者（省略時必須寫入，而不是留 null：
        // 欄位可為 null 會讓「這張審核表是誰做的」變成無解）。
        def.setCreatedBy(accessGuard.requireSelf(def.getCreatedBy(), userId, "createdBy"));

        FormDefinition saved = formService.create(def);
        audit("FORM_UPDATE", userId, saved, "create");
        return saved;
    }

    /**
     * 為既有 formKey 建立下一版 draft —— 已發布表單的改版路徑（P1-12）。
     *
     * <p>改動前完全沒有這個端點，因此 data.sql 種下的四張 published 表單
     * 透過 API 完全不可修改。
     */
    @PostMapping("/{formKey}/revisions")
    @Transactional("formTransactionManager")
    public FormDefinition createRevision(@PathVariable String formKey,
                                         @CallerId
                                         String userId) {
        FormDefinition draft = formService.createNextDraft(formKey, userId);
        audit("FORM_UPDATE", userId, draft, "revise");
        return draft;
    }

    @GetMapping
    public Page<FormDefinition> list(@RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        return formService.list(PageRequest.of(page, size));
    }

    @GetMapping("/{formKey}")
    public FormDefinition getSchema(@PathVariable String formKey,
                                     @RequestParam(required = false) Integer version) {
        return formService.getSchema(formKey, version);
    }

    /**
     * 修改一份 draft。
     *
     * <h2>⚠️ 這裡刻意<b>不</b>套用 {@code requireSelf(createdBy)}（與 POST 的差別）</h2>
     *
     * <p>不是遺漏，而是這條路徑上<b>沒有可冒用的欄位</b>：
     * {@code FormService.update} 只搬 {@code name} 與 {@code schemaJson}，
     * {@code createdBy}、{@code version}、{@code status} 一律留在原資料列上。
     * 也就是說 body 帶 {@code createdBy} 不會改到任何東西 —— 它既不能冒用，
     * 也不構成 {@code POST} 這種「會被寫進新資料列」的動作。
     *
     * <p>加一道拒絕閘門只會製造無意義的破壞（把一個本來就安全的請求變成 400），
     * 而且會讓「兩條路徑規則不同」這件事看起來像刻意為之，
     * 而不是「一條有洞、一條沒洞」。
     *
     * <p>真正會讓 {@code createdBy} 改變的只有 {@code createNextDraft}
     * （改版時由 {@code @CallerId} 決定）與 {@code create}（本工項）。
     */
    @PutMapping("/{id}")
    @Transactional("formTransactionManager")
    public FormDefinition update(@PathVariable String id, @RequestBody FormDefinition def,
                                 @CallerId
                                 String userId) {
        FormDefinition saved = formService.update(id, def);
        audit("FORM_UPDATE", userId, saved, "update");
        return saved;
    }

    @PostMapping("/{id}/publish")
    @Transactional("formTransactionManager")
    public FormDefinition publish(@PathVariable String id,
                                  @CallerId
                                  String userId) {
        FormDefinition saved = formService.publish(id);
        audit("FORM_UPDATE", userId, saved, "publish");
        return saved;
    }

    @PostMapping("/{id}/archive")
    @Transactional("formTransactionManager")
    public FormDefinition archive(@PathVariable String id,
                                  @CallerId
                                  String userId) {
        FormDefinition saved = formService.archive(id);
        audit("FORM_UPDATE", userId, saved, "archive");
        return saved;
    }

    @DeleteMapping("/{id}")
    @Transactional("formTransactionManager")
    public Map<String, String> delete(@PathVariable String id,
                                      @CallerId
                                      String userId) {
        FormDefinition existing = formService.getById(id);
        formService.delete(id);
        audit("FORM_UPDATE", userId, existing, "delete");
        return Map.of("status", "deleted");
    }

    /**
     * 表單定義變更的稽核（security-audit P1-15）。
     *
     * <p>改動前這個 controller 注入了 {@code auditPublisher} 卻<b>一次都沒用</b>
     * → 表單定義的 create／update／publish／archive／delete <b>全部零稽核</b>。
     * 「誰改了審核表的欄位」沒有任何軌跡 —— 而改 schema 等於改流程行為
     * （表單欄位 id 就是流程變數名，spec §8.5）。
     */
    private void audit(String operationType, String userId, FormDefinition def, String action) {
        auditPublisher.publish(new AuditEvent(operationType,
                userId != null ? userId : "unknown",
                null,
                null, Map.of("action", action,
                       "formKey", def.getFormKey() != null ? def.getFormKey() : "",
                       "version", def.getVersion() != null ? def.getVersion() : 0,
                       "status", def.getStatus() != null ? def.getStatus() : "",
                       "formDefinitionId", def.getId() != null ? def.getId() : "")));
    }
}
