package com.bpm.core.form.repository;

import com.bpm.core.form.model.FormData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface FormDataRepository extends JpaRepository<FormData, String> {
    List<FormData> findByProcessInstanceIdOrderBySubmittedAtDesc(String processInstanceId);

    /**
     * 該表單定義被哪些案件使用過（去重，只回 processInstanceId）。
     *
     * <p>archive 的「流程使用中」判定來源（#59）：表單資料是「這份版本
     * 真的被哪個案件用過」的唯一精確證據，而案件是否還在跑由 Flowable
     * runtime 回答（見 {@code FormService.requireNotUsedByRunningProcess}）。
     */
    @Query("SELECT DISTINCT d.processInstanceId FROM FormData d WHERE d.formDefinitionId = :formDefinitionId")
    List<String> findDistinctProcessInstanceIdsByFormDefinitionId(String formDefinitionId);

    /**
     * 該表單定義是否已有任何表單資料（不問案件是否已結束）。
     *
     * <p>delete 的孤兒資料守衛（#59）：刪掉表單定義不會連動刪掉
     * {@code bpm_form_data}（兩者之間沒有 FK），留下的資料列會指向
     * 一個不存在的 formDefinitionId。
     */
    boolean existsByFormDefinitionId(String formDefinitionId);
}
