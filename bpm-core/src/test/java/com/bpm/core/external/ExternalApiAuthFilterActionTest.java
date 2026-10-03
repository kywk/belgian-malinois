package com.bpm.core.external;

import com.bpm.core.audit.AuditEventPublisher;
import com.bpm.core.repository.ExternalSystemRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@link ExternalApiAuthFilter#resolveAction} 的單元測試：外部 API 的
 * 「路徑＋方法 → action」對照表。
 *
 * <h2>這組測試在防什麼缺陷</h2>
 *
 * <p>改動前是對整個 URI 做 {@code contains()} 子串比對，且未匹配時
 * <b>預設回傳 {@code "query_status"}</b> —— 未知端點自動取得查詢權限，
 * 而 {@code allowedActions} 是外部系統唯一的動作白名單。
 *
 * <p>現在未知組合一律回 {@code null} 由呼叫端以 403 拒絕（fail-closed）。
 * 這裡把五個端點的每一種「路徑 × 方法」組合逐一釘死，包含：
 * 子路徑不得誤命中、尾斜線正規化、前綴相似的路徑不得被當成
 * {@code /api/external/}。
 */
class ExternalApiAuthFilterActionTest {

    private final ExternalApiAuthFilter filter = new ExternalApiAuthFilter(
            mock(ExternalSystemRepository.class), mock(AuditEventPublisher.class),
            new ObjectMapper(), mock(ExternalSystemAccessGuard.class));

    private static MockHttpServletRequest request(String method, String uri) {
        return new MockHttpServletRequest(method, uri);
    }

    @Nested
    @DisplayName("流程實例端點")
    class ProcessInstances {

        @Test
        @DisplayName("POST /process-instances → start_process；GET → query_status")
        void collectionEndpoints() {
            assertThat(filter.resolveAction(request("POST", "/api/external/process-instances")))
                    .isEqualTo("start_process");
            assertThat(filter.resolveAction(request("GET", "/api/external/process-instances")))
                    .isEqualTo("query_status");
        }

        @Test
        @DisplayName("尾斜線正規化：/process-instances/ 與不帶斜線相同")
        void trailingSlashIsNormalised() {
            assertThat(filter.resolveAction(request("POST", "/api/external/process-instances/")))
                    .isEqualTo("start_process");
        }

        @Test
        @DisplayName("PUT /process-instances 不存在 → null（不得 fallthrough 成 query_status）")
        void unsupportedMethodIsNull() {
            assertThat(filter.resolveAction(request("PUT", "/api/external/process-instances")))
                    .isNull();
        }

        @Test
        @DisplayName("GET /process-instances/{id}/status → query_status")
        void statusEndpoint() {
            assertThat(filter.resolveAction(
                    request("GET", "/api/external/process-instances/pid-1/status")))
                    .isEqualTo("query_status");
        }

        @Test
        @DisplayName("POST 到 status、或 status 後面還有更深路徑 → null")
        void statusWrongShapeIsNull() {
            assertThat(filter.resolveAction(
                    request("POST", "/api/external/process-instances/pid-1/status")))
                    .isNull();
            assertThat(filter.resolveAction(
                    request("GET", "/api/external/process-instances/pid-1/status/extra")))
                    .isNull();
        }
    }

    @Nested
    @DisplayName("任務完成端點")
    class Tasks {

        @Test
        @DisplayName("PUT /tasks/{taskId} → complete_task")
        void completeTask() {
            assertThat(filter.resolveAction(request("PUT", "/api/external/tasks/task-1")))
                    .isEqualTo("complete_task");
        }

        @Test
        @DisplayName("GET 或更深層路徑（/tasks/{id}/children）→ null，子路徑不得誤命中")
        void wrongMethodOrDeeperPathIsNull() {
            assertThat(filter.resolveAction(request("GET", "/api/external/tasks/task-1"))).isNull();
            assertThat(filter.resolveAction(request("PUT", "/api/external/tasks/task-1/children")))
                    .isNull();
        }
    }

    @Nested
    @DisplayName("流程變數規格端點")
    class VariableSpec {

        @Test
        @DisplayName("GET /process-definitions/{key}/variable-spec → query_status")
        void variableSpec() {
            assertThat(filter.resolveAction(
                    request("GET", "/api/external/process-definitions/leave/variable-spec")))
                    .isEqualTo("query_status");
        }

        @Test
        @DisplayName("POST 同一路徑 → null")
        void wrongMethodIsNull() {
            assertThat(filter.resolveAction(
                    request("POST", "/api/external/process-definitions/leave/variable-spec")))
                    .isNull();
        }
    }

    @Nested
    @DisplayName("External Worker 端點（#22）：共用一個 external_worker action")
    class Worker {

        @Test
        @DisplayName("acquire 與 tasks 列表都對應 external_worker")
        void acquireAndList() {
            assertThat(filter.resolveAction(request("POST", "/api/external/worker/tasks/acquire")))
                    .isEqualTo("external_worker");
            assertThat(filter.resolveAction(request("GET", "/api/external/worker/tasks")))
                    .isEqualTo("external_worker");
        }

        @Test
        @DisplayName("complete／fail／unacquire 三個動作都對應同一個 external_worker")
        void workCycleSharesOneAction() {
            assertThat(filter.resolveAction(
                    request("POST", "/api/external/worker/tasks/job-1/complete")))
                    .isEqualTo("external_worker");
            assertThat(filter.resolveAction(
                    request("POST", "/api/external/worker/tasks/job-1/fail")))
                    .isEqualTo("external_worker");
            assertThat(filter.resolveAction(
                    request("POST", "/api/external/worker/tasks/job-1/unacquire")))
                    .isEqualTo("external_worker");
        }

        @Test
        @DisplayName("未知 worker 子路徑或錯誤方法 → null（fail-closed）")
        void unknownWorkerShapesAreNull() {
            assertThat(filter.resolveAction(
                    request("POST", "/api/external/worker/tasks/job-1/delete"))).isNull();
            assertThat(filter.resolveAction(
                    request("GET", "/api/external/worker/tasks/job-1/complete"))).isNull();
        }
    }

    @Nested
    @DisplayName("未知路徑與過濾範圍")
    class UnknownAndScope {

        @Test
        @DisplayName("未知路徑一律 null —— 改動前未匹配會變成 query_status，等於未知端點自動取得查詢權")
        void unknownPathIsNull() {
            assertThat(filter.resolveAction(request("GET", "/api/external/unknown"))).isNull();
            assertThat(filter.resolveAction(request("DELETE", "/api/external/process-instances"))).isNull();
        }

        @Test
        @DisplayName("前綴相似的路徑（/api/externalx）不得被當成外部 API")
        void prefixLookalikeIsNull() {
            assertThat(filter.resolveAction(request("GET", "/api/externalx/process-instances"))).isNull();
        }

        @Test
        @DisplayName("shouldNotFilter：/api/external 與其子路徑要過濾，其他路徑不過濾")
        void filterScope() {
            assertThat(filter.shouldNotFilter(request("GET", "/api/external/process-instances")))
                    .isFalse();
            assertThat(filter.shouldNotFilter(request("GET", "/api/external")))
                    .as("不帶尾斜線的裸前綴也要涵蓋，避免 prefix 檢查的破口")
                    .isFalse();
            assertThat(filter.shouldNotFilter(request("GET", "/api/tasks"))).isTrue();
            assertThat(filter.shouldNotFilter(request("GET", "/api/externalx"))).isTrue();
        }
    }
}
