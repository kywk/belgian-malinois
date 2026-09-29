package com.bpm.core.repository;

import com.bpm.core.model.DocumentRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DocumentRequestRepository extends JpaRepository<DocumentRequest, String> {

    List<DocumentRequest> findByCreatedByOrderByCreatedAtDesc(String createdBy);

    /**
     * 同前綴的公文筆數。
     *
     * <p>改動前是 {@code LIKE :prefix%} —— JPQL 不允許具名參數後面直接接
     * {@code %}（security-audit P1-11 標為「無法確定」實際行為）。
     * 改用 {@code CONCAT(:prefix, '%')}，語意明確且可攜。
     *
     * <p>⚠️ 這個方法<b>不應該</b>被用來產生序號，見
     * {@link #maxSequenceForPrefix(String)}。保留它是因為「同前綴共有幾筆」
     * 本身是有意義的查詢。
     */
    @Query("SELECT COUNT(d) FROM DocumentRequest d WHERE d.documentNumber LIKE CONCAT(:prefix, '%')")
    int countByPrefix(@Param("prefix") String prefix);

    /**
     * 同前綴中目前最大的序號（沒有則為 0）。
     *
     * <p>用 MAX 而非 COUNT 產生下一個號（security-audit P1-11）：
     * COUNT 的語意是錯的 —— 刪掉一筆舊公文後，下一個號會與既有編號重複。
     *
     * <p>編號格式為 {@code {prefix}-{3 位序號}}，因此取 prefix 長度 + 2
     * 之後的子字串轉整數。
     */
    @Query("""
            SELECT COALESCE(MAX(CAST(SUBSTRING(d.documentNumber, LENGTH(:prefix) + 2, 10) AS integer)), 0)
              FROM DocumentRequest d
             WHERE d.documentNumber LIKE CONCAT(:prefix, '-%')
            """)
    int maxSequenceForPrefix(@Param("prefix") String prefix);
}
