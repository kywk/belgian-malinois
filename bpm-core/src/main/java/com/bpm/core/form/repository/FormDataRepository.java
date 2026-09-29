package com.bpm.core.form.repository;

import com.bpm.core.form.model.FormData;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FormDataRepository extends JpaRepository<FormData, String> {
    List<FormData> findByProcessInstanceIdOrderBySubmittedAtDesc(String processInstanceId);
}
