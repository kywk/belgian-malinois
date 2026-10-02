package com.bpm.core.controller;

import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.dto.AuditEvent;
import com.bpm.core.external.ExternalActorGuard;
import com.bpm.core.notify.NotifyPublisher;
import com.bpm.core.security.ProcessAccessGuard;
import com.bpm.core.security.TaskHolderGuard;
import com.bpm.core.service.ApplicantResolver;
import com.bpm.core.service.BpmPermissionService;
import com.bpm.core.service.OnBehalfOfLookup;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@code TaskController.urgeTask} 在「系統案件」上的權限中心邊界（#3）。
 *
 * <h2>這裡測的是整合測試碰不到的兩條路</h2>
 *
 * <p>{@code TaskUrgeRevisionHandlerTest} 用真實的 dev fixture 跑得動正向
 * 與「查無受理人」，但<b>權限中心故障</b>沒有辦法從 HTTP 端製造
 * （MockPermController 只會對未知權限碼回 404，而這條路徑的快取與
 * fixture 都是固定值）。所以這一組用真的 {@link ApplicantResolver}
 * 接一個會拋錯的 {@link BpmPermissionService}，直接驗證控制器的轉譯：
 *
 * <ul>
 *   <li>權限中心呼叫失敗（{@code RestClientException}）→ <b>503</b>；</li>
 *   <li>{@code IllegalStateException}（規則說「沒有任何人」）→ 沿用既有
 *       拒絕分流（參與者 403／非參與者 404）；</li>
 *   <li>兩者都<b>不得</b> fail-open：沒有通知、沒有 TASK_URGE、不碰 Redis
 *       （不消耗冷卻）。</li>
 * </ul>
 *
 * <p>TaskController 的其餘協作者全部是 mock：這條路徑在授權判定就返回，
 * 不該碰到任務查詢或通知。用 {@code verifyNoInteractions} 把「不該碰到」
 * 寫成斷言，比只驗狀態碼更能分辨「拒絕了」與「先做了一部分才拒絕」。
 */
class TaskUrgeApplicantResolutionTest {

    private static final String PID = "pid-urge-1";
    private static final String PERM = ApplicantResolver.PERM_EXTERNAL_REVISION;

    private final TaskService taskService = mock(TaskService.class);
    private final RuntimeService runtimeService = mock(RuntimeService.class);
    private final RepositoryService repositoryService = mock(RepositoryService.class);
    private final AuditEventPublisher auditPublisher = mock(AuditEventPublisher.class);
    private final ProcessAccessGuard accessGuard = mock(ProcessAccessGuard.class);
    private final TaskHolderGuard holderGuard = mock(TaskHolderGuard.class);
    private final ExternalActorGuard actorGuard = mock(ExternalActorGuard.class);
    private final OnBehalfOfLookup onBehalfOfLookup = mock(OnBehalfOfLookup.class);
    private final NotifyPublisher notifyPublisher = mock(NotifyPublisher.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final BpmPermissionService permService = mock(BpmPermissionService.class);

    private final TaskController controller = new TaskController(
            taskService, runtimeService, repositoryService, auditPublisher, accessGuard,
            holderGuard, actorGuard, new ApplicantResolver(permService),
            onBehalfOfLookup, notifyPublisher, redis);

    /** 每個測試的起點：一個存在、系統發起、沒有 onBehalfOf 的案件。 */
    @BeforeEach
    void systemCase() {
        when(accessGuard.stateOf(PID)).thenReturn(ProcessAccessGuard.InstanceState.RUNNING);
        when(onBehalfOfLookup.byProcessInstances(List.of(PID))).thenReturn(Map.of());
        when(accessGuard.initiatorOf(PID)).thenReturn("system:erp");
    }

    @Test
    @DisplayName("#3：權限中心查無受理人 → 參與者 403；不碰任務／通知／Redis／TASK_URGE")
    void noHandlerDeniesParticipantWithoutSideEffects() {
        when(permService.getFirstAvailableUser(PERM)).thenReturn(null);
        when(accessGuard.isParticipant(PID, "mgr001")).thenReturn(true);

        ResponseStatusException ex = catchThrowableOfType(
                () -> controller.urgeTask(PID, "mgr001"), ResponseStatusException.class);

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // 拒絕留痕是既有授權行為（DATA_ACCESS publishDetached），
        // TASK_URGE 的 publish 則不得發生。
        verify(auditPublisher).publishDetached(any(AuditEvent.class));
        verify(auditPublisher, never()).publish(any(AuditEvent.class));
        verifyNoInteractions(taskService, notifyPublisher, redis);
    }

    @Test
    @DisplayName("#3：權限中心故障 → 503（fail-closed）；零副作用、零留痕")
    void permissionCentreFailureIsServiceUnavailable() {
        when(permService.getFirstAvailableUser(PERM))
                .thenThrow(new ResourceAccessException("權限中心無回應"));

        ResponseStatusException ex = catchThrowableOfType(
                () -> controller.urgeTask(PID, "dir001"), ResponseStatusException.class);

        assertThat(ex.getStatusCode())
                .as("無法判定時不得 fail-open；503 讓呼叫端知道是暫時性問題")
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verifyNoInteractions(taskService, notifyPublisher, redis, auditPublisher);
    }
}
