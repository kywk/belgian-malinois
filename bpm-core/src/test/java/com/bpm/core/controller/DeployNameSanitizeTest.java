package com.bpm.core.controller;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BPMN 部署名稱的清理規則（security-audit P0-1 同類問題）。
 *
 * <p>{@code DeploymentController} 原本直接 {@code bpmnDir.resolve(deployName)}，
 * 而 deployName 來自 {@code @RequestParam} 或 client 的原始檔名 ——
 * {@code name=../../../../app/app.jar} 即可覆寫執行中的 jar。
 *
 * <p>這組測試的另一半用途是<b>確認正常名稱沒有被破壞</b>：
 * {@code scripts/seed-data.sh} 部署的是 {@code leave-approval.bpmn20.xml}，
 * 清理規則若把點或連字號吃掉，seed 與驗收測試會整組壞掉。
 */
class DeployNameSanitizeTest {

    @Test
    @DisplayName("正常的 BPMN 檔名必須原樣保留")
    void normalNamesUnchanged() {
        assertThat(DeploymentController.safeFileName("leave-approval.bpmn20.xml"))
                .isEqualTo("leave-approval.bpmn20.xml");
        assertThat(DeploymentController.safeFileName("purchase-approval.bpmn20.xml"))
                .isEqualTo("purchase-approval.bpmn20.xml");
        assertThat(DeploymentController.safeFileName("leave-approval"))
                .isEqualTo("leave-approval");
    }

    @Test
    @DisplayName("路徑成分一律剝除，不得逃出 bpmnDir")
    void pathComponentsStripped() {
        assertThat(DeploymentController.safeFileName("../../../../app/app.jar"))
                .isEqualTo("app.jar");
        assertThat(DeploymentController.safeFileName("/etc/cron.d/evil"))
                .isEqualTo("evil");
        assertThat(DeploymentController.safeFileName("..\\..\\windows\\system32\\x"))
                .isEqualTo("x");
        assertThat(DeploymentController.safeFileName(".."))
                .as("單純的 .. 必須被換掉，不可原樣回傳")
                .isEqualTo("__");
    }

    @Test
    @DisplayName("編碼變體與特殊字元一律換成底線（白名單制）")
    void encodedVariantsNeutralised() {
        // 黑名單（拒絕 ".."）必然漏掉這些；白名單則一律處理掉。
        assertThat(DeploymentController.safeFileName("%2e%2e%2fapp.jar"))
                .doesNotContain("%").doesNotContain("/");
        assertThat(DeploymentController.safeFileName("a;rm -rf b"))
                .isEqualTo("a_rm_-rf_b");
    }

    @Test
    @DisplayName("空值與空白回退為預設檔名")
    void blankFallsBackToDefault() {
        assertThat(DeploymentController.safeFileName(null)).isEqualTo("process.bpmn20.xml");
        assertThat(DeploymentController.safeFileName("")).isEqualTo("process.bpmn20.xml");
        assertThat(DeploymentController.safeFileName("   ")).isEqualTo("process.bpmn20.xml");
    }
}
