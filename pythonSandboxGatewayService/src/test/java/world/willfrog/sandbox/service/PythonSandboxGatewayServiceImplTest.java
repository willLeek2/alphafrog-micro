package world.willfrog.sandbox.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import world.willfrog.alphafrogmicro.sandbox.idl.ExecuteRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskResultResponse;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PythonSandboxGatewayServiceImplTest {

    @Test
    void canonicalCreateComponentsShouldKeepFrozenProtoNumbers() {
        assertEquals(19, ExecuteRequest.CANONICALSPECSCHEMAVERSION_FIELD_NUMBER);
        assertEquals(20, ExecuteRequest.CODEHASH_FIELD_NUMBER);
        assertEquals(21, ExecuteRequest.IMMUTABLEDATASETSNAPSHOTDIGEST_FIELD_NUMBER);
        assertEquals(22, ExecuteRequest.LIBRARIESDIGEST_FIELD_NUMBER);
        assertEquals(23, ExecuteRequest.SANDBOXOPTIONSDIGEST_FIELD_NUMBER);

        ExecuteRequest request = ExecuteRequest.newBuilder()
                .setCanonicalSpecSchemaVersion("sandbox_create_v1")
                .setCodeHash("sha256:" + "a".repeat(64))
                .setImmutableDatasetSnapshotDigest("sha256:" + "b".repeat(64))
                .setLibrariesDigest("sha256:" + "c".repeat(64))
                .setSandboxOptionsDigest("sha256:" + "d".repeat(64))
                .build();

        assertEquals("sandbox_create_v1", request.getCanonicalSpecSchemaVersion());
        assertEquals("sha256:" + "a".repeat(64), request.getCodeHash());
        assertEquals("sha256:" + "b".repeat(64), request.getImmutableDatasetSnapshotDigest());
        assertEquals("sha256:" + "c".repeat(64), request.getLibrariesDigest());
        assertEquals("sha256:" + "d".repeat(64), request.getSandboxOptionsDigest());
    }

    @Test
    void terminalRetryableShouldKeepFrozenProtoNumberAndPresence() {
        assertEquals(9, TaskResultResponse.RETRYABLE_FIELD_NUMBER);

        TaskResultResponse missing = TaskResultResponse.newBuilder().build();
        TaskResultResponse explicitFalse = TaskResultResponse.newBuilder().setRetryable(false).build();

        assertFalse(missing.hasRetryable());
        assertTrue(explicitFalse.hasRetryable());
        assertFalse(explicitFalse.getRetryable());
    }

    @Test
    void getTaskResultShouldBridgeRetryableAndMeasuredZeroUsageFromHttpJson() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway =
                new PythonSandboxGatewayServiceImpl(restTemplate, new ObjectMapper());
        ReflectionTestUtils.setField(gateway, "sandboxUrl", "http://sandbox");

        server.expect(once(), requestTo("http://sandbox/tasks/task-1"))
                .andRespond(withSuccess(
                        "{\"task_id\":\"task-1\",\"status\":\"SUCCEEDED\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("http://sandbox/tasks/task-1/result"))
                .andRespond(withSuccess("""
                        {
                          "exit_code": 0,
                          "stdout": "ok",
                          "stderr": "",
                          "dataset_dir": "/tmp/ds",
                          "retryable": false,
                          "resource_usage": {
                            "resource_class": "STANDARD",
                            "cpu_millis": 0,
                            "logical_bytes_scanned": 0,
                            "dataset_open_count": 0,
                            "exit_reason": "SUCCEEDED",
                            "oom_killed": false,
                            "timed_out": false,
                            "attribution_complete": true,
                            "missing_fields": []
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        TaskResultResponse response = gateway.getTaskResult(
                world.willfrog.alphafrogmicro.sandbox.idl.GetTaskResultRequest.newBuilder()
                        .setTaskId("task-1").build());

        server.verify();
        assertTrue(response.hasRetryable());
        assertFalse(response.getRetryable());
        assertTrue(response.hasResourceUsage());
        assertTrue(response.getResourceUsage().hasCpuMillis());
        assertEquals(0L, response.getResourceUsage().getCpuMillis());
        assertTrue(response.getResourceUsage().hasLogicalBytesScanned());
        assertEquals(0L, response.getResourceUsage().getLogicalBytesScanned());
        assertTrue(response.getResourceUsage().hasDatasetOpenCount());
        assertEquals(0, response.getResourceUsage().getDatasetOpenCount());
        assertTrue(response.getResourceUsage().getAttributionComplete());
    }

    @Test
    void getTaskResultShouldPreserveMissingRetryableAndPartialUsage() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway =
                new PythonSandboxGatewayServiceImpl(restTemplate, new ObjectMapper());
        ReflectionTestUtils.setField(gateway, "sandboxUrl", "http://sandbox");

        server.expect(once(), requestTo("http://sandbox/tasks/task-2"))
                .andRespond(withSuccess(
                        "{\"task_id\":\"task-2\",\"status\":\"FAILED\",\"error\":\"boom\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("http://sandbox/tasks/task-2/result"))
                .andRespond(withSuccess("""
                        {
                          "exit_code": -1,
                          "stdout": "",
                          "stderr": "boom",
                          "dataset_dir": "/tmp/ds",
                          "resource_usage": {
                            "resource_class": "STANDARD",
                            "logical_bytes_scanned": 0,
                            "dataset_open_count": 0,
                            "exit_reason": "EXECUTION_ERROR",
                            "attribution_complete": false,
                            "missing_fields": ["cpuMillis", "memoryPeakBytes"]
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        TaskResultResponse response = gateway.getTaskResult(
                world.willfrog.alphafrogmicro.sandbox.idl.GetTaskResultRequest.newBuilder()
                        .setTaskId("task-2").build());

        server.verify();
        assertFalse(response.hasRetryable());
        assertEquals("FAILED", response.getStatus());
        assertEquals("boom", response.getError());
        assertTrue(response.hasResourceUsage());
        assertFalse(response.getResourceUsage().getAttributionComplete());
        assertEquals(java.util.List.of("cpuMillis", "memoryPeakBytes"),
                response.getResourceUsage().getMissingFieldsList());
        assertTrue(response.getResourceUsage().hasLogicalBytesScanned());
        assertEquals(0L, response.getResourceUsage().getLogicalBytesScanned());
    }

    @Test
    void getTaskResultShouldBridgeExplicitRetryableTrueForFailedResult() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway =
                new PythonSandboxGatewayServiceImpl(restTemplate, new ObjectMapper());
        ReflectionTestUtils.setField(gateway, "sandboxUrl", "http://sandbox");

        server.expect(once(), requestTo("http://sandbox/tasks/task-oom"))
                .andRespond(withSuccess(
                        "{\"task_id\":\"task-oom\",\"status\":\"FAILED\",\"error\":\"oom\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("http://sandbox/tasks/task-oom/result"))
                .andRespond(withSuccess("""
                        {
                          "exit_code": -1,
                          "stderr": "oom",
                          "retryable": true,
                          "resource_usage": {
                            "resource_class": "STANDARD",
                            "exit_reason": "OOM_KILLED",
                            "oom_killed": true,
                            "timed_out": false,
                            "attribution_complete": false,
                            "missing_fields": ["cpuMillis"]
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        TaskResultResponse response = gateway.getTaskResult(
                world.willfrog.alphafrogmicro.sandbox.idl.GetTaskResultRequest.newBuilder()
                        .setTaskId("task-oom").build());

        server.verify();
        assertTrue(response.hasRetryable());
        assertTrue(response.getRetryable());
        assertTrue(response.hasResourceUsage());
        assertTrue(response.getResourceUsage().getOomKilled());
        assertEquals("OOM_KILLED", response.getResourceUsage().getExitReason());
    }

    @Test
    void getTaskResultShouldFetchCanceledTerminalClassification() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway =
                new PythonSandboxGatewayServiceImpl(restTemplate, new ObjectMapper());
        ReflectionTestUtils.setField(gateway, "sandboxUrl", "http://sandbox");

        server.expect(once(), requestTo("http://sandbox/tasks/task-cancel"))
                .andRespond(withSuccess(
                        "{\"task_id\":\"task-cancel\",\"status\":\"CANCELED\",\"error\":\"user canceled\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("http://sandbox/tasks/task-cancel/result"))
                .andRespond(withSuccess("""
                        {
                          "exit_code": -1,
                          "stderr": "user canceled",
                          "retryable": false
                        }
                        """, MediaType.APPLICATION_JSON));

        TaskResultResponse response = gateway.getTaskResult(
                world.willfrog.alphafrogmicro.sandbox.idl.GetTaskResultRequest.newBuilder()
                        .setTaskId("task-cancel").build());

        server.verify();
        assertEquals("CANCELED", response.getStatus());
        assertTrue(response.hasRetryable());
        assertFalse(response.getRetryable());
    }

    @Test
    void extractTimingFieldsShouldExposeSandboxPhaseTimings() {
        PythonSandboxGatewayServiceImpl.HttpExecuteResult result =
                new PythonSandboxGatewayServiceImpl.HttpExecuteResult();
        result.setArtifacts(Map.of(
                "timings", Map.of(
                        "env_load_ms", 11,
                        "code_exec_ms", 22,
                        "artifact_collect_ms", 3,
                        "workspace_prepare_ms", 10,
                        "script_run_ms", 20,
                        "workspace_cleanup_ms", 4,
                        "total_runner_ms", 40
                )
        ));

        Map<String, Object> fields = PythonSandboxGatewayServiceImpl.extractTimingFields(result);

        assertEquals(11, fields.get("env_load_ms"));
        assertEquals(22, fields.get("code_exec_ms"));
        assertEquals(3, fields.get("artifact_collect_ms"));
        assertEquals(10, fields.get("workspace_prepare_ms"));
        assertEquals(20, fields.get("script_run_ms"));
        assertEquals(4, fields.get("workspace_cleanup_ms"));
        assertEquals(40, fields.get("total_runner_ms"));
    }

    @Test
    void extractTimingFieldsShouldFallbackToLegacyRunnerTimingNames() {
        PythonSandboxGatewayServiceImpl.HttpExecuteResult result =
                new PythonSandboxGatewayServiceImpl.HttpExecuteResult();
        result.setArtifacts(Map.of(
                "timings", Map.of(
                        "workspace_prepare_ms", "10",
                        "script_run_ms", "20",
                        "workspace_cleanup_ms", "4"
                )
        ));

        Map<String, Object> fields = PythonSandboxGatewayServiceImpl.extractTimingFields(result);

        assertEquals(10L, fields.get("env_load_ms"));
        assertEquals(20L, fields.get("code_exec_ms"));
        assertEquals(4L, fields.get("artifact_collect_ms"));
    }
}
