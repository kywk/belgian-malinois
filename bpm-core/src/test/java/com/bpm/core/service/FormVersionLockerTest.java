package com.bpm.core.service;

import com.bpm.core.form.model.FormDefinition;
import com.bpm.core.form.service.FormService;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FormVersionLocker} 的單元測試：流程啟動時鎖定表單版本。
 *
 * <h2>這組測試在防什麼缺陷</h2>
 *
 * <p>版本鎖定的失敗型態是<b>靜默失效</b>：{@code resolveVersion} 吞掉例外回傳
 * null 時，{@code _formVersions} 完全不寫入，但流程照常啟動成功 ——
 * 該案件此後永遠走「最新版表單」，欄位 id 就是流程變數名（spec §8.5），
 * 所以表單改版可以在案件進行中改變簽核語意，而稽核看不出異狀。
 *
 * <p>因此這裡釘住三件事：哪些 formKey 該鎖、失敗時不得拖垮流程啟動、
 * 以及「同一個 formKey 只解析一次」（重複解析會讓 form-service 故障時
 * 每次啟動付 N 倍 timeout）。
 */
class FormVersionLockerTest {

    private static final String DEF_ID = "leave:1:1";

    private final RepositoryService repositoryService = mock(RepositoryService.class);
    private final RuntimeService runtimeService = mock(RuntimeService.class);
    private final FormService formService = mock(FormService.class);

    private final FormVersionLocker locker =
            new FormVersionLocker(repositoryService, runtimeService, formService);

    private static BpmnModel model(FlowElement... elements) {
        BpmnModel model = new BpmnModel();
        Process process = new Process();
        process.setId("leave");
        for (FlowElement element : elements) process.addFlowElement(element);
        model.addProcess(process);
        return model;
    }

    private static UserTask userTask(String id, String formKey) {
        UserTask task = new UserTask();
        task.setId(id);
        task.setFormKey(formKey);
        return task;
    }

    private static StartEvent startEvent(String id, String formKey) {
        StartEvent event = new StartEvent();
        event.setId(id);
        event.setFormKey(formKey);
        return event;
    }

    private void stubModel(BpmnModel model) {
        when(repositoryService.getBpmnModel(DEF_ID)).thenReturn(model);
    }

    private FormDefinition publishedDefinition(int version) {
        FormDefinition def = mock(FormDefinition.class);
        when(def.getVersion()).thenReturn(version);
        return def;
    }

    private void stubPublished(String formKey, int version) {
        FormDefinition def = publishedDefinition(version);
        when(formService.getSchema(formKey, null)).thenReturn(def);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Integer> capturedVersions() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(runtimeService).setVariable(eq("pid-1"), eq("_formVersions"), captor.capture());
        return (Map<String, Integer>) captor.getValue();
    }

    // ── formKey 收集 ──────────────────────────────────────────────

    @Nested
    @DisplayName("哪些 formKey 要被鎖定")
    class FormKeyCollection {

        @Test
        @DisplayName("UserTask 與 StartEvent 的 formKey 都納入，版本寫進 _formVersions")
        void userTaskAndStartEventAreLocked() {
            stubModel(model(userTask("t1", "leave-form"), startEvent("s1", "start-form")));
            stubPublished("leave-form", 3);
            stubPublished("start-form", 1);

            locker.lockVersions("pid-1", DEF_ID);

            assertThat(capturedVersions())
                    .containsEntry("leave-form", 3)
                    .containsEntry("start-form", 1);
        }

        @Test
        @DisplayName("同一個 formKey 出現在多個節點 → 只解析一次（form-service 故障時不付 N 倍 timeout）")
        void duplicateFormKeysResolvedOnce() {
            stubModel(model(userTask("t1", "leave-form"), userTask("t2", "leave-form")));
            stubPublished("leave-form", 2);

            locker.lockVersions("pid-1", DEF_ID);

            verify(formService, times(1)).getSchema("leave-form", null);
            assertThat(capturedVersions()).containsExactly(java.util.Map.entry("leave-form", 2));
        }

        @Test
        @DisplayName("external: 前綴是外部表單，不解析（不是本系統的表單版本）")
        void externalFormsAreSkipped() {
            stubModel(model(userTask("t1", "external:erp-form")));

            locker.lockVersions("pid-1", DEF_ID);

            verify(formService, never()).getSchema(anyString(), any());
            verify(runtimeService, never()).setVariable(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("含 ${} 的是動態運算式，啟動時無法解析，不鎖定")
        void expressionFormsAreSkipped() {
            stubModel(model(userTask("t1", "${formKeyVar}")));

            locker.lockVersions("pid-1", DEF_ID);

            verify(formService, never()).getSchema(anyString(), any());
        }

        @Test
        @DisplayName("null 與空白 formKey 略過")
        void blankFormKeysAreSkipped() {
            stubModel(model(userTask("t1", null), userTask("t2", "  ")));

            locker.lockVersions("pid-1", DEF_ID);

            verify(formService, never()).getSchema(anyString(), any());
        }

        @Test
        @DisplayName("完全沒有 formKey → 不寫 _formVersions、不查 form-service")
        void noFormKeysWritesNothing() {
            stubModel(model(userTask("t1", null)));

            locker.lockVersions("pid-1", DEF_ID);

            verify(runtimeService, never()).setVariable(anyString(), anyString(), any());
        }
    }

    // ── 失敗處理 ──────────────────────────────────────────────────

    @Nested
    @DisplayName("解析失敗不得拖垮流程啟動，但也不得靜默全空")
    class FailureHandling {

        @Test
        @DisplayName("部分 formKey 沒有已發布版本 → 其餘照樣鎖定，且不拋例外")
        void partialFailureKeepsResolvedVersions() {
            stubModel(model(userTask("t1", "leave-form"), userTask("t2", "missing-form")));
            stubPublished("leave-form", 4);
            when(formService.getSchema("missing-form", null))
                    .thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));

            assertThatCode(() -> locker.lockVersions("pid-1", DEF_ID)).doesNotThrowAnyException();
            assertThat(capturedVersions()).containsExactly(java.util.Map.entry("leave-form", 4));
        }

        @Test
        @DisplayName("失敗的 formKey 重複出現 → 只嘗試一次，不重複發查詢")
        void failingKeyIsAttemptedOnce() {
            stubModel(model(userTask("t1", "missing"), userTask("t2", "missing")));
            when(formService.getSchema("missing", null))
                    .thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));

            locker.lockVersions("pid-1", DEF_ID);

            verify(formService, times(1)).getSchema("missing", null);
            verify(runtimeService, never()).setVariable(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("全部解析失敗 → 不寫 _formVersions（沒有東西可鎖），流程仍啟動")
        void totalFailureWritesNothing() {
            stubModel(model(userTask("t1", "missing")));
            when(formService.getSchema("missing", null))
                    .thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));

            assertThatCode(() -> locker.lockVersions("pid-1", DEF_ID)).doesNotThrowAnyException();
            verify(runtimeService, never()).setVariable(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("getSchema 回 null（查無定義）→ 不放入 map，不得 NPE")
        void nullDefinitionIsNotLocked() {
            stubModel(model(userTask("t1", "missing")));
            when(formService.getSchema("missing", null)).thenReturn(null);

            assertThatCode(() -> locker.lockVersions("pid-1", DEF_ID)).doesNotThrowAnyException();
            verify(runtimeService, never()).setVariable(anyString(), anyString(), any());
        }
    }
}
