package com.bpm.core.notify;

import com.bpm.core.http.SafeRestClients;
import com.bpm.core.model.NotifyConfig;
import com.bpm.core.model.NotifyTemplate;
import com.bpm.core.repository.NotifyConfigRepository;
import com.bpm.core.repository.NotifyTemplateRepository;
import com.bpm.core.webhook.WebhookUrlPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 通知訊息的消費端（{@code bpm.notify.queue}）：依 {@code NotifyConfig.channel}
 * 路由到 email 或 Teams（#32）。
 *
 * <h2>為什麼類別還叫 EmailConsumer</h2>
 *
 * <p>改名會連動 RabbitMQ 設定／測試／既有註解的指涉，而本體仍是
 * 「{@code bpm.notify.queue} 的 listener」；名稱留在歷史上的成本比一次
 * 大規模改名低。行為上它現在是<b>通知 consumer</b>：{@code email} 走模板／
 * fallback 寄信，{@code teams} 走 Incoming Webhook 推送。
 *
 * <h2>兩個 channel 的失敗語意</h2>
 *
 * <ul>
 *   <li><b>email</b>：SMTP 例外 → 往外丟（listener 既有 retry 1+2 次後進
 *       {@code dlq.bpm}）；模板缺失 → warn 後落硬編預設模板（P1-13）。</li>
 *   <li><b>teams</b>：非 2xx／逾時／連線例外 → 往外丟（與 email 一致，
 *       走同一條 retry→DLQ）；SSRF 政策拒絕 → log error、<b>不重試、
 *       不 fallback</b>（非暫時性，與 #44 一致）；{@code webhookUrl} 空 →
 *       warn 並略過該筆設定（後續設定照常，全數略過才落 email fallback）。</li>
 * </ul>
 *
 * <h2>⚠️ 收件人只對 email 有意義</h2>
 *
 * <p>Teams 推送是「一則訊息進一個頻道」，不需要任何收件人。因此
 * {@link #handle} 的「沒有 assignee／候選人就 return」不能再擋在設定迴圈
 * 之前 —— 否則群組任務（沒有 assignee）的 Teams 通知永遠送不出去。
 *
 * <h2>⚠️ 兩個 channel 同時設定時的已知取捨</h2>
 *
 * <p>一則通知訊息只會被 ack 一次：email 成功、teams 失敗（或反之）時
 * 整則訊息重試，已成功的那個 channel 會重複送達。既有 email 路徑沒有
 * 這個形狀（唯一約束下只有一筆 email 設定），#32 起同一事件可有兩筆設定，
 * 這個重複風險是共通的（真正消除需要 per-channel 冪等或 outbox，不在本次
 * 範圍）。
 */
@Component
public class EmailConsumer {

    private static final Logger log = LoggerFactory.getLogger(EmailConsumer.class);
    private final JavaMailSender mailSender;
    private final NotifyConfigRepository configRepo;
    private final NotifyTemplateRepository templateRepo;
    private final WebhookUrlPolicy urlPolicy;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    /**
     * 逾時與「不跟隨 3xx」都集中在 {@link SafeRestClients}（規則只有一份；
     * 為什麼逾時不可省略、為什麼重導是 SSRF 缺口，見該類別註解）。設定沿用
     * {@code bpm.webhook.*}，與 {@link TeamsNotifyDelegate} 同一份。
     */
    public EmailConsumer(JavaMailSender mailSender, NotifyConfigRepository configRepo,
                         NotifyTemplateRepository templateRepo,
                         WebhookUrlPolicy urlPolicy, ObjectMapper objectMapper,
                         @Value("${bpm.webhook.connect-timeout-ms:2000}") long connectTimeoutMs,
                         @Value("${bpm.webhook.read-timeout-ms:5000}") long readTimeoutMs) {
        this.mailSender = mailSender;
        this.configRepo = configRepo;
        this.templateRepo = templateRepo;
        this.urlPolicy = urlPolicy;
        this.objectMapper = objectMapper;
        this.restClient = SafeRestClients.create(connectTimeoutMs, readTimeoutMs);
    }

    @RabbitListener(queues = "bpm.notify.queue")
    public void handle(Map<String, Object> msg) {
        String event = str(msg, "event");
        String processDefKey = str(msg, "processDefinitionKey");

        // 收件人：優先 assignee；候選人任務沒有 assignee，改送給候選人。
        // 改動前只看 assignee，為 null 就 return → 群組待辦完全不發通知
        // （security-audit P1-13）。
        //
        // ⚠️ #32：這裡不再 early return —— 收件人只對 email 路徑有意義，
        // Teams 路徑不受影響（見類別註解）。early return 移到 fallback 前。
        List<String> recipients = resolveRecipients(msg);

        // ── ① 設定路由（#32）────────────────────────────────────────────
        // 同一 (key, event) 可以同時有 email 與 teams 兩筆設定（唯一約束含
        // channel），兩條都必須走完：任一條被處理過就不落硬編模板。
        if (processDefKey != null && !processDefKey.isBlank()) {
            List<NotifyConfig> configs = configRepo
                    .findByProcessDefinitionKeyAndEventTypeAndEnabledTrue(processDefKey, event);
            boolean handled = false;
            for (NotifyConfig cfg : configs) {
                if ("teams".equals(cfg.getChannel())) {
                    handled |= sendTeams(cfg, event, msg);
                    continue;
                }
                if (!"email".equals(cfg.getChannel())) continue;
                // ⚠️ templateId 可能為 null（NotifyConfig 沒有 nullable=false，
                // 而 create 端點先前也不驗證）。findById(null) 會拋
                // IllegalArgumentException → retry 3 次後進 dlq.bpm →
                // 該通知永久遺失，而且同一 config 之後每則通知都重踩。
                if (cfg.getTemplateId() == null || cfg.getTemplateId().isBlank()) {
                    log.warn("通知設定 {} 的 templateId 為空，改用預設模板（流程 {}／事件 {}）",
                            cfg.getId(), cfg.getProcessDefinitionKey(), cfg.getEventType());
                    continue;
                }
                var tmpl = templateRepo.findById(cfg.getTemplateId()).orElse(null);
                if (tmpl == null) {
                    log.warn("通知設定 {} 指向不存在的模板 {}，改用預設模板",
                            cfg.getId(), cfg.getTemplateId());
                    continue;
                }
                // 沒有收件人時 email 無處可送，但這筆設定仍算「已處理」——
                // fallback 也同樣沒有收件人，沒有理由再落一次。
                if (!recipients.isEmpty()) {
                    recipients.forEach(to -> sendWithTemplate(tmpl, msg, to));
                }
                handled = true;
            }
            if (handled) return;
        }

        // ── ② Fallback：硬編模板（email）────────────────────────────────
        if (recipients.isEmpty()) {
            log.debug("No assignee or candidate users, skipping notification");
            return;
        }
        DefaultMessage fallback = defaultMessage(event, msg);
        // 未知事件沿用原本 default -> return 的靜默丟棄：沒有模板、也沒有
        // 可組的預設文案，就沒有東西可送。
        if (fallback == null) return;
        recipients.forEach(to -> sendEmail(to, fallback.subject(), fallback.body()));
    }

    /**
     * Teams 路徑（#32）。
     *
     * <p>文案來源：{@code templateId} 有解析到 → 渲染後的 subject 當 title、
     * body 當 text；否則（空、或指向不存在的模板）用
     * {@link #defaultMessage} 的事件預設文案 —— 與 email fallback 共用同一份，
     * 兩邊文案不會分岔。
     *
     * @return {@code true} = 這筆設定已被處理（已送出，或政策拒絕這個
     *         非暫時性失敗），呼叫端不得再落 email fallback；
     *         {@code false} = 這筆設定被略過（{@code webhookUrl} 空、沒有
     *         可用的文案），後續設定照常。
     */
    private boolean sendTeams(NotifyConfig cfg, String event, Map<String, Object> msg) {
        String webhookUrl = cfg.getWebhookUrl();
        if (webhookUrl == null || webhookUrl.isBlank()) {
            log.warn("通知設定 {} 的 channel=teams 但 webhookUrl 為空，略過這筆設定（流程 {}／事件 {}）",
                    cfg.getId(), cfg.getProcessDefinitionKey(), cfg.getEventType());
            return false;
        }

        // 🔴 SSRF 閘門：與 TeamsNotifyDelegate 共用同一份政策
        // （WebhookUrlPolicy 是 repo 唯一一份）。寫入端
        // （NotifyAdminController）已經擋過一次，這裡是第二道 —— 資料可能
        // 來自 migration 前的舊列或直接 DB 寫入。
        String reason = urlPolicy.rejectionReason(webhookUrl);
        if (reason != null) {
            // 政策拒絕是非暫時性的：丟例外只會重試到 DLQ 再重踩，而
            // fallback 到 email 等於把「這個 channel 的設定」偷改成另一個
            // channel。因此 log error 後直接視為已處理。
            log.error("通知設定 {} 的 Teams webhook 目標被拒絕，不發送、不重試、不 fallback："
                    + "url={} 原因={}", cfg.getId(), webhookUrl, reason);
            return true;
        }

        String title = null;
        String body = null;
        if (cfg.getTemplateId() != null && !cfg.getTemplateId().isBlank()) {
            var tmpl = templateRepo.findById(cfg.getTemplateId()).orElse(null);
            if (tmpl == null) {
                log.warn("通知設定 {} 指向不存在的模板 {}，Teams 訊息改用預設文案",
                        cfg.getId(), cfg.getTemplateId());
            } else {
                title = replaceVars(tmpl.getSubjectTemplate(), msg);
                body = replaceVars(tmpl.getBodyTemplate(), msg);
            }
        }
        if (title == null || title.isBlank() || body == null) {
            DefaultMessage fallback = defaultMessage(event, msg);
            if (fallback == null) {
                log.warn("通知設定 {} 的 Teams 訊息沒有模板、事件 {} 也沒有預設文案，略過這筆設定",
                        cfg.getId(), event);
                return false;
            }
            if (title == null || title.isBlank()) title = fallback.subject();
            if (body == null) body = fallback.body();
        }
        if (title.isBlank() && body.isBlank()) {
            // 與 TeamsNotifyDelegate 的「不發空訊息」同一條規則：空的 Teams
            // 訊息只會在頻道留下一則空白通知。
            log.warn("通知設定 {} 的 Teams 訊息渲染後為空，不發送，略過這筆設定", cfg.getId());
            return false;
        }

        String payload = TeamsWebhookPayload.json(objectMapper, title, body);
        ResponseEntity<String> response;
        try {
            response = restClient.post()
                    .uri(webhookUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toEntity(String.class);
        } catch (RuntimeException e) {
            // 與 email 同一語意：逾時／連線失敗／4xx／5xx → 往外丟，讓 AMQP
            // listener 的既有 retry 接手（1+2 次），耗盡後進 dlq.bpm。
            log.error("Teams 通知發送失敗（將重試→DLQ）：設定={} url={}", cfg.getId(), webhookUrl, e);
            throw e;
        }
        if (!response.getStatusCode().is2xxSuccessful()) {
            // RestClient 對 4xx／5xx 已拋例外；3xx 會原樣回到這裡，而
            // SafeRestClients 不跟隨重導 —— 非 2xx 一律視為未送達。
            log.error("Teams 通知非 2xx，視為未送達（將重試→DLQ）：設定={} url={} status={}",
                    cfg.getId(), webhookUrl, response.getStatusCode().value());
            throw new IllegalStateException(
                    "Teams webhook 回非 2xx: " + response.getStatusCode().value());
        }

        log.info("Teams 通知已送出：設定={} url={}", cfg.getId(), webhookUrl);
        return true;
    }

    /**
     * 解析收件人。
     *
     * <p>assignee 優先；候選人任務（candidateUsers／candidateGroups）沒有
     * assignee，此時改送給 NotifyTaskListener 帶過來的候選人清單。
     */
    @SuppressWarnings("unchecked")
    private static List<String> resolveRecipients(Map<String, Object> msg) {
        String assignee = str(msg, "assignee");
        if (!assignee.isBlank()) return List.of(assignee);
        Object candidates = msg.get("candidateUsers");
        if (candidates instanceof List<?> list) {
            return list.stream().filter(Objects::nonNull)
                    .map(Object::toString).filter(v -> !v.isBlank()).distinct().toList();
        }
        return List.of();
    }

    private void sendWithTemplate(NotifyTemplate tmpl, Map<String, Object> vars, String to) {
        String subject = replaceVars(tmpl.getSubjectTemplate(), vars);
        String body = replaceVars(tmpl.getBodyTemplate(), vars);
        sendEmail(to, subject, body);
    }

    private String replaceVars(String template, Map<String, Object> vars) {
        if (template == null) return "";
        String result = template;
        for (var entry : vars.entrySet()) {
            result = result.replace("${" + entry.getKey() + "}", entry.getValue() != null ? entry.getValue().toString() : "");
        }
        // Standard aliases
        result = result.replace("${processName}", str(vars, "processDefinitionKey"));
        result = result.replace("${taskName}", str(vars, "taskName"));
        result = result.replace("${assigneeName}", str(vars, "assignee"));
        result = result.replace("${initiatorName}", str(vars, "initiator"));
        return result;
    }

    /** 事件預設文案（subject／body）。未知事件回 {@code null}。 */
    private record DefaultMessage(String subject, String body) {
    }

    /**
     * 「事件 → 預設文案」的唯一一份實作。
     *
     * <p>email fallback 與 Teams 的無模板路徑共用：兩條路徑都送同一句話，
     * 各自抄一份遲早會分岔。P2-1 紅線的既有排除（不放退回／拒絕原因）也在
     * 這裡，不因 channel 而異。
     */
    private static DefaultMessage defaultMessage(String event, Map<String, Object> msg) {
        String taskName = str(msg, "taskName");
        String initiator = str(msg, "initiator");

        return switch (event != null ? event : "") {
            case "task_assigned" -> new DefaultMessage(
                    "【BPM】您有新的待辦事項：" + taskName,
                    "您好，\n\n任務名稱：" + taskName + "\n申請人：" + initiator + "\n\n請登入 BPM 平台處理。");
            // #33：認領。收件人是其他候選人（NotifyPublisher.taskClaimed 把
            // claimedBy 認領者排除後放進 candidateUsers），所以 assignee 是空的。
            case "task_claimed" -> {
                String claimedBy = str(msg, "claimedBy");
                yield new DefaultMessage(
                        "【BPM】任務已被認領：" + taskName,
                        "您好，\n\n任務「" + taskName + "」已由 "
                                + (claimedBy.isBlank() ? "其他候選人" : claimedBy)
                                + " 認領，您不需要再處理。");
            }
            // #33/#6：催辦。收件人是目前受理人（assignee 優先）或候選人，
            // initiator 是發動催辦的申請人（署名用）。
            case "task_urged" -> new DefaultMessage(
                    "【BPM】催辦提醒：" + taskName,
                    "您好，\n\n申請人提醒您盡快處理任務：「" + taskName
                            + "」。\n申請人：" + initiator + "\n\n請登入 BPM 平台處理。");
            // #23：任務逾時提醒。由 TimeoutNotifyDelegate（boundary timer）觸發，
            // 不是 Flowable 的 timeout task event（引擎不發，見 WebhookTaskListener.matches）。
            // 收件人與催辦同一條規則：assignee 優先、候選任務送候選人。
            case "task_timeout" -> new DefaultMessage(
                    "【BPM】任務已逾時：" + taskName,
                    "您好，\n\n任務「" + taskName + "」已逾時，請盡快登入 BPM 平台處理。");
            // #7 殘餘收尾：撤回。收件人是現任受理人（assignee 優先）或候選人。
            // ⚠️ 文案是對「受理人」說的，不是對申請人：申請人自己剛按下撤回，
            // 再寄一封「您的申請已被撤回」給他沒有意義；受理人需要知道的是
            // 「這張單沒了、不用再處理」。initiator 是撤回的申請人（署名用）。
            case "process_cancelled" -> {
                String who = initiator.isBlank() ? "申請人" : "申請人「" + initiator + "」";
                yield new DefaultMessage(
                        "【BPM】案件已被撤回：" + taskName,
                        "您好，\n\n" + who + "已撤回案件，任務「" + taskName
                                + "」已不存在，您不需要再處理。");
            }
            case "process_returned" -> new DefaultMessage(
                    "【BPM】您的申請已被退回",
                    "您好，\n\n您的申請「" + taskName + "」已被退回，請修改後重新提交。");
            // ⚠️ 刻意不放退回／拒絕原因（security-audit P2-1 紅線）：
            // 原因在前端是「簽核意見」而不是 rejectReason 變數
            // （ActionDialog.vue 把退件／拒絕原因寫成 comment），
            // 而簽核意見全文屬於不得新增外送的敏感內容。
            // 硬編模板原本的「原因：」因此移除，不留一個永遠空白的標籤。
            case "process_rejected" -> new DefaultMessage(
                    "【BPM】您的申請已被拒絕",
                    "您好，\n\n您的申請「" + taskName + "」已被拒絕。");
            case "process_completed" -> new DefaultMessage(
                    "【BPM】您的申請已核准",
                    "您好，\n\n您的申請「" + taskName + "」已核准完成。");
            default -> null;
        };
    }

    private void sendEmail(String to, String subject, String body) {
        try {
            SimpleMailMessage mail = new SimpleMailMessage();
            mail.setTo(to + "@company.com");
            mail.setSubject(subject);
            mail.setText(body);
            mail.setFrom("bpm-noreply@company.com");
            mailSender.send(mail);
            log.info("Email sent to {}: {}", to, subject);
        } catch (Exception e) {
            log.error("Failed to send email to {}: {}", to, e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v != null ? v.toString() : "";
    }
}
