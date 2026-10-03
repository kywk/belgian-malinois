package com.bpm.core.audit;

import com.bpm.core.audit.model.AuditLog;
import com.bpm.core.audit.model.OperationType;
import com.bpm.core.audit.service.AuditLogService;
import com.bpm.core.dto.AuditEvent;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 稽核寫入的唯一入口。<b>Fail-closed</b>：稽核寫不進去，業務操作就不成立
 * （security-audit P1-14，2026-09-29 決策）。
 *
 * <h2>為什麼是 fail-closed</h2>
 *
 * <p>改動前是 {@code @Async} + {@code catch (Exception) → log.error}：
 * 稽核 DB 掛掉時簽核照樣回 200，軌跡上少一筆而且沒人知道。
 * 對簽核系統而言「做了但查不到是誰做的」比「暫時做不了」更糟 ——
 * 後者會被看見、會被修；前者要等到稽核調查時才發現，那時已無法補救。
 * 代價是稽核 DB 的任何中斷都會直接變成簽核中斷（回 503），這是接受的取捨。
 *
 * <h2>怎麼做到「稽核失敗 → 業務回滾」</h2>
 *
 * <p>業務資料（{@code bpm_core_db}／{@code bpm_form_db}）與稽核
 * （{@code bpm_audit_db}）是不同資料庫，沒有 XA。所以做法是：
 * <b>業務交易進行中時，把稽核寫入掛在該交易的 {@code beforeCommit}</b>。
 * 稽核寫入失敗 → {@code beforeCommit} 拋例外 → Spring 回滾業務交易並把
 * 例外原樣拋回 → {@link AuditWriteException} 轉成 503。
 *
 * <p>為什麼掛 {@code beforeCommit} 而不是在呼叫點直接同步寫：
 * <ul>
 *   <li>呼叫點之後可能還有會失敗的動作。越晚寫，「稽核有、業務卻回滾了」的
 *       窗口越小。</li>
 *   <li>引擎 listener（{@code ProcessCompletedListener}）的
 *       {@code isFailOnException()} 是 false，在裡面拋例外會被 Flowable 吞掉。
 *       掛到交易上，失敗就發生在 commit 時，不經過那層吞例外的機制。</li>
 * </ul>
 *
 * <p>⚠️ <b>仍存在的窗口</b>：稽核在 {@code beforeCommit} 已 commit，業務交易
 * 卻在其後的 commit 本身失敗（例如 flush 時才浮現的約束違規、連線中斷）。
 * 結果是一筆「宣稱發生、實際沒發生」的稽核。這是沒有分散式交易時必然的二選一，
 * 我們選「多記」而不是「漏記」：多記可以從業務資料反查出來，漏記無從發現。
 *
 * <p>沒有進行中的交易時（例如唯讀查詢的 {@code DATA_ACCESS}），直接同步寫入；
 * 失敗一樣拋 {@link AuditWriteException}，資料就不會被回傳。
 *
 * <h2>⚠️ 呼叫端必須有交易</h2>
 *
 * <p>會寫業務資料的 controller 必須標 {@code @Transactional}（依資料庫選
 * {@code primaryTransactionManager} 或 {@code formTransactionManager}）。
 * 沒有交易時每個 Flowable／repository 呼叫會各自 commit，
 * 等到這裡寫稽核失敗時業務早已落地 —— 回 503 但資料已改，比 fail-open 更糟。
 */
@Component
public class AuditEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(AuditEventPublisher.class);
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    public AuditEventPublisher(AuditLogService auditLogService, ObjectMapper objectMapper) {
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
    }

    /**
     * 記錄一筆業務操作的稽核。與進行中的交易同生共死：
     * 交易回滾則不寫；稽核寫入失敗則交易回滾。
     *
     * @throws AuditWriteException 沒有進行中的交易且寫入失敗時（有交易時改在 commit 時拋出）
     */
    public void publish(AuditEvent event) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    write(event);
                }
            });
        } else {
            write(event);
        }
    }

    /**
     * 立即寫入、<b>不跟隨交易</b>、失敗時<b>不拋例外</b>。只用於以下兩種情況，
     * 其餘一律用 {@link #publish}。
     *
     * <p><b>1. 被拒絕的存取嘗試</b>（附件非參與者、外部 API 認證失敗）。
     * 拒絕之後呼叫端緊接著會拋例外，交易必然回滾 —— 若也掛在
     * {@code beforeCommit}，這筆紀錄永遠不會寫入。失敗時不拋：操作本身已經被拒絕，
     * fail-closed 已經成立；改拋 {@link AuditWriteException} 只會讓回應從 404／401
     * 變成 503，洩漏「這裡原本會拒絕你」以外的資訊，而不會多擋下任何東西。
     *
     * <p><b>2. 交易 commit 之後才發現的系統異常</b>（例如
     * {@code UnreachableTaskListener}）。業務已經落地，拋例外只會讓使用者收到
     * 錯誤、卻無法回滾任何東西。
     */
    public void publishDetached(AuditEvent event) {
        try {
            write(event);
        } catch (AuditWriteException e) {
            // write() 已記錄完整事件，這裡不重複。
        }
    }

    private void write(AuditEvent event) {
        try {
            AuditLog auditLog = new AuditLog();
            auditLog.setOperationType(OperationType.valueOf(event.operationType()));
            auditLog.setOperatorId(event.operatorId());
            auditLog.setOperatorSource(event.operatorSource());
            auditLog.setProcessDefinitionKey(event.processDefinitionKey());
            auditLog.setProcessInstanceId(event.processInstanceId());
            auditLog.setTaskId(event.taskId());
            auditLog.setBusinessKey(event.businessKey());
            if (event.detail() != null) {
                auditLog.setDetail(objectMapper.writeValueAsString(event.detail()));
            }
            auditLog.setCreatedAt(event.timestamp());
            auditLogService.append(auditLog);
        } catch (Exception e) {
            // 把完整事件內容一起記錄：業務雖已回滾，但這是判斷「使用者當時想做什麼」的唯一線索。
            log.error("稽核寫入失敗，操作已中止（fail-closed）。event={} 原因={}",
                    event, e.getMessage(), e);
            throw new AuditWriteException(e);
        }
    }
}
