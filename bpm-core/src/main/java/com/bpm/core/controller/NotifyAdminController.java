package com.bpm.core.controller;

import org.springframework.transaction.annotation.Transactional;
import com.bpm.core.security.CallerId;
import com.bpm.core.audit.ConfigChangeAuditor;
import com.bpm.core.model.NotifyConfig;
import com.bpm.core.model.NotifyTemplate;
import com.bpm.core.repository.NotifyConfigRepository;
import com.bpm.core.repository.NotifyTemplateRepository;
import com.bpm.core.webhook.WebhookUrlPolicy;
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
    private final WebhookUrlPolicy urlPolicy;

    public NotifyAdminController(NotifyTemplateRepository templateRepo,
                                 NotifyConfigRepository configRepo,
                                 ConfigChangeAuditor auditor,
                                 WebhookUrlPolicy urlPolicy) {
        this.templateRepo = templateRepo;
        this.configRepo = configRepo;
        this.auditor = auditor;
        this.urlPolicy = urlPolicy;
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
        // webhookUrl 是可張貼到 Teams 頻道的 bearer token：記全文等於把
        // token 複製進稽核庫（與模板 body 記摘要同一條理由）。記 sha256
        // 前綴＋長度，足以證明「投遞目標被改過」與「改了多少」。
        String webhookUrl = c.getWebhookUrl() == null ? "" : c.getWebhookUrl();
        m.put("webhookUrlSha256Prefix", sha256Prefix(webhookUrl));
        m.put("webhookUrlChars", webhookUrl.length());
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
    @Transactional("primaryTransactionManager")
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
    @Transactional("primaryTransactionManager")
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
     * {@code NotifyConfig} 之後，{@code EmailConsumer} 每次發通知都取不到模板
     * 而退回硬編的預設模板（見 {@link #requireExistingTemplate} 的註解），
     * 而同一個 config 之後每則通知都重踩。那正是 P1-13 的失敗模式 ——
     * <b>只是 delete 這條路徑把同一個洞重新打開了</b>。
     *
     * <p>（#84 補上 update 端的檢查之後，這句「P1-13 只在 create/update 擋住了
     * 錯誤的 templateId」才是真的；補上前 {@code updateConfig} 沒有這道檢查。
     * 註解描述一個不存在的檢查比沒有註解更糟 —— 它讓下一個人以為這條路徑
     * 已經安全。）
     *
     * <p>另外原本對不存在的 id 是靜默的 no-op。回 404 才誠實 ——
     * 呼叫端以為刪掉了，而實際上什麼都沒發生。
     */
    @DeleteMapping("/notify-templates/{id}")
    @Transactional("primaryTransactionManager")
    public void deleteTemplate(@PathVariable String id, @CallerId String operatorId) {
        NotifyTemplate existing = templateRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        var referencing = configRepo.findByTemplateId(id);
        if (!referencing.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "仍有 %d 筆通知設定引用這個模板，刪掉之後那些通知的模板會被靜默忽略"
                            + "（EmailConsumer 只會記 WARN 而改用預設模板）。請先改掉或刪除引用它的設定：%s"
                            .formatted(referencing.size(),
                                    referencing.stream().map(NotifyConfig::getId).toList()));
        }

        var digest = templateDigest(existing);
        templateRepo.deleteById(id);
        auditor.record(operatorId, TEMPLATE, "delete", id, digest);
    }

    // Configs

    /**
     * templateId 必須指向實際存在的模板（security-audit P1-13；#84 補上 update 端）。
     *
     * <p><b>為什麼寫入端必須擋。</b>改動前 {@code createConfig} 與
     * {@code updateConfig} 都不驗證，而 {@code NotifyConfig.templateId}
     * 沒有 {@code nullable=false}。結果依消費端當時的防護分成兩種：
     *
     * <ul>
     *   <li><b>消費端無防護時</b>（P1-13 的原始狀態）：
     *       {@code findById(null)} 拋 {@code IllegalArgumentException} →
     *       retry 3 次後進 {@code dlq.bpm} → 該通知<b>永久遺失</b>，
     *       而且同一 config 之後每則通知都重踩同一個坑。</li>
     *   <li><b>消費端已有防護時</b>（現況，{@code EmailConsumer} 記 WARN
     *       並退回硬編預設模板）：通知不會遺失，但管理員設定的模板
     *       <b>被靜默忽略</b> —— 收件人收到的是不含流程識別、不含表單內容的
     *       通用句，而管理員在後台看到的是「設定成功」。稽核紀錄同樣會記下
     *       {@code after.templateId = <不存在的 id>}，等於組態變更紀錄說了一套、
     *       系統實際做的是另一套。</li>
     * </ul>
     *
     * <p>後者危害小得多，但<b>更難察覺</b>：沒有例外、沒有錯誤訊息、
     * 通知照常寄出，只有伺服器日誌裡一行 WARN。而「相關人員要知道自己有東西
     * 該簽」正是這個平台的價值之一，模板被靜默忽略等同於簽核活動被悄悄降級。
     * 所以擋在寫入端才是正確的位置 —— 消費端的防護是第二道，不是第一道。
     *
     * <p><b>為什麼是 400。</b>呼叫端送來一個指向不存在物件的參照，
     * 是請求本身有誤。與 {@link #deleteTemplate} 擋到「仍被引用」時的 409
     * 是不同的情況：那個模板確實存在，是「現在不能刪」的狀態衝突。
     *
     * <p><b>為什麼是共用方法而不是兩處各寫一份。</b>（理由與
     * {@code ProcessAccessGuard} 類別註解相同 —— 不是 DRY，是
     * <b>規則只能有一份</b>。）這個缺陷本身就是分散造成的：
     * {@code createConfig} 有檢查、{@code updateConfig} 沒有，而
     * {@code deleteTemplate} 的註解宣稱「create/update 都擋住了」——
     * 註解描述的是<b>不存在的</b>行為。只要判斷散在不同方法裡，
     * 下次補一條路徑就會再漏一次，而且漏的時候沒有測試會紅。
     */
    private void requireExistingTemplate(String templateId) {
        if (templateId == null || templateId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "templateId 為必填");
        }
        if (!templateRepo.existsById(templateId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "templateId 指向不存在的模板: " + templateId);
        }
    }

    /**
     * {@code channel=teams} 的 {@code webhookUrl} 規則（#32）。
     *
     * <p>Teams 設定沒有 URL 就沒有投遞目標，而 {@code NotifyConfig.webhookUrl}
     * 是可空的（email 設定不需要它）—— 唯一擋得住「teams 但沒有 URL」的地方
     * 就是寫入端。消費端遇到空值只會記 warn 並略過，管理員在後台看到的卻是
     * 「設定成功」，與 P1-13 的「模板被靜默忽略」是同一種失敗形狀。
     *
     * <p>通過必填之後先過 {@link WebhookUrlPolicy#rejectionReason(String)}：
     * 這個 URL 是伺服器會主動 POST 的目標，不擋的話管理端就成為 SSRF 的
     * 寫入點（消費端仍會再過一次，那是第二道）。被拒 → 400 並帶上原因，
     * 呼叫端才知道要改什麼。
     *
     * <p>⚠️ 刻意<b>不</b>加 channel 白名單。現行測試
     * （{@code NotifyConfigTargetValidationTest} 的 tamper 用
     * {@code teams-<uuid>} 這種值並期待 200）與資料都允許自訂 channel
     * 字串，加白名單會是這個工項範圍外的行為變更；只有 {@code teams}
     * 這個精確值會被本方法檢查。
     *
     * <p>update 端也套用同一條規則：只在 create 檢查的話，管理員可以先
     * 建一筆合法的 teams 設定，再 PUT 一個內網 URL 繞過閘門（消費端仍會
     * 擋，但寫入端就不該放行）。
     */
    private void requireValidTeamsWebhook(NotifyConfig c) {
        if (!"teams".equals(c.getChannel())) return;
        String webhookUrl = c.getWebhookUrl();
        if (webhookUrl == null || webhookUrl.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "channel=teams 時 webhookUrl 為必填");
        }
        String reason = urlPolicy.rejectionReason(webhookUrl);
        if (reason != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "webhookUrl 被拒絕: " + reason);
        }
    }

    @PostMapping("/notify-configs")
    @Transactional("primaryTransactionManager")
    public NotifyConfig createConfig(@RequestBody NotifyConfig c,
                                     @CallerId String operatorId) {
        c.setId(null);
        requireExistingTemplate(c.getTemplateId());
        requireValidTeamsWebhook(c);
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

    /**
     * 修改通知設定。
     *
     * <p>⚠️ <b>templateId 與 create 端同樣必須驗證存在</b>（#84）。
     * 改動前這一條路徑完全沒有檢查，而 {@link #deleteTemplate} 上方的註解
     * 卻宣稱「P1-13 只在 create/update 擋住了錯誤的 templateId」——
     * 註解寫的是一個不存在的行為。實測（修前）：
     * {@code PUT} 帶不存在的 templateId 回 <b>200</b> 且資料真的被寫進資料庫，
     * 同一個值走 {@code POST} 卻被擋成 <b>400</b>。
     *
     * <p>實測到的後果是「設定被靜默忽略」而不是「通知永久遺失」：
     * {@code EmailConsumer} 已在 P1-13 補上消費端防護，會記 WARN 並退回
     * 硬編預設模板。修補理由與 {@link #requireExistingTemplate} 相同。
     *
     * <p>順序：先驗 templateId 再碰 entity。擋下來之後交易回捲，
     * 而且沒有任何 save 被執行 —— 「回 400 卻資料已被改掉」這種組合
     * 會讓只斷言狀態碼的測試以為守衛有效。
     */
    @PutMapping("/notify-configs/{id}")
    @Transactional("primaryTransactionManager")
    public NotifyConfig updateConfig(@PathVariable String id, @RequestBody NotifyConfig c,
                                     @CallerId String operatorId) {
        NotifyConfig existing = configRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        requireExistingTemplate(c.getTemplateId());
        // #32：與 create 同一條規則（見 requireValidTeamsWebhook）——
        // 順序同樣是「先驗證再碰 entity」，被擋下時交易回捲且零 save。
        requireValidTeamsWebhook(c);
        var before = configDigest(existing);
        existing.setProcessDefinitionKey(c.getProcessDefinitionKey());
        existing.setEventType(c.getEventType());
        existing.setChannel(c.getChannel());
        existing.setWebhookUrl(c.getWebhookUrl());
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
    @Transactional("primaryTransactionManager")
    public void deleteConfig(@PathVariable String id, @CallerId String operatorId) {
        // 同 deleteTemplate：對不存在的 id 靜默 no-op 會讓呼叫端以為刪掉了。
        NotifyConfig existing = configRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var digest = configDigest(existing);
        configRepo.deleteById(id);
        auditor.record(operatorId, CONFIG, "delete", id, digest);
    }
}
