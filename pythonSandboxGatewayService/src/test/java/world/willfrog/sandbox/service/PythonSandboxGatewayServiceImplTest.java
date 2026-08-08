package world.willfrog.sandbox.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import world.willfrog.alphafrogmicro.sandbox.idl.ExecuteRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.ExecuteResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.FinanceRecordChannelMetadata;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskByOperationIdRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskByOperationIdResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.SandboxEnvironmentIdentity;
import world.willfrog.alphafrogmicro.sandbox.idl.SandboxPackageApi;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskResultResponse;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class PythonSandboxGatewayServiceImplTest {

    @Test
    void createTaskShouldForwardCanonicalContractAndBridgeIdempotencyResponse() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway =
                new PythonSandboxGatewayServiceImpl(restTemplate, new ObjectMapper());
        ReflectionTestUtils.setField(gateway, "sandboxUrl", "http://sandbox");

        server.expect(once(), requestTo("http://sandbox/tasks"))
                .andExpect(content().json("""
                        {
                          "resource_class":"HEAVY",
                          "estimated_rows":6000,
                          "estimated_bytes":500000,
                          "file_count":2,
                          "capacity_units":3,
                          "operation_id":"run-1:call-1:1",
                          "request_fingerprint":"sha256:request",
                          "memory_limit_bytes":1073741824,
                          "timeout_millis":30000,
                          "runtime_environment_version":"python-v1",
                          "canonical_spec_schema_version":"sandbox_create_v1",
                          "code_hash":"sha256:code",
                          "immutable_dataset_snapshot_digest":"sha256:dataset",
                          "libraries_digest":"sha256:libraries",
                          "sandbox_options_digest":"sha256:options"
                        }
                        """, false))
                .andRespond(withSuccess("""
                        {
                          "task_id":"task-existing",
                          "status":"RUNNING",
                          "existing":true,
                          "request_fingerprint":"sha256:request"
                        }
                        """, MediaType.APPLICATION_JSON));

        ExecuteResponse response = gateway.createTask(ExecuteRequest.newBuilder()
                .setCode("print(1)")
                .setResourceClass("HEAVY")
                .setEstimatedRows(6000)
                .setEstimatedBytes(500000)
                .setFileCount(2)
                .setCapacityUnits(3)
                .setOperationId("run-1:call-1:1")
                .setRequestFingerprint("sha256:request")
                .setMemoryLimitBytes(1073741824L)
                .setTimeoutMillis(30000)
                .setRuntimeEnvironmentVersion("python-v1")
                .setCanonicalSpecSchemaVersion("sandbox_create_v1")
                .setCodeHash("sha256:code")
                .setImmutableDatasetSnapshotDigest("sha256:dataset")
                .setLibrariesDigest("sha256:libraries")
                .setSandboxOptionsDigest("sha256:options")
                .build());

        server.verify();
        assertEquals("task-existing", response.getTaskId());
        assertTrue(response.getExisting());
        assertEquals("sha256:request", response.getRequestFingerprint());
    }

    @Test
    void getTaskByOperationIdShouldBridgeFoundTask() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway =
                new PythonSandboxGatewayServiceImpl(restTemplate, new ObjectMapper());
        ReflectionTestUtils.setField(gateway, "sandboxUrl", "http://sandbox");

        server.expect(once(), requestTo("http://sandbox/operations/run-1:call-1:1"))
                .andRespond(withSuccess("""
                        {
                          "found":true,
                          "task_id":"task-1",
                          "status":"SUCCEEDED",
                          "request_fingerprint":"sha256:request"
                        }
                        """, MediaType.APPLICATION_JSON));

        GetTaskByOperationIdResponse response = gateway.getTaskByOperationId(
                GetTaskByOperationIdRequest.newBuilder()
                        .setOperationId("run-1:call-1:1")
                        .build());

        server.verify();
        assertTrue(response.getFound());
        assertEquals("task-1", response.getTaskId());
        assertEquals("SUCCEEDED", response.getStatus());
        assertEquals("sha256:request", response.getRequestFingerprint());
    }

    @Test
    void getTaskByOperationIdShouldEncodeSinglePathSegment() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway =
                new PythonSandboxGatewayServiceImpl(restTemplate, new ObjectMapper());
        ReflectionTestUtils.setField(gateway, "sandboxUrl", "http://sandbox");

        server.expect(once(), requestTo("http://sandbox/operations/run%2F%E4%B8%AD%E6%96%87%20id"))
                .andRespond(withSuccess("{\"found\":false}", MediaType.APPLICATION_JSON));

        GetTaskByOperationIdResponse response = gateway.getTaskByOperationId(
                GetTaskByOperationIdRequest.newBuilder()
                        .setOperationId("run/中文 id")
                        .build());

        server.verify();
        assertFalse(response.getFound());
        assertTrue(response.getError().isBlank());
    }

    @Test
    void getTaskByOperationIdShouldExposeTransportFailureAsErrorNotDefinitiveAbsence() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway =
                new PythonSandboxGatewayServiceImpl(restTemplate, new ObjectMapper());
        ReflectionTestUtils.setField(gateway, "sandboxUrl", "http://sandbox");

        server.expect(once(), requestTo("http://sandbox/operations/run-1:call-1:1"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        GetTaskByOperationIdResponse response = gateway.getTaskByOperationId(
                GetTaskByOperationIdRequest.newBuilder()
                        .setOperationId("run-1:call-1:1")
                        .build());

        server.verify();
        assertFalse(response.getFound());
        assertFalse(response.getError().isBlank());
    }

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

    // 260808-finance-methodspec-v5 work package D: parent absence on HTTP must
    // translate to no parent set on proto. Downstream consumers then see
    // hasFinanceRecordChannel() == false and hasExecutionEnvironment() == false,
    // which is the v5 signal for "old producer, do not interpret defaults".
    @Test
    void getTaskResultShouldDistinguishOldProducerByParentAbsence() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway =
                new PythonSandboxGatewayServiceImpl(restTemplate, new ObjectMapper());
        ReflectionTestUtils.setField(gateway, "sandboxUrl", "http://sandbox");

        server.expect(once(), requestTo("http://sandbox/tasks/task-old"))
                .andRespond(withSuccess(
                        "{\"task_id\":\"task-old\",\"status\":\"SUCCEEDED\"}",
                        MediaType.APPLICATION_JSON));
        // HTTP body without finance_record_channel and execution_environment keys.
        server.expect(once(), requestTo("http://sandbox/tasks/task-old/result"))
                .andRespond(withSuccess("""
                        {
                          "exit_code": 0,
                          "stdout": "ok",
                          "stderr": "",
                          "dataset_dir": "/tmp/ds",
                          "retryable": false,
                          "resource_usage": {
                            "resource_class": "STANDARD",
                            "attribution_complete": true
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        TaskResultResponse response = gateway.getTaskResult(
                world.willfrog.alphafrogmicro.sandbox.idl.GetTaskResultRequest.newBuilder()
                        .setTaskId("task-old").build());

        server.verify();
        assertFalse(response.hasFinanceRecordChannel(),
                "parent absence on HTTP must NOT set FinanceRecordChannel on proto");
        assertFalse(response.hasExecutionEnvironment(),
                "parent absence on HTTP must NOT set ExecutionEnvironment on proto");
    }

    // 260808-finance-methodspec-v5 work package D: both parents present on HTTP
    // must surface on proto with all mapped fields. packageApis list must be
    // carried through as a repeated SandboxPackageApi snapshot, with no
    // runtimeImageRef leaking from the Python-internal layer.
    @Test
    void getTaskResultShouldBridgeFinanceRecordChannelAndExecutionEnvironment() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway =
                new PythonSandboxGatewayServiceImpl(restTemplate, new ObjectMapper());
        ReflectionTestUtils.setField(gateway, "sandboxUrl", "http://sandbox");

        server.expect(once(), requestTo("http://sandbox/tasks/task-v5"))
                .andRespond(withSuccess(
                        "{\"task_id\":\"task-v5\",\"status\":\"SUCCEEDED\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("http://sandbox/tasks/task-v5/result"))
                .andRespond(withSuccess("""
                        {
                          "exit_code": 0,
                          "stdout": "ok",
                          "stderr": "",
                          "dataset_dir": "/tmp/ds",
                          "retryable": false,
                          "resource_usage": {"resource_class":"STANDARD","attribution_complete":true},
                          "finance_record_channel": {
                            "emitted_record_count": 1,
                            "emitted_record_bytes": 401,
                            "record_set_complete": true,
                            "drop_reason": "",
                            "record_digest": "eb4382d97e74ff45f9b2a28d967f44af2f083404ac535287e70bc1d9e36a8a20",
                            "stdout_truncated": false,
                            "stderr_truncated": false
                          },
                          "execution_environment": {
                            "environment_id": "sha256:actual-runtime-example",
                            "image_digest": "sha256:image-example",
                            "library_set_digest": "sha256:library-set-example",
                            "inventory_complete": true,
                            "package_apis": [
                              {
                                "name": "alphafrog_finance",
                                "version": "1.0.3",
                                "api_version": "1.0"
                              }
                            ]
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        TaskResultResponse response = gateway.getTaskResult(
                world.willfrog.alphafrogmicro.sandbox.idl.GetTaskResultRequest.newBuilder()
                        .setTaskId("task-v5").build());

        server.verify();
        assertTrue(response.hasFinanceRecordChannel());
        FinanceRecordChannelMetadata finance = response.getFinanceRecordChannel();
        assertEquals(1, finance.getEmittedRecordCount());
        assertEquals(401L, finance.getEmittedRecordBytes());
        assertTrue(finance.getRecordSetComplete());
        assertEquals("", finance.getDropReason());
        assertEquals("eb4382d97e74ff45f9b2a28d967f44af2f083404ac535287e70bc1d9e36a8a20",
                finance.getRecordDigest());
        assertFalse(finance.getStdoutTruncated());
        assertFalse(finance.getStderrTruncated());

        assertTrue(response.hasExecutionEnvironment());
        SandboxEnvironmentIdentity env = response.getExecutionEnvironment();
        assertEquals("sha256:actual-runtime-example", env.getEnvironmentId());
        assertEquals("sha256:image-example", env.getImageDigest());
        assertEquals("sha256:library-set-example", env.getLibrarySetDigest());
        assertTrue(env.getInventoryComplete());
        assertEquals(1, env.getPackageApisCount());
        SandboxPackageApi pkg = env.getPackageApis(0);
        assertEquals("alphafrog_finance", pkg.getName());
        assertEquals("1.0.3", pkg.getVersion());
        assertEquals("1.0", pkg.getApiVersion());
    }

    // 260808-finance-methodspec-v5 work package D: parent present with all
    // default-valued fields is "v5 enabled, empty batch". This is the contract
    // for sandbox runs that emit no __AF_FINANCE_RESULT_v1__ markers. Parent
    // must still be set so downstream sees hasFinanceRecordChannel() == true;
    // internal field defaults use proto3 zero values.
    @Test
    void getTaskResultShouldBridgeEmptyFinanceRecordChannelBatch() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway =
                new PythonSandboxGatewayServiceImpl(restTemplate, new ObjectMapper());
        ReflectionTestUtils.setField(gateway, "sandboxUrl", "http://sandbox");

        server.expect(once(), requestTo("http://sandbox/tasks/task-empty"))
                .andRespond(withSuccess(
                        "{\"task_id\":\"task-empty\",\"status\":\"SUCCEEDED\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("http://sandbox/tasks/task-empty/result"))
                .andRespond(withSuccess("""
                        {
                          "exit_code": 0,
                          "stdout": "no markers here",
                          "stderr": "",
                          "dataset_dir": "/tmp/ds",
                          "retryable": false,
                          "finance_record_channel": {
                            "emitted_record_count": 0,
                            "emitted_record_bytes": 0,
                            "record_set_complete": true,
                            "drop_reason": "",
                            "record_digest": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                            "stdout_truncated": false,
                            "stderr_truncated": false
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        TaskResultResponse response = gateway.getTaskResult(
                world.willfrog.alphafrogmicro.sandbox.idl.GetTaskResultRequest.newBuilder()
                        .setTaskId("task-empty").build());

        server.verify();
        assertTrue(response.hasFinanceRecordChannel(),
                "parent present with empty batch must still be set on proto");
        FinanceRecordChannelMetadata finance = response.getFinanceRecordChannel();
        assertEquals(0, finance.getEmittedRecordCount());
        assertEquals(0L, finance.getEmittedRecordBytes());
        assertTrue(finance.getRecordSetComplete());
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                finance.getRecordDigest());
        assertFalse(response.hasExecutionEnvironment());
    }
}
