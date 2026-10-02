package com.bpm.core.audit.repository;

import com.bpm.core.audit.model.AuditLog;
import com.bpm.core.audit.model.OperationType;
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

    /** 幂等檢查用（security-audit P1-14）。 */
    boolean existsByEventId(String eventId);

    /**
     * 列表與 CSV 匯出共用的查詢（#40）。匯出逐頁呼叫它，篩選規則只有這一份。
     *
     * <p>⚠️ {@code ORDER BY} 的 {@code a.id DESC} 不是裝飾：只依
     * {@code createdAt} 排序時，同一時間戳的多筆記錄在兩次分頁查詢之間
     * 沒有穩定順序 —— MSSQL 的 {@code OFFSET/FETCH} 可能讓某筆同時出現在
     * 兩頁（重複匯出）或兩頁都不出現（漏掉）。批次事件（同一毫秒寫入多筆）
     * 正好會踩到。加 id 作為決勝鍵後排序全序，分頁才可重現。
     * 列表端點同樣受益，兩者維持同一個順序。
     */
    @Query("SELECT a FROM AuditLog a WHERE " +
            "(:processInstanceId IS NULL OR a.processInstanceId = :processInstanceId) AND " +
            "(:operatorId IS NULL OR a.operatorId = :operatorId) AND " +
            "(:operationType IS NULL OR a.operationType = :operationType) AND " +
            "(:startDate IS NULL OR a.createdAt >= :startDate) AND " +
            "(:endDate IS NULL OR a.createdAt <= :endDate) " +
            "ORDER BY a.createdAt DESC, a.id DESC")
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
