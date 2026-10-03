package com.bpm.core.service;

/**
 * BPMN 部署的 Git commit 失敗（#61）。
 *
 * <p>獨立型別讓 {@code DeploymentController} 能把「版控寫不進去」轉成 503
 * 且<b>不</b>呼叫 Flowable deploy（fail-closed），而不必逐一 catch JGit 的
 * 各種例外型別 —— 那些型別是實作細節，不該出現在 controller 的 import 裡。
 */
public class DeploymentGitException extends RuntimeException {

    public DeploymentGitException(String message) {
        super(message);
    }

    public DeploymentGitException(String message, Throwable cause) {
        super(message, cause);
    }
}
