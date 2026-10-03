package com.bpm.core.external;

import com.bpm.core.model.ExternalSystem;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ExternalSystemPolicy} 的單元測試。
 *
 * <p>這是外部系統唯一的授權判定點（R-09 / commit {@code f12a8c2}），
 * 而它的輸入是自由文字的資料庫欄位 —— 沒有 schema 驗證、沒有必填約束，
 * admin UI 讓人隨意填。因此「各種奇怪的值會被判成什麼」必須逐一釘死。
 *
 * <p>純邏輯、不需要容器，因此跑得極快 —— 授權規則的迴歸應該由這一層守住，
 * 整合測試只負責確認它真的被串在請求路徑上。
 */
class ExternalSystemPolicyTest {

    private final ExternalSystemPolicy policy = new ExternalSystemPolicy(new ObjectMapper());

    private static ExternalSystem withActions(String allowedActions) {
        ExternalSystem s = new ExternalSystem();
        s.setSystemId("erp");
        s.setAllowedActions(allowedActions);
        return s;
    }

    @Nested
    @DisplayName("精確比對（R-09 的核心）")
    class ExactMatching {

        @Test
        @DisplayName("清單內的 action 放行")
        void allowsListedAction() {
            assertThat(policy.isActionAllowed(
                    withActions("[\"start_process\",\"query_status\"]"), "start_process")).isTrue();
        }

        @Test
        @DisplayName("子串不得誤放行 —— 這正是改動前的漏洞")
        void substringMustNotPass() {
            // 改動前："[\"query_status_extended\"]".contains("query_status") == true
            assertThat(policy.isActionAllowed(
                    withActions("[\"query_status_extended\"]"), "query_status")).isFalse();
            assertThat(policy.isActionAllowed(
                    withActions("[\"start_process_v2\"]"), "start_process")).isFalse();
        }

        @Test
        @DisplayName("反向子串也不得放行")
        void reverseSubstringMustNotPass() {
            assertThat(policy.isActionAllowed(
                    withActions("[\"query\"]"), "query_status")).isFalse();
        }

        @Test
        @DisplayName("大小寫不同不算相符")
        void caseSensitive() {
            assertThat(policy.isActionAllowed(
                    withActions("[\"START_PROCESS\"]"), "start_process")).isFalse();
        }
    }

    @Nested
    @DisplayName("四態語意：不限制／拒絕全部／清單／格式錯誤")
    class FourStates {

        @Test
        @DisplayName("null 與空白視為不限制（維持改動前語意，避免既有整合被鎖死）")
        void blankMeansUnrestricted() {
            assertThat(policy.isActionAllowed(withActions(null), "anything")).isTrue();
            assertThat(policy.isActionAllowed(withActions(""), "anything")).isTrue();
            assertThat(policy.isActionAllowed(withActions("   "), "anything")).isTrue();
        }

        @Test
        @DisplayName("明確的空清單 [] 代表拒絕全部，不是不限制")
        void emptyJsonArrayMeansDenyAll() {
            assertThat(policy.isActionAllowed(withActions("[]"), "start_process")).isFalse();
            assertThat(policy.isActionAllowed(withActions("[ ]"), "start_process")).isFalse();
        }

        @Test
        @DisplayName("格式錯誤一律拒絕（fail-closed）")
        void malformedFailsClosed() {
            assertThat(policy.isActionAllowed(withActions("[not json"), "start_process")).isFalse();
            assertThat(policy.isActionAllowed(withActions("[{\"a\":1}]"), "start_process")).isFalse();
        }

        @Test
        @DisplayName("只有分隔符與空白的垃圾值視為格式錯誤 → 拒絕")
        void garbageIsRejected() {
            assertThat(policy.isActionAllowed(withActions(" , , "), "start_process")).isFalse();
        }
    }

    @Nested
    @DisplayName("非 JSON 的逗號分隔格式（admin UI 可能存進來）")
    class CommaSeparated {

