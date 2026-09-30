package com.bpm.core.external;

import com.bpm.core.service.OrgService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.server.ResponseStatusException;

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
    private final ExternalActorGuard guard = new ExternalActorGuard(orgService);

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
        @DisplayName("組織系統查無此人 → 400")
        void unknownPersonIsRejected() {
            when(orgService.getDirectManager("nobody-123")).thenThrow(notFound());

            var e = rejectedBy("nobody-123");

            assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.getReason())
                    .as("訊息必須同時點名 payload 與「組織系統掛掉」兩種可能 —— "
                            + "否則運維看到「查無此人」會去查呼叫端的參數，"
                            + "而真正的故障在基礎設施")
                    .contains(FIELD).contains("nobody-123")
                    .contains("組織系統").contains("fail-closed");
        }

        @Test
        @DisplayName("⚠️ 組織系統不可用（連線逾時）→ 一樣拒絕（fail-closed）")
        void orgSystemUnavailableIsRejectedFailClosed() {
            // 政策：fail-closed。放行等於回到缺陷本身（指派給一個沒有人能
            // 持有的身分）。代價是組織系統掛掉時外部系統發不起流程，
            // 而且回 400（呼叫端通常不會重試）—— 見工項報告，狀態碼語意
            // 屬政策決定，需 PM 裁決。
            when(orgService.getDirectManager(anyString()))
                    .thenThrow(new ResourceAccessException("connect timed out"));

            assertThat(rejectedBy("mgr001").getStatusCode())
                    .as("fail-open 會讓 #88 的缺陷在組織系統故障時原形重現")
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("全形 system： 不被前綴比對命中，但仍然被組織查詢擋下")
        void fullWidthSystemPrefixIsCaughtByOrgLookup() {
            // 這條是「為什麼問組織系統是主要規則、而不只是擋前綴」的直接證據：
            // ExternalActorIdentity.isSystemActor("ｓｙｓｔｅｍ：x") 回 false，
            // 所以只擋前綴的實作會讓這個值通過。而它一樣沒有人能簽。
            when(orgService.getDirectManager("ｓｙｓｔｅｍ：x")).thenThrow(notFound());

            assertThat(rejectedBy("ｓｙｓｔｅｍ：x").getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
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
}
