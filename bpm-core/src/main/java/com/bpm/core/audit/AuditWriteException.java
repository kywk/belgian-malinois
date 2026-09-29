package com.bpm.core.audit;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 稽核寫入失敗，操作因此中止（fail-closed，見 {@link AuditEventPublisher}）。
 *
 * <p>回 503 而非 500：這是依賴服務（稽核 DB）暫時不可用，操作本身沒有錯，
 * 稍後重試應該會成功。500 會讓人以為是程式錯誤、去查錯方向。
 */
@ResponseStatus(value = HttpStatus.SERVICE_UNAVAILABLE, reason = "稽核服務暫時無法寫入，操作未執行，請稍後重試")
public class AuditWriteException extends RuntimeException {
    public AuditWriteException(Throwable cause) {
        super("稽核寫入失敗，操作已中止", cause);
    }
}
