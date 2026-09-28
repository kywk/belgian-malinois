package com.bpm.core.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * 啟動時驗證組織／權限系統的 URL 設定，設錯就不讓它起來。
 *
 * <h2>這個檢查要防止的部署事故（security-audit P2-7）</h2>
 *
 * <p>改動前的狀態：
 * <ul>
 *   <li>{@code MockOrgController} / {@code MockPermController} <b>沒有任何 profile 限制</b>，
 *       任何環境都會註冊。</li>
 *   <li>{@code bpm.external.org-service-url} 預設指向 {@code http://localhost:8080/mock/org}，
 *       <b>沒有任何 profile 或 compose 檔覆蓋它</b>。</li>
 *   <li>三個 compose 檔（base／dev／prod）都用 {@code SPRING_PROFILES_ACTIVE: docker}
 *       —— profile 完全不區分 dev 與 prod。</li>
 * </ul>
 *
 * <p>三者相乘的後果：正式環境的組織架構與權限判定，全部來自一份寫死在程式碼裡的
 * 9 人開發 fixture。而且沒有任何東西會報錯 —— 服務健康、流程跑得動、
 * 簽核任務都派得出去，只是派給的是 {@code mgr001}。
 *
 * <h2>為什麼要「啟動失敗」而不是記一條警告</h2>
 *
 * <p>組織與權限是授權的事實來源。設錯的授權來源不該啟動成功 ——
 * 它能提供服務，就代表它正在做出錯誤的授權決定，而且看起來一切正常。
 * 這種錯誤越晚發現越貴：資料已經按錯誤的權責簽核完成，事後無法只靠重設設定修正。
 *
 * <p>啟動失敗的代價是部署當下就中斷，訊息明確、根因直接。這是刻意選擇的取捨：
 * 寧可讓部署失敗，也不要讓一個授權錯誤的系統上線。
 */
@Configuration
public class ExternalSystemUrlValidator {

    private static final Logger log = LoggerFactory.getLogger(ExternalSystemUrlValidator.class);

    private final boolean mockEnabled;
    private final String orgUrl;
    private final String permUrl;

    public ExternalSystemUrlValidator(
            @Value("${bpm.external.mock-enabled:false}") boolean mockEnabled,
            @Value("${bpm.external.org-service-url:}") String orgUrl,
            @Value("${bpm.external.perm-service-url:}") String permUrl) {
        this.mockEnabled = mockEnabled;
        this.orgUrl = orgUrl;
        this.permUrl = permUrl;
    }

    @PostConstruct
    void validate() {
        check("bpm.external.org-service-url", orgUrl);
        check("bpm.external.perm-service-url", permUrl);

        if (mockEnabled) {
            // 就算是 dev，也要在啟動日誌留下明顯痕跡。
            // 萬一哪天有人把 mock-enabled 帶上正式環境，這幾行是唯一的線索。
            log.warn("╔══════════════════════════════════════════════════════════════╗");
            log.warn("║ 組織／權限資料來自開發用 fixture（bpm.external.mock-enabled=true）║");
            log.warn("║ 授權決定不可信，僅限開發與測試環境。                              ║");
            log.warn("╚══════════════════════════════════════════════════════════════╝");
        }
    }

    private void check(String property, String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalStateException(
                    "%s 未設定。組織／權限系統的位置沒有合理的預設值 —— 猜錯會造成錯誤的授權決定，所以這裡不提供預設值。"
                            .formatted(property));
        }

        boolean pointsAtMock = url.contains("/mock/");

        if (pointsAtMock && !mockEnabled) {
            throw new IllegalStateException(
                    ("%s 指向 mock 端點（%s），但 bpm.external.mock-enabled=false —— "
                     + "mock controller 不會註冊，這個 URL 在執行期只會拿到 404，"
                     + "而錯誤會出現在使用者送出簽核的時候，不是現在。"
                     + "請改設為真實組織／權限系統的位置。")
                            .formatted(property, url));
        }

        if (!pointsAtMock && mockEnabled) {
            throw new IllegalStateException(
                    ("bpm.external.mock-enabled=true 但 %s 指向外部系統（%s）。"
                     + "這兩個設定互相矛盾，無法判斷意圖 —— 要用 mock 就讓 URL 指向 /mock/，"
                     + "要接真實系統就把 mock-enabled 設為 false。")
                            .formatted(property, url));
        }

        if (!mockEnabled && (url.contains("localhost") || url.contains("127.0.0.1"))) {
            // 不擋：sidecar 或本機 proxy 是合理架構。但正式環境指向 localhost
            // 多半是設定漏改，值得在啟動日誌留一筆。
            log.warn("{} 指向本機（{}）。若非 sidecar／本機 proxy 架構，這很可能是設定漏改。",
                    property, url);
        }
    }
}
