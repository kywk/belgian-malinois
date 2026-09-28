package com.bpm.core.security;

import com.bpm.core.service.BpmPermissionService;
import com.bpm.core.service.OrgService;
import com.bpm.core.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 開發用的組織／權限 fixture 查不到資料時必須拋錯，不能猜（security-audit P2-7）。
 *
 * <h2>為什麼「猜」比「拋錯」危險</h2>
 *
 * <p>改動前不認識的權限碼會回 {@code ["mgr001"]}、不認識的 userId 會被捏造成
 * {@code dept001} 的員工、主管一律回 {@code mgr001}。這些回答直接決定簽核任務派給誰。
 *
 * <p>猜錯的後果不是「失敗」而是<b>「成功但錯誤」</b>：流程順利跑完、任務有人簽、
 * 系統沒有任何異常訊號，只是簽核權責已經錯置。相對地，拋錯會讓問題停在流程啟動那一刻。
 *
 * <h2>這組測試的核心價值：證明 fail-closed 會穿透 service 層</h2>
 *
 * <p>只測 mock 端點回 404 是不夠的 —— 如果 {@code OrgService} 或
 * {@code BpmPermissionService} 把 404 吞掉並回一個預設值，fail-closed 等於白做。
 * 所以這裡<b>透過 service 層</b>驗證例外真的傳上來。
 *
 * <p>（已確認兩個 service 的 try/catch 只包 Redis 快取容錯，不攔 REST 錯誤。
 * 這個測試就是防止日後有人「順手」加一個 catch 把它變回 fail-open。）
 */
class MockFailClosedTest extends IntegrationTestBase {

    @Autowired private MockMvc mockMvc;
    @Autowired private OrgService orgService;
    @Autowired private BpmPermissionService permissionService;

    // ── service 層：fail-closed 必須穿透 ────────────────────────────

    @Test
    @DisplayName("未知權限碼必須拋錯，不能靜默回 [mgr001]")
    void unknownPermissionCodeMustNotResolveToADefaultApprover() {
        // 情境：BPMN 寫錯權限碼，或權限系統還沒建好這個碼。
        // 改動前這裡會回 ["mgr001"]，流程照跑，簽核權責錯置且無訊號。
        assertThatThrownBy(() -> permissionService.getUsersByPermission("typo:does:not:exist"))
                .as("未知權限碼被靜默解析成預設簽核人 —— 這是 P2-7 的核心風險")
                .isInstanceOf(Exception.class);

        // 已知的權限碼必須照常運作，否則上面的斷言只是「全都壞了」。
        assertThat(permissionService.getUsersByPermission("hr:leave:approve"))
                .contains("mgr001", "mgr002", "dir001");
    }

    @Test
    @DisplayName("未知使用者的主管必須拋錯，不能靜默回 mgr001")
    void unknownUserManagerMustNotDefault() {
        // 情境：正式環境的真實員工編號不在開發 fixture 裡。
        assertThatThrownBy(() -> orgService.getDirectManager("E0912345"))
                .isInstanceOf(Exception.class);

        assertThat(orgService.getDirectManager("user001")).isEqualTo("mgr001");
    }

    @Test
    @DisplayName("未知部門的成員必須拋錯，不能靜默回 user001~003")
    void unknownDepartmentMustNotDefault() {
        assertThatThrownBy(() -> orgService.getDeptMembers("dept999"))
                .isInstanceOf(Exception.class);

        assertThat(orgService.getDeptMembers("dept002")).contains("mgr002", "user004", "user005");
    }

    // ── 端點層：語意區分與自我一致 ──────────────────────────────────

    @Test
    @DisplayName("鏈頂人員「無主管」與「使用者不存在」必須是不同的回應")
    void topOfChainIsNotTheSameAsUnknownUser() throws Exception {
        // dir001 沒有主管是事實，不是錯誤 → 200 + 空物件。
        mockMvc.perform(get("/mock/org/api/users/dir001/manager"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.managerId").doesNotExist());

        // 不存在的使用者是錯誤 → 404。
        mockMvc.perform(get("/mock/org/api/users/E0912345/manager"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("getManager 與 getManagerChain 對同一個事實必須一致")
    void managerAndManagerChainMustAgree() throws Exception {
        // 改動前的自我矛盾：getManager("dir001") 回 mgr001（套用預設值），
        // 但 getManagerChain("dir001") 回 []。兩個端點對同一個事實給出不同答案，
        // 而且前者還構成 mgr001 → dir001 → mgr001 的環。
        mockMvc.perform(get("/mock/org/api/users/dir001/manager-chain"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // mgr001 的鏈是 [dir001] 且到此為止 —— 如果 getManager 還在 fail-open，
        // dir001 會再指回 mgr001，鏈就會長成環。
        mockMvc.perform(get("/mock/org/api/users/mgr001/manager-chain?levels=5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0]").value("dir001"));
    }

    @Test
    @DisplayName("判定型端點查不到時回「拒絕」而非 404")
    void predicateEndpointsDenyRatherThanError() throws Exception {
        // 「這個人有沒有權限」對未知使用者的正確答案是 false（拒絕），
        // 那本身就是 fail-closed。不用 404，因為那會把一次乾淨的權限拒絕變成 500。
        mockMvc.perform(get("/mock/perm/api/users/E0912345/has-permission")
                        .param("code", "hr:leave:approve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasPermission").value(false));

        mockMvc.perform(get("/mock/perm/api/users/E0912345/permissions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
