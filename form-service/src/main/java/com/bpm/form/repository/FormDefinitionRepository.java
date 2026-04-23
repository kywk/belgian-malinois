package com.bpm.form.repository;

import com.bpm.form.model.FormDefinition;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface FormDefinitionRepository extends JpaRepository<FormDefinition, String> {

    @Query("SELECT f FROM FormDefinition f WHERE f.formKey = :formKey AND f.status = 'published' ORDER BY f.version DESC LIMIT 1")
    Optional<FormDefinition> findLatestPublished(String formKey);

    Optional<FormDefinition> findByFormKeyAndVersion(String formKey, Integer version);

    Page<FormDefinition> findByStatusNotOrderByUpdatedAtDesc(String status, Pageable pageable);

    Page<FormDefinition> findAllByOrderByUpdatedAtDesc(Pageable pageable);

    @Query("SELECT COALESCE(MAX(f.version), 0) FROM FormDefinition f WHERE f.formKey = :formKey")
    int findMaxVersion(String formKey);
}
