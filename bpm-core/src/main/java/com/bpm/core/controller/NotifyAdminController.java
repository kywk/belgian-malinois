package com.bpm.core.controller;

import com.bpm.core.security.CallerId;
import com.bpm.core.audit.ConfigChangeAuditor;
import com.bpm.core.model.NotifyConfig;
import com.bpm.core.model.NotifyTemplate;
import com.bpm.core.repository.NotifyConfigRepository;
import com.bpm.core.repository.NotifyTemplateRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 通知模板與通知設定的管理端點。
 *
 * <h2>為什麼這裡的每個變更都必須稽核（security-audit P2-4）</h2>
 *
 * <p>改動前這裡有六個變更端點、<b>零個稽核呼叫</b>（{@link #createTemplate}
 * 的註解當時已經指出這件事）。
 *
 * <p>通知設定是一條<b>隱藏簽核活動</b>的路徑，而且它不需要碰任何案件資料：
 * 把某個事件的通知 {@code enabled} 關掉，相關人員就不會知道有案件在跑；
 * 改掉模板內容，可以植入釣魚連結或讓通知看起來無關緊要。
 * 兩者都不會在案件的稽核軌跡上留下任何痕跡 —— 因為變的不是案件。
 *
 * <h2>模板內容記摘要而非全文</h2>
 *
 * <p>模板本文可能很長，而且它本身就是要調查的內容（例如被植入的連結）。
 * 記全文會讓稽核庫膨脹並複製一份可疑內容；記 sha256 前綴加長度，
 * 足以證明「內容變了」與「變了多少」，要看內容則去比對模板版本。
 */
@RestController
@RequestMapping("/api/admin")
public class NotifyAdminController {

    private static final String TEMPLATE = "notify-template";
    private static final String CONFIG = "notify-config";

    private final NotifyTemplateRepository templateRepo;
    private final NotifyConfigRepository configRepo;
    private final ConfigChangeAuditor auditor;

    public NotifyAdminController(NotifyTemplateRepository templateRepo,
                                 NotifyConfigRepository configRepo,
                                 ConfigChangeAuditor auditor) {
        this.templateRepo = templateRepo;
        this.configRepo = configRepo;
        this.auditor = auditor;
    }

    /** 模板內容的摘要。見類別註解說明為什麼不記全文。 */
    private static java.util.Map<String, Object> templateDigest(NotifyTemplate t) {
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("name", t.getName() == null ? "" : t.getName());
        m.put("channel", t.getChannel() == null ? "" : t.getChannel());
        // 主旨很短且是最常被改的地方，直接記全文。
        m.put("subjectTemplate", t.getSubjectTemplate() == null ? "" : t.getSubjectTemplate());
        String body = t.getBodyTemplate() == null ? "" : t.getBodyTemplate();
        m.put("bodySha256Prefix", sha256Prefix(body));
        m.put("bodyChars", body.length());
        return m;
    }

    private static java.util.Map<String, Object> configDigest(NotifyConfig c) {
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("processDefinitionKey", c.getProcessDefinitionKey() == null ? "" : c.getProcessDefinitionKey());
        m.put("eventType", c.getEventType() == null ? "" : c.getEventType());
        m.put("channel", c.getChannel() == null ? "" : c.getChannel());
        m.put("templateId", c.getTemplateId() == null ? "" : c.getTemplateId());
        // enabled 從 true 變 false 就是「讓相關人員不再收到通知」。
        m.put("enabled", String.valueOf(c.getEnabled()));
        return m;
    }

    private static String sha256Prefix(String s) {
        try {
            var d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
            return sb + "…";
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 必須存在", e);
        }
    }

    // Templates
    @PostMapping("/notify-templates")
    public NotifyTemplate createTemplate(@RequestBody NotifyTemplate t,
                                         @CallerId String operatorId) {
        // 必定新增。夾帶 id 的話 save() 會走 merge → 覆寫既有模板
        // （可植入釣魚連結）。零稽核那一半已在本次補上（P2-4）。
        t.setId(null);
        NotifyTemplate saved = templateRepo.save(t);
        auditor.record(operatorId, TEMPLATE, "create", saved.getId(), templateDigest(saved));
        return saved;
    }

    @GetMapping("/notify-templates")
    public List<NotifyTemplate> listTemplates() { return templateRepo.findAll(); }

    @GetMapping("/notify-templates/{id}")
    public NotifyTemplate getTemplate(@PathVariable String id) {
        return templateRepo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @PutMapping("/notify-templates/{id}")
    public NotifyTemplate updateTemplate(@PathVariable String id, @RequestBody NotifyTemplate t,
                                         @CallerId String operatorId) {
        NotifyTemplate existing = templateRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        // 先取摘要再套用 —— 順序反了就拿不到舊值。
        var before = templateDigest(existing);
        existing.setName(t.getName());
        existing.setChannel(t.getChannel());
        existing.setSubjectTemplate(t.getSubjectTemplate());
        existing.setBodyTemplate(t.getBodyTemplate());
        NotifyTemplate saved = templateRepo.save(existing);

        var detail = new java.util.LinkedHashMap<String, Object>();
        before.forEach((k, v) -> detail.put("before." + k, v));
        templateDigest(saved).forEach((k, v) -> detail.put("after." + k, v));
        auditor.record(operatorId, TEMPLATE, "update", id, detail);
        return saved;
    }

    /**
     * 刪除模板。
     *
     * <p>⚠️ 必須先檢查有沒有設定引用它（security-audit P2-4 施作時發現）。
     *
     * <p>原本是直接 {@code deleteById}。留下指向不存在模板的
     * {@code NotifyConfig} 之後，{@code EmailConsumer} 取不到模板 →
     * retry 後進 DLQ → 通知永久遺失，而同一個 config 之後每則通知都重踩。
     * 那正是 P1-13 的失敗模式 —— P1-13 只在 create/update 擋住了錯誤的
     * templateId，刪除這條路徑把同一個洞重新打開了。
     *
     * <p>另外原本對不存在的 id 是靜默的 no-op。回 404 才誠實 ——
     * 呼叫端以為刪掉了，而實際上什麼都沒發生。
     */
    @DeleteMapping("/notify-templates/{id}")
    public void deleteTemplate(@PathVariable String id, @CallerId String operatorId) {
        NotifyTemplate existing = templateRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        var referencing = configRepo.findByTemplateId(id);
        if (!referencing.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "仍有 %d 筆通知設定引用這個模板，刪掉會讓那些通知進入死信佇列而永久遺失。請先改掉或刪除引用它的設定：%s"
                            .formatted(referencing.size(),
                                    referencing.stream().map(NotifyConfig::getId).toList()));
        }

        var digest = templateDigest(existing);
        templateRepo.deleteById(id);
        auditor.record(operatorId, TEMPLATE, "delete", id, digest);
    }

    // Configs
    @PostMapping("/notify-configs")
    public NotifyConfig createConfig(@RequestBody NotifyConfig c,
                                     @CallerId String operatorId) {
        c.setId(null);
        // ⚠️ templateId 必須指向存在的模板（security-audit P1-13）。
        // 改動前完全不驗證，而 NotifyConfig 也沒有 nullable=false：
        // 一筆 templateId 為 null（或指向不存在的模板）的設定，會讓
        // EmailConsumer 的 findById(null) 拋 IllegalArgumentException →
        // retry 3 次後進 dlq.bpm → 該通知永久遺失，
        // 而且同一 config 之後每則通知都重踩同一個坑。
        //
        // 消費端已加上防護（改用預設模板並記錄警告），但錯誤設定不該
        // 一開始就能存進去 —— 在寫入端擋掉才是正確的位置。
        if (c.getTemplateId() == null || c.getTemplateId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "templateId 為必填");
        }
        if (!templateRepo.existsById(c.getTemplateId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "templateId 指向不存在的模板: " + c.getTemplateId());
        }
        NotifyConfig saved = configRepo.save(c);
        auditor.record(operatorId, CONFIG, "create", saved.getId(), configDigest(saved));
        return saved;
    }

    @GetMapping("/notify-configs")
    public List<NotifyConfig> listConfigs(@RequestParam(required = false) String processDefinitionKey) {
        if (processDefinitionKey != null)
            return configRepo.findByProcessDefinitionKeyOrderByEventType(processDefinitionKey);
        return configRepo.findAll();
    }

    @PutMapping("/notify-configs/{id}")
    public NotifyConfig updateConfig(@PathVariable String id, @RequestBody NotifyConfig c,
                                     @CallerId String operatorId) {
        NotifyConfig existing = configRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var before = configDigest(existing);
        existing.setProcessDefinitionKey(c.getProcessDefinitionKey());
        existing.setEventType(c.getEventType());
        existing.setChannel(c.getChannel());
        existing.setTemplateId(c.getTemplateId());
        existing.setEnabled(c.getEnabled());
        NotifyConfig saved = configRepo.save(existing);

        var detail = new java.util.LinkedHashMap<String, Object>();
        before.forEach((k, v) -> detail.put("before." + k, v));
        configDigest(saved).forEach((k, v) -> detail.put("after." + k, v));
        auditor.record(operatorId, CONFIG, "update", id, detail);
        return saved;
    }

    @DeleteMapping("/notify-configs/{id}")
    public void deleteConfig(@PathVariable String id, @CallerId String operatorId) {
        // 同 deleteTemplate：對不存在的 id 靜默 no-op 會讓呼叫端以為刪掉了。
        NotifyConfig existing = configRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var digest = configDigest(existing);
        configRepo.deleteById(id);
        auditor.record(operatorId, CONFIG, "delete", id, digest);
    }
}
