package com.bpm.core.controller;

import com.bpm.core.security.ProcessAccessGuard;
import java.util.List;
import java.util.Map;
import org.flowable.bpmn.model.*;
import org.flowable.common.engine.api.FlowableObjectNotFoundException;
import org.flowable.engine.*;
import org.flowable.engine.runtime.ProcessInstance;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CompletedTaskDiagramTest {
    @Test void finishingTheLastTaskWhileRenderingTheDiagramReturnsTheClosedView() throws Exception {
        var runtime = mock(RuntimeService.class, RETURNS_DEEP_STUBS);
        var definitions = mock(RepositoryService.class);
        var instance = mock(ProcessInstance.class);
        when(runtime.createProcessInstanceQuery().processInstanceId("instance").singleResult()).thenReturn(instance);
        when(instance.getProcessDefinitionId()).thenReturn("definition");
        var model = new BpmnModel();
        var process = new org.flowable.bpmn.model.Process();
        process.setId("diagram");
        var end = new EndEvent();
        end.setId("end");
        process.addFlowElement(end);
        model.addProcess(process);
        model.addGraphicInfo("end", new GraphicInfo());
        when(definitions.getBpmnModel("definition")).thenReturn(model);
        when(runtime.getActiveActivityIds("instance")).thenThrow(new FlowableObjectNotFoundException("completed"));
        var controller = new ProcessController(runtime, definitions, null, null, null, null,
                mock(ProcessAccessGuard.class), null, null, null, null, null, null);
        assertThat(controller.getBpmnXml("instance", "reviewer")).isEqualTo(Map.of("xml", "", "activeIds", List.of()));
    }
}
