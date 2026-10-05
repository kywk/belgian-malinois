package com.bpm.core.repository;

import com.bpm.core.model.ExternalSystem;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ExternalSystemRepository extends JpaRepository<ExternalSystem, String> {
    Optional<ExternalSystem> findBySystemId(String systemId);
    // ⚠️ 刻意沒有 findBySystemIdAndApiKey（R-25）：API key 的驗證必須走
    // ApiKeyHasher 的雙讀邏輯（v2 HMAC／legacy SHA-256），而金鑰查詢用
    // DB 等值比對只能支援其中一種格式。留著這個方法等於留一條
    // 「繞過 v2 驗證」的路。
}
