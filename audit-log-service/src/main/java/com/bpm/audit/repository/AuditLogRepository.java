package com.bpm.audit.repository;

import com.bpm.audit.model.AuditLog;
import com.bpm.audit.model.OperationType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    @Query("SELECT a FROM AuditLog a ORDER BY a.id DESC LIMIT 1")
    Optional<AuditLog> findLastRecord();

    @Query("SELECT a FROM AuditLog a WHERE " +
            "(:processInstanceId IS NULL OR a.processInstanceId = :processInstanceId) AND " +
            "(:operatorId IS NULL OR a.operatorId = :operatorId) AND " +
            "(:operationType IS NULL OR a.operationType = :operationType) AND " +
            "(:startDate IS NULL OR a.createdAt >= :startDate) AND " +
            "(:endDate IS NULL OR a.createdAt <= :endDate) " +
            "ORDER BY a.createdAt DESC")
    Page<AuditLog> search(
            @Param("processInstanceId") String processInstanceId,
            @Param("operatorId") String operatorId,
            @Param("operationType") OperationType operationType,
            @Param("startDate") Instant startDate,
            @Param("endDate") Instant endDate,
            Pageable pageable);

    @Query("SELECT a FROM AuditLog a WHERE a.createdAt >= :startDate AND a.createdAt <= :endDate ORDER BY a.id ASC")
    List<AuditLog> findByDateRange(@Param("startDate") Instant startDate, @Param("endDate") Instant endDate);
}
