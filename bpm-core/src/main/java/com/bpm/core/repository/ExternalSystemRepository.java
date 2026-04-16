package com.bpm.core.repository;

import com.bpm.core.model.ExternalSystem;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ExternalSystemRepository extends JpaRepository<ExternalSystem, String> {
    Optional<ExternalSystem> findBySystemId(String systemId);
    Optional<ExternalSystem> findBySystemIdAndApiKey(String systemId, String apiKeyHash);
}
