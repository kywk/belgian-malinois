package com.bpm.core.external;

import com.bpm.core.model.ExternalSystem;
import com.bpm.core.repository.ExternalSystemRepository;
import com.bpm.core.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R-21：授權設定的驗證必須在寫入端，而且 400 必須零副作用。
 *
 * <h2>這組測試在防什麼缺陷</h2>
 *
 * <p>改動前 {@code ExternalSystemAdminController} 對四個授權欄位沒有任何格式
 * 或必填驗證：
 * <ul>
 *   <li>{@code allowedProcessKeys} 留空 → 匯入資料庫的 {@code null} → 讀取端
 *       {@code UNRESTRICTED}，<b>照 UI 正常流程建立的系統預設可以啟動任何流程</b>。</li>
 *   <li>非 JSON 的垃圾值 → 讀取端判定 {@code INVALID} → 該系統所有呼叫
 *       靜默全滅，唯一線索是一行 WARN。</li>
 *   <li>打錯字的流程 key → 直到呼叫端拿到 403 才發現（或更糟：空值時根本不會被擋）。</li>
 * </ul>
 *
 * <p>本類別把「寫入端就該擋下」這條界線釘住，並對每個 400 同時驗
 * <b>DB 完全沒變</b>——驗證若排在 mutation 之後，400 回應會伴隨已寫入的
 * 變更（{@code update} 的欄位會被覆寫成 {@code null} ＝放寬授權並 flush）。
 *
 * <h2>⚠️ 刻意區分「空值（不限制）」與「空陣列（拒絕全部）」</h2>
 *
 * <p>{@link #updateDistinguishesEmptyArrayFromAbsentForActions} 是這個區分的
 * 直接證據：{@code allowedActions} 缺席＝不限制，{@code "[]"}＝拒絕全部。
 * 兩者都是合法輸入，但不可互相轉換 —— 把「沒帶」當成「拒絕全部」會鎖死
 * 既有整合，把「[]」當成「沒帶」則是靜默放寬。
 */
class ExternalSystemAuthorizationValidationTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ExternalSystemRepository repo;

    private final List<String> createdSystems = new ArrayList<>();

    @AfterEach
    void cleanup() {
        createdSystems.forEach(sid -> repo.findBySystemId(sid).ifPresent(repo::delete));
        createdSystems.clear();
    }

    private static String uniqueId() {
        return "r21-" + UUID.randomUUID().toString().substring(0, 8);
    }

    // ── fixture／工具 ──────────────────────────────────────────────

    /**
     * 直接寫一筆合法系統作為 update 的前置條件。
     *
     * <p>不用 admin API 建立：本類別要驗的是 update 的驗證，fixture 若也走
     * 同一條驗證路徑，失敗時分不出「fixture 錯」與「待測行為錯」。
     */
    private void given(String systemId) {
        ExternalSystem sys = new ExternalSystem();
        sys.setSystemId(systemId);
        sys.setSystemName("R21 驗證測試系統");
        sys.setApiKey(ApiKeyUtil.hash("r21-key-" + systemId));
        sys.setAllowedProcessKeys("[\"leave-approval\"]");
        sys.setAllowedActions("[\"start_process\"]");
        sys.setEnabled(true);
        sys.setAllowOnBehalfOf(false);
        sys.setCreatedAt(Instant.now());
        repo.save(sys);
        createdSystems.add(systemId);
    }

    private ResultActions create(String json) throws Exception {
        return mockMvc.perform(post("/api/admin/external-systems")
                .header("X-User-Id", "admin001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private ResultActions update(String systemId, String json) throws Exception {
        return mockMvc.perform(put("/api/admin/external-systems/" + systemId)
                .header("X-User-Id", "admin001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    /**
     * 400 的 reason（＝回應 message 的來源）。
     *
     * <p>⚠️ 用 {@code getResolvedException()} 而不是回應 body：MockMvc 不做
     * ERROR dispatch，{@code ResponseStatusExceptionResolver} 走
     * {@code sendError} 之後 body 是空的 —— 對 body 斷言會是一條永遠紅的
     * 斷言。訊息「看得到」那一半由 {@code ErrorMessageDisclosureTest} 用
     * 真實 HTTP 守住；本類別驗的是「400 的 reason 指名哪個欄位」。
     */
    private static String badRequestReason(MvcResult result) {
        Exception ex = result.getResolvedException();
        assertThat(ex)
                .as("400 必須來自刻意丟出的 ResponseStatusException")
                .isInstanceOf(ResponseStatusException.class);
        String reason = ((ResponseStatusException) ex).getReason();
        assertThat(reason).isNotNull();
        return reason;
    }

    /** 一個合法的最小建立 payload；測試各自換掉要驗的那一欄。 */
    private static String createBody(String systemId, String allowedProcessKeys, String allowedActions) {
        return "{\"systemId\":\"" + systemId + "\","
                + "\"systemName\":\"R21 驗證測試系統\","
                + "\"allowedProcessKeys\":" + allowedProcessKeys + ","
                + "\"allowedActions\":" + allowedActions + "}";
    }

    private static String updateBody(String allowedProcessKeys, String allowedActions) {
        return "{\"systemName\":\"R21 驗證測試系統\","
                + "\"allowedProcessKeys\":" + allowedProcessKeys + ","
                + "\"allowedActions\":" + allowedActions + ","
                + "\"enabled\":true}";
    }

    // ── create：格式驗證 ──────────────────────────────────────────

    @Test
    @DisplayName("R-21：create 的每個授權欄位格式錯誤 → 400 指名欄位，且 DB 無任何資料")
    void createRejectsMalformedJsonAndNamesTheField() throws Exception {
        // 四個欄位逐一破壞。allowedProcessKeys 由另一條測試單獨覆蓋必填，
        // 這裡驗的是純格式（值不是 JSON 陣列）。
        record Case(String field, String json) {}
        List<Case> cases = List.of(
                new Case("allowedProcessKeys", "not-json"),
                new Case("allowedActions", "not-json"),
                new Case("allowedCandidateGroups", "not-json"),
                new Case("allowedWorkerTopics", "{不是陣列}"));

        for (Case c : cases) {
            String sid = uniqueId();
            String body = switch (c.field()) {
                case "allowedProcessKeys" -> "{\"systemId\":\"" + sid + "\","
                        + "\"systemName\":\"R21\",\"allowedProcessKeys\":\"" + c.json() + "\"}";
                default -> "{\"systemId\":\"" + sid + "\","
                        + "\"systemName\":\"R21\","
                        + "\"allowedProcessKeys\":\"[\\\"leave-approval\\\"]\","
                        + "\"" + c.field() + "\":\"" + c.json() + "\"}";
            };

            MvcResult result = create(body).andExpect(status().isBadRequest()).andReturn();
            assertThat(badRequestReason(result))
                    .as("400 的訊息必須指名出錯的欄位")
                    .contains(c.field());

            assertThat(repo.findBySystemId(sid))
                    .as("400 必須零副作用：%s 格式錯誤不得建立任何資料列", c.field())
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("R-21：create 缺少／空的 allowedProcessKeys → 400（空值＝不限制，不可由寫入端產生）")
    void createRejectsMissingOrEmptyProcessKeys() throws Exception {
        // 缺席、空字串、空白、空陣列、JSON null —— 全都是「拒絕」，
        // 因為它們在讀取端的語意合起來就是「不限制」或「永遠拒絕」，
        // 兩者都不該是照正常流程建立的新系統的狀態。
        record Case(String processKeysJson, boolean omitted) {}
        List<Case> cases = List.of(
                new Case(null, true),
                new Case("\"\"", false),
                new Case("\"   \"", false),
                new Case("\"[]\"", false),
                new Case("\"null\"", false));

        for (Case c : cases) {
            String sid = uniqueId();
            String body = c.omitted()
                    ? "{\"systemId\":\"" + sid + "\",\"systemName\":\"R21\","
                            + "\"allowedActions\":\"[\\\"start_process\\\"]\"}"
                    : createBody(sid, c.processKeysJson(), "\"[\\\"start_process\\\"]\"");

            MvcResult result = create(body).andExpect(status().isBadRequest()).andReturn();
            assertThat(badRequestReason(result)).contains("allowedProcessKeys");
            assertThat(repo.findBySystemId(sid))
                    .as("400 必須零副作用（輸入：%s）", c.processKeysJson())
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("R-21：create 含未部署的流程 key → 400 指名該 key，DB 無資料")
    void createRejectsUndeployedProcessKey() throws Exception {
        String sid = uniqueId();
        MvcResult result = create(createBody(sid, "\"[\\\"leave-approval\\\",\\\"no-such-process-key\\\"]\"",
                "\"[\\\"start_process\\\"]\""))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertThat(badRequestReason(result))
                .contains("allowedProcessKeys")
                .contains("no-such-process-key");

        assertThat(repo.findBySystemId(sid)).isEmpty();
    }

    @Test
    @DisplayName("R-21：合法設定可建立（驗證不得把正常路徑一起擋掉）")
    void createAcceptsValidConfig() throws Exception {
        String sid = uniqueId();
        create(createBody(sid, "\"[\\\"leave-approval\\\"]\"", "\"[\\\"start_process\\\"]\""))
                .andExpect(status().isOk());

        ExternalSystem saved = repo.findBySystemId(sid).orElseThrow();
        assertThat(saved.getAllowedProcessKeys()).isEqualTo("[\"leave-approval\"]");
        assertThat(saved.getAllowedActions()).isEqualTo("[\"start_process\"]");
    }

    // ── update：驗證必須發生在任何 mutation 之前 ──────────────────

    @Test
    @DisplayName("R-21：update 格式錯誤 → 400，既有授權欄位與名稱一筆都不准被動到")
    void updateRejectsInvalidConfigWithoutSideEffects() throws Exception {
        String sid = uniqueId();
        given(sid);

        // 這筆請求同時帶了一個合法的新系統名稱：如果驗證排在 mutation 之後，
        // 名稱與四個授權欄位會先被套用（PUT 是整欄覆寫，缺的欄位變 null）
        // 才回 400 —— 那正是「400 有副作用」的形狀。
        MvcResult result = update(sid, "{\"systemName\":\"被改壞的名字\","
                + "\"allowedProcessKeys\":\"[\\\"leave-approval\\\"]\","
                + "\"allowedActions\":\"not-json\","
                + "\"enabled\":true}")
                .andExpect(status().isBadRequest())
                .andReturn();
        assertThat(badRequestReason(result)).contains("allowedActions");

        ExternalSystem after = repo.findBySystemId(sid).orElseThrow();
        assertThat(after.getSystemName()).isEqualTo("R21 驗證測試系統");
        assertThat(after.getAllowedProcessKeys()).isEqualTo("[\"leave-approval\"]");
        assertThat(after.getAllowedActions()).isEqualTo("[\"start_process\"]");
    }

    @Test
    @DisplayName("R-21：update 未帶 allowedProcessKeys → 400（PUT 整欄覆寫不可靜默放寬）")
    void updateWithoutProcessKeysIsRejected() throws Exception {
        String sid = uniqueId();
        given(sid);

        MvcResult result = update(sid, "{\"systemName\":\"R21 驗證測試系統\","
                + "\"allowedActions\":\"[\\\"start_process\\\"]\","
                + "\"enabled\":true}")
                .andExpect(status().isBadRequest())
                .andReturn();
        assertThat(badRequestReason(result)).contains("allowedProcessKeys");

        assertThat(repo.findBySystemId(sid).orElseThrow().getAllowedProcessKeys())
                .as("被拒絕的 PUT 不得把白名單清成 null（＝不限制）")
                .isEqualTo("[\"leave-approval\"]");
    }

    @Test
    @DisplayName("R-21：update 含未部署 key → 400 且既有設定原封不動")
    void updateRejectsUndeployedProcessKey() throws Exception {
        String sid = uniqueId();
        given(sid);

        MvcResult result = update(sid, updateBody("\"[\\\"typo-key\\\"]\"", "\"[\\\"start_process\\\"]\""))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertThat(badRequestReason(result)).contains("typo-key");

        assertThat(repo.findBySystemId(sid).orElseThrow().getAllowedProcessKeys())
                .isEqualTo("[\"leave-approval\"]");
    }

    // ── 「未帶欄位」與「空陣列」的語意必須分開 ─────────────────────

    @Test
    @DisplayName("R-21：allowedActions 缺席＝不限制、\"[]\"＝拒絕全部，兩者不可互轉")
    void updateDistinguishesEmptyArrayFromAbsentForActions() throws Exception {
        String sid = uniqueId();
        given(sid);

        // 1. 未帶 allowedActions → null ＝ UNRESTRICTED。
        //    這是既有語意（allowOnBehalfOf 刻意走相反方向），R-21 沒有改它；
        //    本條把它寫下來，避免日後有人「順手」把 null 轉成 []。
        update(sid, "{\"systemName\":\"R21 驗證測試系統\","
                + "\"allowedProcessKeys\":\"[\\\"leave-approval\\\"]\","
                + "\"enabled\":true}")
                .andExpect(status().isOk());
        assertThat(repo.findBySystemId(sid).orElseThrow().getAllowedActions())
                .as("欄位缺席 → null → 不限制")
                .isNull();

        // 2. 明確送 "[]" → 空清單 ＝ DENY_ALL。它是合法設定，不得被當成
        //    null（放寬）也不得被 400 擋掉。
        update(sid, updateBody("\"[\\\"leave-approval\\\"]\"", "\"[]\""))
                .andExpect(status().isOk());
        assertThat(repo.findBySystemId(sid).orElseThrow().getAllowedActions())
                .as("明確的空清單 → 拒絕全部，不是不限制")
                .isEqualTo("[]");
    }
}
