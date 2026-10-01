package com.bpm.core.external;

import com.bpm.core.model.ExternalSystem;
import com.bpm.core.service.OrgService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ExternalActorGuard} 的單元測試（#88）。
 *
 * <p>純邏輯、不需要容器，所以跑得極快。整合層
 * （{@code ExternalFirstTaskAssigneeTest}）只負責確認這條規則真的被串在
 * 請求路徑上，以及狀態碼在真實 HTTP 下長什麼樣子。
 *
 * <p>分層的理由與 {@link ExternalSystemPolicyTest} 相同：規則的<b>分支窮舉</b>
 * 由這一層守住（哪些形狀被擋、哪些放行、每個形狀有沒有打網路），
 * 整合層的成本不該花在重複同一張表上。
 *
 * <h2>⚠️ 每一條拒絕都配一條「同樣參數、只有那個值不同」的放行對照</h2>
 *
 * <p>沒有對照組的話，一個「擋掉所有非 null 值」的實作能讓所有拒絕測試全綠 ——
 * 而那個實作會讓所有正常發起都回 400。對照組在這裡不只是數量問題，
 * 有兩條特別關鍵：
 * <ul>
 *   <li>{@link Passes#chainTopPersonPasses}：{@code dir001} 沒有主管，
 *       {@code getDirectManager} 回 {@code null}。<b>把回傳值當存在性判斷</b>
 *       的實作（{@code if (getDirectManager(x) == null) reject}）會擋掉總監本人
 *       —— 而「答案恰好等於預期值」正是這個 repo 踩過最多次的坑。</li>
 *   <li>{@link Passes#nullMeansUnspecified}：沒指名是合法的（改用候選群組）。
 *       一個「null 也拒絕」或「null 也去打網路」的實作會讓只給候選群組的
 *       合法請求整個壞掉。</li>
 * </ul>
 *
 * <h2>⚠️ 2026-09-30：故障與拒絕分成兩組測試（政策裁決）</h2>
 *
 * <p>{@link OrgLookup#unknownPersonIsRejected}（404 → <b>400</b>）與
 * {@link OrgSystemFailure}（逾時／5xx／401 → <b>503</b>）是<b>兩組分開的測試</b>，
 * 不是同一條測試裡的兩個分支斷言。理由：一個「不論什麼都回 400」的實作可以
 * 讓「查無此人」那組變綠，必須有一組獨立的測試證明<b>故障不會走成 400</b>。
 *
 * <h2>為什麼斷言「有沒有打網路」而不只是狀態碼</h2>
 *
 * <p>{@code system:} 前綴與空白這兩層存在的理由之一就是<b>不打網路</b>
 * （見類別註解「便宜的先做」）。而且這是規則分層的可觀測指標：
 * 有人日後把順序倒過來（先打網路再比前綴），狀態碼完全一樣，
 * 只有這個斷言會紅。
 */
class ExternalActorGuardTest {

    private static final String FIELD = "firstTaskAssignee";

    private final OrgService orgService = mock(OrgService.class);
    private final ExternalSystemPolicy policy = new ExternalSystemPolicy(new ObjectMapper());
    private final ExternalActorGuard guard = new ExternalActorGuard(orgService, policy);

    /** 拒絕時取回例外，讓每個測試可以同時斷言狀態碼與訊息。 */
    private ResponseStatusException rejectedBy(String userId) {
        try {
            guard.requireKnownPerson(FIELD, userId);
        } catch (ResponseStatusException e) {
            return e;
        }
        throw new AssertionError("預期被拒絕，但沒有拋例外: [" + userId + "]");
    }

    /** fail-closed 的 mock 對「fixture 裡沒有的人」丟的就是這個 404。 */
    private static HttpClientErrorException notFound() {
        return HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", null, null, null);
    }

    @Nested
    @DisplayName("第一層：server 鑄造的系統身分（不打網路）")
    class SystemIdentity {

        @Test
        @DisplayName("system:<id> → 400，且不得查組織系統")
        void systemPrefixIsRejectedWithoutOrgLookup() {
            var e = rejectedBy("system:evil");

            assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.getReason())
                    .as("訊息必須指名欄位與成因，呼叫端才知道要改什麼")
                    .contains(FIELD).contains("system:evil").contains("系統身分");
            verifyNoInteractions(orgService);
        }

        @Test
        @DisplayName("大小寫不同同樣拒絕（外部系統可以送 SYSTEM:x）")
        void systemPrefixIsCaseInsensitive() {
            // 只比對小寫的實作會留下這條繞道，而後果是 #83 的靜默卡死。
            for (String v : new String[]{"SYSTEM:evil", "System:evil", "sYsTeM:evil"}) {
                assertThat(rejectedBy(v).getStatusCode())
                        .as("大小寫不同的系統身分必須同樣被擋: " + v)
                        .isEqualTo(HttpStatus.BAD_REQUEST);
            }
            verifyNoInteractions(orgService);
        }
    }

    @Nested
    @DisplayName("第二層：空白（不打網路）")
    class Blank {

        @Test
        @DisplayName("空字串與全空白 → 400")
        void blankIsRejected() {
            for (String v : new String[]{"", " ", "   ", "\t", "\n"}) {
                assertThat(rejectedBy(v).getStatusCode())
                        .as("空白不得被當成「未指定」: [" + v + "]")
                        .isEqualTo(HttpStatus.BAD_REQUEST);
            }
            verifyNoInteractions(orgService);
        }

        @Test
        @DisplayName("前後有空白 → 不得靜默 trim，要讓呼叫端看到它送錯了")
        void paddedValueIsNotSilentlyTrimmed() {
            // " mgr001 " 對組織系統來說是另一個人。靜默 trim 會讓呼叫端
            // 永遠不知道自己送出了錯誤的 id，而存進 Flowable 的 assignee
            // 也會與它送的不同 —— 錯誤被藏起來比被拒絕更糟。
            when(orgService.getDirectManager(" mgr001 ")).thenThrow(notFound());

            assertThat(rejectedBy(" mgr001 ").getReason())
                    .contains(" mgr001 ")
                    .as("訊息必須原樣回顯它送的值，呼叫端才知道要去比對哪裡");
        }
    }

    @Nested
    @DisplayName("第三層：其餘一律問組織系統（擋掉所有組織系統不認識的字串）")
    class OrgLookup {

        @Test
        @DisplayName("組織系統查無此人（404）→ 400，呼叫端該改 payload")
        void unknownPersonIsRejected() {
            when(orgService.getDirectManager("nobody-123")).thenThrow(notFound());

            var e = rejectedBy("nobody-123");

            assertThat(e.getStatusCode())
                    .as("404 是組織系統明確的拒絕 → payload 的問題")
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.getReason())
                    .as("訊息必須指向『換一個 id』，而且不得暗示這是暫時性問題 —— "
                            + "否則呼叫端會一直重試一個永遠不會成功的請求")
                    .contains(FIELD).contains("nobody-123")
                    .contains("不是組織系統認識的人員")
                    .doesNotContain("請稍後以相同的參數重試");
        }

        @Test
        @DisplayName("全形 system： 不被前綴比對命中，但仍然被組織查詢擋下")
        void fullWidthSystemPrefixIsCaughtByOrgLookup() {
            // 這條是「為什麼問組織系統是主要規則、而不只是擋前綴」的直接證據：
            // ExternalActorIdentity.isSystemActor("ｓｙｓｔｅｍ：x") 回 false，
            // 所以只擋前綴的實作會讓這個值通過。而它一樣沒有人能簽。
            when(orgService.getDirectManager("ｓｙｓｔｅｍ：x")).thenThrow(notFound());

            assertThat(rejectedBy("ｓｙｓｔｅｍ：x").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 組織系統<b>故障</b>（不是拒絕）—— 2026-09-30 使用者裁決：回 503。
     *
     * <p>與 {@link OrgLookup} 分開成獨立的測試組的理由見類別註解。
     */
    @Nested
    @DisplayName("第三層之bis：組織系統故障 → 503（暫時性，呼叫端該重試）")
    class OrgSystemFailure {

        /** 每一種故障形狀都必須是 503，且訊息必須說明「重試安全」。 */
        private void assertServiceUnavailable(Exception failure, String what) {
            // ⚠️ 用 doThrow(...).when(...) 而不是 when(...).thenThrow(...)：
            // 一條測試裡連續驗兩種形狀時，第二次 when() 會實際呼叫那個
            // mock 方法，而前一次 stub 的例外會在 stubbing 階段就丟出來。
            // （doThrow 不會呼叫被 stub 的方法。）
            org.mockito.Mockito.doThrow(failure).when(orgService).getDirectManager(anyString());

            var e = rejectedBy("mgr001");

            assertThat(e.getStatusCode())
                    .as("組織系統「故障」必須 503：「查無此人」是 400，兩者不可混為一談。"
                            + "回 400 會讓批次不重試，組織恢復後要人工重跑 —— " + what)
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(e.getReason())
                    .as("503 的訊息必須說明這是暫時性問題、且重試不會產生重複案件；"
                            + "呼叫端只看到狀態碼時，無從判斷該不該重試 —— " + what)
                    .contains("組織系統目前無法查詢")
                    .contains("請稍後以相同的參數重試")
                    // ⚠️ #93a：這裡**刻意不再**斷「未建立任何流程實例」。
                    // 該措辭只對外部 API 那一個呼叫點成立，而本方法已有五個
                    // （reassign／delegate／countersign 都不發起流程）——
                    // 斷言一個對四個呼叫點是假的句子，只會把訊息鎖死在
                    // 對多數呼叫端說謊的版本上。改成動作中立的「未做任何變更」，
                    // 它承載的意圖（重試安全）一字未減。
                    .contains("本次請求未做任何變更")
                    .contains("重試不會產生重複案件");
        }

        @Test
        @DisplayName("連線逾時 / 無法連線 → 503")
        void timeoutOrConnectionRefusedIs503() {
            // ResourceAccessException 是 RestClient 對「連不上」與「讀逾時」的封裝，
            // 這是「無法連線」唯一能被精確產生的形狀，所以放在單元層驗。
            assertServiceUnavailable(new ResourceAccessException("connect timed out"), "逾時");
            assertServiceUnavailable(new ResourceAccessException("Connection refused"), "拒線");
        }

        @Test
        @DisplayName("組織系統自己 5xx → 503")
        void upstreamServerErrorIs503() {
            assertServiceUnavailable(
                    HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR,
                            "boom", null, null, null), "5xx");
            assertServiceUnavailable(
                    HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE,
                            "down for maintenance", null, null, null), "5xx 503");
        }

        @Test
        @DisplayName("⚠️ 組織系統回 401／403 → 503，不是 400")
        void upstreamAuthErrorIsNotAPersonRejection() {
            // 401／403 描述的是「我們沒有被允許查」（憑證或權限設定錯了），
            // 不是「這個人不存在」。回 400 會讓呼叫端去改一個根本不是
            // 問題來源的欄位，而真正的故障在基礎設施。
            //
            // ⚠️ 這條是「預設方向是把不確定當故障」的直接證據：
            // 一個「所有 4xx 都算查無此人」的實作（聽起來很直覺）
            // 會讓這條紅 —— 而症狀是批次大量回 400、沒有人查基礎設施。
            assertServiceUnavailable(
                    HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized",
                            null, null, null), "401");
            assertServiceUnavailable(
                    HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden",
                            null, null, null), "403");
        }

        @Test
        @DisplayName("⚠️ 故障仍然是 fail-closed：不放行")
        void failureNeverFailsOpen() {
            // 裁決改的是「要不要重試」，不是「要不要拒絕」。
            // 放行等於回到缺陷本身（指派給一個沒有人能持有的身分），
            // 而那正是 #88 要修的東西 —— 只是換成在組織系統掛掉時發生。
            when(orgService.getDirectManager("mgr001"))
                    .thenThrow(new ResourceAccessException("down"));

            assertThat(rejectedBy("mgr001").getStatusCode())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    @Nested
    @DisplayName("對照組：合法值必須放行（缺了它，「擋掉全部」的實作會全綠）")
    class Passes {

        @Test
        @DisplayName("組織系統認識的人 → 放行")
        void knownPersonPasses() {
            when(orgService.getDirectManager("mgr001")).thenReturn("dir001");

            assertThatCode(() -> guard.requireKnownPerson(FIELD, "mgr001"))
                    .as("最常見的正常路徑不得被擋").doesNotThrowAnyException();
        }

        @Test
        @DisplayName("⚠️ 位於組織鏈頂、沒有主管的人 → 仍然放行")
        void chainTopPersonPasses() {
            // getDirectManager 回 null 代表「他存在但沒有主管」，
            // 不是「查不到」。把回傳值當存在性判斷的實作會擋掉 dir001。
            when(orgService.getDirectManager("dir001")).thenReturn(null);

            assertThatCode(() -> guard.requireKnownPerson(FIELD, "dir001"))
                    .as("dir001 沒有主管是事實，不是查不到 —— 絕不可因此拒絕")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("沒有指名（null）→ 放行，且不得打網路")
        void nullMeansUnspecified() {
            assertThatCode(() -> guard.requireKnownPerson(FIELD, null))
                    .as("沒指名是合法的（改用候選群組），由呼叫端自己的規則處理")
                    .doesNotThrowAnyException();
            verifyNoInteractions(orgService);
        }
    }

    /**
     * 候選群組白名單（#88 政策 B）。
     *
     * <h2>⚠️ 這裡最容易寫出一個「擋掉全部群組」的實作</h2>
     *
     * <p>白名單是一個「包含關係」，而空值還有「不限制」的語意 ——
     * 一個寫成 {@code groups.isEmpty() || !allowed.contains(g)} 或
     * {@code !allowed.contains(g)} 配上一個預設空清單的實作，
     * 會讓<b>所有</b>候選群組請求全被擋掉，而除了下面幾條測試沒有人會發現。
     * 所以 {@link CandidateGroups#nullWhitelistMeansUnrestricted} 與
     * {@link CandidateGroups#listedGroupsPass} 與 {@link Passes} 同級，不是多餘的。
     */
    @Nested
    @DisplayName("候選群組白名單（#88 政策 B）")
    class CandidateGroups {

        private ExternalSystem system(String whitelist) {
            ExternalSystem sys = new ExternalSystem();
            sys.setSystemId("erp");
            sys.setAllowedCandidateGroups(whitelist);
            return sys;
        }

        /**
         * 相容形狀的切分，與 {@code ExternalApiController.parseCandidateGroups}
         * 對逗號分隔字串做的事相同。
         *
         * <p>⚠️ 這裡<b>刻意</b>保留一份切分：它不是「規則的第二份」，
         * 形狀解析只有 controller 那一份（見 #93 的 javadoc）。這個 helper
         * 只是讓下面那些以逗號分隔字串表達的測試維持可讀 —— 守衛本身
         * <b>不再</b>切分，所以它收到什麼就是什麼（元素未 trim）。
         */
        private static List<String> csv(String raw) {
            return raw == null ? null : List.of(raw.split(",", -1));
        }

        private List<String> accepted(String whitelist, List<String> raw) {
            return guard.requireAllowedCandidateGroups(system(whitelist), raw);
        }

        private ResponseStatusException groupRejected(String whitelist, List<String> raw) {
            try {
                accepted(whitelist, raw);
            } catch (ResponseStatusException e) {
                return e;
            }
            throw new AssertionError("預期被拒絕，但沒有拋例外: " + raw);
        }

        @Test
        @DisplayName("白名單內的群組必須放行（對照組：不得「擋掉全部群組」）")
        void listedGroupsPass() {
            assertThat(accepted("[\"dept001\",\"hr:leave:approve\"]", csv("dept001,hr:leave:approve")))
                    .containsExactly("dept001", "hr:leave:approve");
            // 權限碼形狀是本專案自己的 BPMN 會產生的（ExternalSystemPolicy 的說明），
            // 一個「假設每個群組都是部門」的實作會擋掉它。
            assertThat(accepted("[\"hr:leave:approve\"]", csv("hr:leave:approve")))
                    .containsExactly("hr:leave:approve");
        }

        @Test
        @DisplayName("⚠️ 白名單為 null／空白 → 不限制（與 allowedProcessKeys 同一條規則）")
        void nullWhitelistMeansUnrestricted() {
            // ⚠️ 這條同時是「既有資料為什麼不需要回填」的答案：
            // migration 之後既有系統的這個欄位一律是 null，而它必須照常工作。
            for (String whitelist : new String[]{null, "", "   "}) {
                assertThat(accepted(whitelist, csv("dept001,hr:leave:approve,anything")))
                        .as("白名單留空代表不限制: [" + whitelist + "]")
                        .containsExactly("dept001", "hr:leave:approve", "anything");
            }
        }

        @Test
        @DisplayName("明確的空清單 [] → 拒絕全部（合法設定，不是「不限制」）")
        void emptyJsonArrayDeniesAll() {
            assertThat(groupRejected("[]", csv("dept001")).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("白名單外的群組 → 403，且指名是哪一個群組")
        void unlistedGroupIsForbidden() {
            var e = groupRejected("[\"dept001\"]", csv("dept001,hr:leave:approve"));

            assertThat(e.getStatusCode())
                    .as("白名單是授權維度 → 403，與 allowedProcessKeys 的 403 同類")
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(e.getReason())
                    .as("必須指名那個未授權的群組，並指出授權是在哪裡設定的")
                    .contains("hr:leave:approve").contains("allowedCandidateGroups");
        }

        @Test
        @DisplayName("子串不得誤放行（沿用 R-09 的精確比對）")
        void substringMustNotPass() {
            // 與 isProcessKeyAllowed 同一個坑：集合比對不是子串比對。
            assertThat(groupRejected("[\"hr:leave:approve\"]", csv("hr:leave")).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(groupRejected("[\"dept001\"]", csv("dept0011")).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("空白項目被丟棄（不算未授權的群組）")
        void blankEntriesAreDropped() {
            // 見 requireAllowedCandidateGroups 的 javadoc：丟棄不是放寬
            // （沒有人是空字串群組的成員），但它讓白名單的錯誤訊息不會
            // 指名一個「看得見但看不懂」的群組名。
            assertThat(accepted("[\"dept001\"]", csv(" dept001 , , ")))
                    .containsExactly("dept001");
            // 全部都是空白 → 沒有群組。呼叫端的「至少有一個」規則會看到這一點。
            assertThat(accepted("[\"dept001\"]", csv(" , "))).isEmpty();
        }

        @Test
        @DisplayName("null（沒指定群組）→ 空清單且不打網路")
        void nullRawMeansNoGroups() {
            assertThat(accepted("[\"dept001\"]", null)).isEmpty();
            verifyNoInteractions(orgService);
        }

        @Test
        @DisplayName("⚠️ 必須回傳同一份清單給呼叫端去寫 identity link")
        void returnsTheSameListItValidates() {
            // 驗證與套用若各自 split 一次，就是同一條規則兩套形狀 ——
            // 「驗證了 3 個群組、實際寫了 4 個」這種 bug 不會有任何錯誤。
            // 所以這裡斷言「回傳的清單就是被驗過的那一份」：
            // 空白被丟棄、重複被去重、順序保留。
            assertThat(accepted("[\"dept001\",\"dept002\"]", csv("dept002, dept001 ,dept002")))
                    .as("去重且保留書寫順序")
                    .containsExactly("dept002", "dept001");
        }
    }
}