        @Test
        @DisplayName("逗號分隔可用，且每個項目都會 trim")
        void commaSeparatedWithTrim() {
            ExternalSystem s = withActions("start_process, query_status ,complete_task");
            assertThat(policy.isActionAllowed(s, "start_process")).isTrue();
            assertThat(policy.isActionAllowed(s, "query_status")).isTrue();
            assertThat(policy.isActionAllowed(s, "complete_task")).isTrue();
            assertThat(policy.isActionAllowed(s, "other")).isFalse();
        }

        @Test
        @DisplayName("單一值（無逗號）也適用精確比對")
        void singleValue() {
            assertThat(policy.isActionAllowed(withActions("start_process"), "start_process")).isTrue();
            assertThat(policy.isActionAllowed(withActions("start_process"), "start")).isFalse();
        }
    }

    @Nested
    @DisplayName("null 候選值")
    class NullCandidate {

        @Test
        @DisplayName("有清單時，null 候選值不得放行")
        void nullCandidateDeniedAgainstList() {
            assertThat(policy.isActionAllowed(withActions("[\"start_process\"]"), null)).isFalse();
        }

        @Test
        @DisplayName("IP 白名單為空時不限制；有清單時 null IP 不得放行")
        void ipWhitelistBehaviour() {
            ExternalSystem s = new ExternalSystem();
            s.setSystemId("erp");
            s.setIpWhitelist(null);
            assertThat(policy.isIpAllowed(s, "10.0.0.1")).isTrue();

            s.setIpWhitelist("10.0.0.1, 10.0.0.2");
            assertThat(policy.isIpAllowed(s, "10.0.0.1")).isTrue();
            // 改動前集合元素未 trim，第二個項目永遠比不中
            assertThat(policy.isIpAllowed(s, "10.0.0.2")).isTrue();
            assertThat(policy.isIpAllowed(s, "10.0.0.3")).isFalse();
            assertThat(policy.isIpAllowed(s, null)).isFalse();
        }

        @Test
        @DisplayName("白名單有重複 IP 不得拋例外（改動前 Set.of 會 IllegalArgumentException → 500）")
        void duplicateIpDoesNotThrow() {
            ExternalSystem s = new ExternalSystem();
            s.setSystemId("erp");
            s.setIpWhitelist("10.0.0.1, 10.0.0.1");
            assertThat(policy.isIpAllowed(s, "10.0.0.1")).isTrue();
        }
    }

    @Nested
    @DisplayName("allowedProcessKeys")
    class ProcessKeys {

        @Test
        @DisplayName("精確比對，且空值代表不限制（見 R-21：寫入端應改必填）")
        void processKeyMatching() {
            ExternalSystem s = new ExternalSystem();
            s.setSystemId("erp");
            s.setAllowedProcessKeys("[\"leave-approval\"]");
            assertThat(policy.isProcessKeyAllowed(s, "leave-approval")).isTrue();
            assertThat(policy.isProcessKeyAllowed(s, "purchase-approval")).isFalse();
            // 子串
            assertThat(policy.isProcessKeyAllowed(s, "leave")).isFalse();

            s.setAllowedProcessKeys(null);
            assertThat(policy.isProcessKeyAllowed(s, "anything")).isTrue();
        }
    }

    /**
     * {@code allowedCandidateGroups}（#88 政策 B）。
     *
     * <p>守衛層（{@code ExternalActorGuardTest}）已經把它走過一遍；
     * 這裡補的是<b>只有這一層才看得到</b>的形狀：admin UI 自由填寫時
     * 可能存進資料庫的<b>逗號分隔</b>格式（見 {@link #CommaSeparated} 的說明）。
     */
    @Nested
    @DisplayName("allowedCandidateGroups")
    class CandidateGroups {

        private ExternalSystem withGroups(String raw) {
            ExternalSystem s = new ExternalSystem();
            s.setSystemId("erp");
            s.setAllowedCandidateGroups(raw);
            return s;
        }

