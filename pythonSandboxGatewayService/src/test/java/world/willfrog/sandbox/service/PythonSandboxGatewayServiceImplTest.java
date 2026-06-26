package world.willfrog.sandbox.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PythonSandboxGatewayServiceImplTest {

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
