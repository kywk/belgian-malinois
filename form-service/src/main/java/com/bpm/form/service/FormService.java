package com.bpm.form.service;

import com.bpm.form.model.FormData;
import com.bpm.form.model.FormDefinition;
import com.bpm.form.repository.FormDataRepository;
import com.bpm.form.repository.FormDefinitionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class FormService {

    private final FormDefinitionRepository defRepo;
    private final FormDataRepository dataRepo;

    public FormService(FormDefinitionRepository defRepo, FormDataRepository dataRepo) {
        this.defRepo = defRepo;
        this.dataRepo = dataRepo;
    }

    public FormDefinition create(FormDefinition def) {
        def.setVersion(1);
        def.setStatus("draft");
        return defRepo.save(def);
    }

    public Page<FormDefinition> list(Pageable pageable) {
        return defRepo.findByStatusNotOrderByUpdatedAtDesc("archived", pageable);
    }

    public FormDefinition getSchema(String formKey, Integer version) {
        if (version != null) {
            return defRepo.findByFormKeyAndVersion(formKey, version)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        }
        return defRepo.findLatestPublished(formKey)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    public FormDefinition update(String id, FormDefinition updated) {
        FormDefinition existing = defRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!"draft".equals(existing.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only draft forms can be updated");
        }
        existing.setName(updated.getName());
        existing.setSchemaJson(updated.getSchemaJson());
        return defRepo.save(existing);
    }

    public FormDefinition publish(String id) {
        FormDefinition existing = defRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        // Create a new published version
        FormDefinition published = new FormDefinition();
        published.setFormKey(existing.getFormKey());
        published.setName(existing.getName());
        published.setSchemaJson(existing.getSchemaJson());
        published.setCreatedBy(existing.getCreatedBy());
        published.setVersion(defRepo.findMaxVersion(existing.getFormKey()) + 1);
        published.setStatus("published");
        defRepo.save(published);

        existing.setStatus("published");
        return defRepo.save(existing);
    }

    public FormDefinition archive(String id) {
        FormDefinition existing = defRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!"published".equals(existing.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Only published forms can be archived");
        }
        existing.setStatus("archived");
        return defRepo.save(existing);
    }

    public void delete(String id) {
        FormDefinition existing = defRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if ("published".equals(existing.getStatus()) || "archived".equals(existing.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Published/archived forms cannot be deleted. Archive instead.");
        }
        defRepo.delete(existing);
    }

    // FormData operations
    public FormData submitData(FormData data) {
        return dataRepo.save(data);
    }

    public List<FormData> getDataByProcess(String processInstanceId) {
        return dataRepo.findByProcessInstanceIdOrderBySubmittedAtDesc(processInstanceId);
    }

    public FormData updateData(String id, FormData updated) {
        FormData existing = dataRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        existing.setDataJson(updated.getDataJson());
        return dataRepo.save(existing);
    }
}
