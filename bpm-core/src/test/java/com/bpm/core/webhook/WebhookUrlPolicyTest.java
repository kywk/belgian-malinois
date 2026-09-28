package com.bpm.core.webhook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Webhook 目標位址的 SSRF 防護（security-audit P2-1）。
 *
 * <p>投遞位址來自訊息 payload，而 {@code bpm.exchange} 的 binding 是
 * {@code bpm.webhook.#} —— 任何持有 broker 憑證的人（帳密明文寫在
 * docker-compose.yml）都能讓 bpm-core <b>以伺服器身分</b>去打任意內網位址。
 *
 * <p>最具體的兩個目標：{@code 127.0.0.1} 打自己的 {@code /api/admin/**}
 * （那是 R-18：完全無認證），以及 {@code 169.254.169.254} 雲端 metadata
 * 服務（可取得執行角色憑證）。
 */
class WebhookUrlPolicyTest {

    private final WebhookUrlPolicy policy = new WebhookUrlPolicy("");

    @Nested
    @DisplayName("scheme 限制")
    class Schemes {
        @Test
        @DisplayName("非 http/https 的 scheme 一律拒絕，且理由明確指出 scheme")
        void onlyHttpSchemes() {
            // file:// 可讀本機檔案，gopher:// 曾被用來打其他協定
            for (String url : new String[]{
                    "file:///etc/passwd", "gopher://evil/x", "jar:file:///x!/y", "ftp://x/y"}) {
                assertThat(policy.rejectionReason(url))
                        .as(url + " 必須被拒絕")
                        .isNotNull()
                        .contains("http/https");
            }
        }

        @Test
        @DisplayName("http/https 不得因 scheme 被拒（用允許清單隔離 DNS 依賴）")
        void httpSchemesPassSchemeCheck() {
            // ⚠️ 刻意不用真實可解析的網域做正向斷言：單元測試依賴外部 DNS
            // 會在離線或 CI 沙箱中變成偶發失敗。
            // 這裡用 allowedHosts 讓它跳過 IP 檢查，單獨驗證 scheme 這一關。
            var p = new WebhookUrlPolicy("webhook.example.com");
            assertThat(p.rejectionReason("https://webhook.example.com/hook")).isNull();
            assertThat(p.rejectionReason("http://webhook.example.com/hook")).isNull();
        }

        @Test
        @DisplayName("無法解析的 host 一律拒絕（fail-closed）")
        void unresolvableHostRejected() {
            // 寧可漏送，也不要把伺服器當成跳板去打不確定的位址。
            assertThat(policy.rejectionReason("http://no-such-host.invalid/hook"))
                    .isNotNull()
                    .contains("無法解析");
        }

        @Test
        @DisplayName("格式不合法或缺少 host 一律拒絕")
        void malformedRejected() {
            assertThat(policy.rejectionReason("not a url")).isNotNull();
            assertThat(policy.rejectionReason("http://")).isNotNull();
            assertThat(policy.rejectionReason("")).isNotNull();
        }
    }

    @Nested
    @DisplayName("內網與特殊位址")
    class PrivateAddresses {
        @Test
        @DisplayName("拒絕 loopback —— 那會打到自己完全無認證的 admin API")
        void rejectsLoopback() {
            assertThat(policy.rejectionReason("http://127.0.0.1:8080/api/admin/external-systems"))
                    .isNotNull();
            assertThat(policy.rejectionReason("http://localhost:8080/api/admin/")).isNotNull();
            assertThat(policy.rejectionReason("http://[::1]:8080/x")).isNotNull();
        }

        @Test
        @DisplayName("拒絕 link-local —— 雲端 metadata 可取得執行角色憑證")
        void rejectsLinkLocal() {
            assertThat(policy.rejectionReason("http://169.254.169.254/latest/meta-data/"))
                    .isNotNull();
        }

        @Test
        @DisplayName("拒絕內網位址")
        void rejectsSiteLocal() {
            assertThat(policy.rejectionReason("http://10.0.0.5/hook")).isNotNull();
            assertThat(policy.rejectionReason("http://192.168.1.10/hook")).isNotNull();
            assertThat(policy.rejectionReason("http://172.16.0.1/hook")).isNotNull();
        }

        @Test
        @DisplayName("拒絕 0.0.0.0 與群播")
        void rejectsAnyAndMulticast() {
            assertThat(policy.rejectionReason("http://0.0.0.0/x")).isNotNull();
            assertThat(policy.rejectionReason("http://224.0.0.1/x")).isNotNull();
        }
    }

    @Nested
    @DisplayName("明確允許清單")
    class AllowList {
        @Test
        @DisplayName("列出的主機可以是內網位址（內網 webhook 的正當用途）")
        void allowedHostBypassesPrivateCheck() {
            var p = new WebhookUrlPolicy("internal-erp.corp,127.0.0.1");
            assertThat(p.rejectionReason("http://127.0.0.1:9000/hook"))
                    .as("明確列出後應允許 —— 讓例外是一個決定，而非預設行為")
                    .isNull();
        }

        @Test
        @DisplayName("未列出的主機仍受限制")
        void unlistedStillBlocked() {
            var p = new WebhookUrlPolicy("internal-erp.corp");
            assertThat(p.rejectionReason("http://10.0.0.5/hook")).isNotNull();
        }

        @Test
        @DisplayName("允許清單為空字串時不得誤放行任何主機")
        void emptyAllowListIsNotWildcard() {
            var p = new WebhookUrlPolicy("  ,  ,  ");
            assertThat(p.allowedHosts()).isEmpty();
            assertThat(p.rejectionReason("http://127.0.0.1/x")).isNotNull();
        }
    }
}