        @Test
        @DisplayName("逗號分隔格式可用（admin UI 自由填寫會存成這個）")
        void commaSeparatedAdminUiFormat() {
            ExternalSystem s = withGroups("dept001, hr:leave:approve ,dept002");
            assertThat(policy.isCandidateGroupAllowed(s, "dept001")).isTrue();
            assertThat(policy.isCandidateGroupAllowed(s, "hr:leave:approve")).isTrue();
            assertThat(policy.isCandidateGroupAllowed(s, "dept002")).isTrue();
            assertThat(policy.isCandidateGroupAllowed(s, "dept003")).isFalse();
        }

        @Test
        @DisplayName("格式錯誤 → 拒絕全部（fail-closed），不得當成不限制")
        void malformedFailsClosed() {
            // 方向很重要：把壞掉的設定當成「不限制」等於把整個白名單打開，
            // 而管理員的下一個動作通常是「沒生效，再存一次」。
            ExternalSystem s = withGroups("[not json");
            assertThat(policy.isCandidateGroupAllowed(s, "dept001")).isFalse();
        }

        @Test
        @DisplayName("有清單時 null 群組不得放行（空字串群組不是任何人的群組）")
        void nullGroupDeniedAgainstList() {
            assertThat(policy.isCandidateGroupAllowed(withGroups("[\"dept001\"]"), null)).isFalse();
        }
    }

    /**
     * {@code allowedWorkerTopics}（#22 收尾）。
     *
     * <p>整合層（{@code ExternalWorkerTopicWhitelistTest}）已經把它走過一遍；
     * 這裡補的是只有這一層才看得到的形狀：四態語意與 admin UI 自由填寫時
     * 可能存進資料庫的逗號分隔格式。規則本身與其他欄位共用
     * {@code allowed()}，這裡釘的是「worker topic 真的接上了同一份規則」。
     */
    @Nested
    @DisplayName("allowedWorkerTopics")
    class WorkerTopics {

        private ExternalSystem withTopics(String raw) {
            ExternalSystem s = new ExternalSystem();
            s.setSystemId("erp");
            s.setAllowedWorkerTopics(raw);
            return s;
        }

        @Test
        @DisplayName("null 與空白視為不限制（既有系統不需要回填）")
        void blankMeansUnrestricted() {
            assertThat(policy.isWorkerTopicAllowed(withTopics(null), "anything")).isTrue();
            assertThat(policy.isWorkerTopicAllowed(withTopics(""), "anything")).isTrue();
            assertThat(policy.isWorkerTopicAllowed(withTopics("   "), "anything")).isTrue();
        }

        @Test
        @DisplayName("明確的空清單 [] 代表拒絕全部，不是不限制")
        void emptyJsonArrayMeansDenyAll() {
            assertThat(policy.isWorkerTopicAllowed(withTopics("[]"), "demo-topic")).isFalse();
            assertThat(policy.isWorkerTopicAllowed(withTopics("[ ]"), "demo-topic")).isFalse();
        }

        @Test
        @DisplayName("精確比對：逗號分隔可用、每個項目 trim、子串不得誤放行")
        void exactMatching() {
            ExternalSystem s = withTopics("demo-topic, erp-invoices ,hr-sync");
            assertThat(policy.isWorkerTopicAllowed(s, "demo-topic")).isTrue();
            assertThat(policy.isWorkerTopicAllowed(s, "erp-invoices")).isTrue();
            assertThat(policy.isWorkerTopicAllowed(s, "hr-sync")).isTrue();
            assertThat(policy.isWorkerTopicAllowed(s, "demo")).isFalse();
            assertThat(policy.isWorkerTopicAllowed(s, "demo-topic-extended")).isFalse();
        }

        @Test
        @DisplayName("格式錯誤 → 拒絕全部（fail-closed），不得當成不限制")
        void malformedFailsClosed() {
            assertThat(policy.isWorkerTopicAllowed(withTopics("[not json"), "demo-topic")).isFalse();
            assertThat(policy.isWorkerTopicAllowed(withTopics(" , , "), "demo-topic")).isFalse();
        }

        @Test
        @DisplayName("有清單時 null topic 不得放行")
        void nullTopicDeniedAgainstList() {
            assertThat(policy.isWorkerTopicAllowed(withTopics("[\"demo-topic\"]"), null)).isFalse();
        }
    }
}
