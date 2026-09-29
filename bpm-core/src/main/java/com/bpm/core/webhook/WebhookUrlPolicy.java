package com.bpm.core.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Webhook 目標位址的授權判定（security-audit P2-1 的 SSRF 部分）。
 *
 * <h2>問題</h2>
 *
 * <p>投遞位址來自訊息 payload 的 {@code __webhookUrl}，而改動前<b>沒有 scheme
 * 限制、沒有 allowlist、不阻擋 loopback 與內網</b>。
 *
 * <p>{@code bpm.exchange} 的 binding 是 {@code bpm.webhook.#}，因此任何持有
 * broker 憑證的人都能讓 bpm-core <b>以伺服器身分</b>去打任意內網位址
 * —— 包含 {@code 127.0.0.1}（打自己的 admin API，而那是 R-18：完全無認證）
 * 與 {@code 169.254.169.254}（雲端 metadata 服務，可取得執行角色憑證）。
 * 而 broker 帳密是明文寫在 {@code docker-compose.yml} 裡的。
 *
 * <h2>預設拒絕私有位址</h2>
 *
 * <p>預設只允許 http/https，且拒絕 loopback、link-local、site-local、
 * 以及任播／群播位址。需要打內網端點時用
 * {@code bpm.webhook.allowed-hosts} 明確列出 —— 讓例外成為一個決定，
 * 而不是預設行為。
 *
 * <p>⚠️ 這個檢查在 DNS 解析<b>之後</b>比對實際 IP，因此
 * {@code http://evil.example/} 解析到 {@code 127.0.0.1} 的 DNS rebinding
 * 也會被擋下。但它無法防 TOCTOU（檢查後、連線前 DNS 變更）——
 * 真正要防那個需要自訂 socket factory 並在連線時檢查，不在本次範圍。
 */
@Component
public class WebhookUrlPolicy {

    private static final Logger log = LoggerFactory.getLogger(WebhookUrlPolicy.class);
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private final List<String> allowedHosts;

    public WebhookUrlPolicy(
            @Value("${bpm.webhook.allowed-hosts:}") String allowedHosts) {
        this.allowedHosts = Arrays.stream(allowedHosts.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** @return 不可投遞的理由；{@code null} 代表允許。 */
    public String rejectionReason(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception e) {
            return "URL 格式不合法";
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!ALLOWED_SCHEMES.contains(scheme)) {
            // file://、gopher://、jar:// 等都可能被用來讀本機資源
            return "只允許 http/https，實際為 " + (scheme.isEmpty() ? "(無)" : scheme);
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) return "URL 缺少 host";

        // 明確列出的主機一律允許（內網 webhook 的正當用途）
        if (allowedHosts.contains(host)) return null;

        try {
            // 解析後比對實際 IP —— 這樣 DNS 指向 127.0.0.1 的手法也會被擋。
            for (InetAddress addr : InetAddress.getAllByName(host)) {
                if (addr.isLoopbackAddress()) return "拒絕 loopback 位址: " + addr.getHostAddress();
                if (addr.isLinkLocalAddress()) return "拒絕 link-local 位址: " + addr.getHostAddress()
                        + "（雲端 metadata 服務可取得執行角色憑證）";
                if (addr.isSiteLocalAddress()) return "拒絕內網位址: " + addr.getHostAddress();
                if (addr.isAnyLocalAddress()) return "拒絕任播位址: " + addr.getHostAddress();
                if (addr.isMulticastAddress()) return "拒絕群播位址: " + addr.getHostAddress();
            }
        } catch (Exception e) {
            // 解析不到就不投遞。fail-closed：寧可漏送也不要把伺服器
            // 當成跳板去打不確定的位址。
            return "無法解析 host: " + host;
        }
        return null;
    }

    /** 供 log 使用的允許清單摘要。 */
    public List<String> allowedHosts() {
        return allowedHosts;
    }
}
