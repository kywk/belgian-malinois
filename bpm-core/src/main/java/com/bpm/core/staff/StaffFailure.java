package com.bpm.core.staff;

import org.springframework.security.core.AuthenticationException;

/** Safe public message; upstream bodies/tokens never enter this exception. */
public class StaffFailure extends AuthenticationException {
    private final String code;
    private final int status;
    public StaffFailure(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }
    public String code() { return code; }
    public int status() { return status; }
    public static StaffFailure unavailable(String service) {
        return new StaffFailure("BPM_" + service + "_UNAVAILABLE", 503, "身分或授權來源目前無法確認，請稍後重試");
    }
    public static StaffFailure denied() {
        return new StaffFailure("BPM_PERMISSION_DENIED", 403, "未取得此操作的權限");
    }
}
