package world.willfrog.agent.tools.python;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.workflow.AgentRunDatasetEntry;
import world.willfrog.agent.workflow.AgentRunDatasetRegistry;
import world.willfrog.alphafrogmicro.sandbox.idl.ExecuteRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.ExecuteResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskResultRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskStatusRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.PythonSandboxService;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskResultResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskStatusResponse;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 260623-harness-optimization-02: 锁定 PythonSandboxTools.executePython 的 02 行为。
 *
 * <p>覆盖范围：
 * <ul>
 *   <li>空 dataset_ids → MISSING_DATASET_IDS</li>
 *   <li>缺 runId / registry → RUN_LEVEL_IDS_UNAVAILABLE</li>
 *   <li>Q12 非法编号 → ILLEGAL_RUN_LEVEL_IDS + 列出 legal numbers</li>
 *   <li>Q13 选中子集 → pathsDatasetCsv 反映 sub-snapshot, pathManifestCsv 反映全 run</li>
 *   <li>protobuf 字段被注入到 ExecuteRequest（pathsDatasetCsv / pathManifestCsv）</li>
 *   <li>CSV 占位符 /__AF_INPUT__/ 在 CSV 内容中</li>
 * </ul>
 */
class PythonSandboxToolsTest {

    private PythonSandboxTools tools;
    private PythonSandboxService sandboxService;
    private AgentRunDatasetRegistry registry;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        tools = new PythonSandboxTools(mapper);
        sandboxService = mock(PythonSandboxService.class);
        registry = mock(AgentRunDatasetRegistry.class);
        // executePython 是实例方法，registry 通过 setter 注入（业务路径是 Spring @Autowired(required=false)）
        tools.setAgentRunDatasetRegistry(registry);
        // 通过反射注入 dubbo proxy（@DubboReference 字段），避免启动失败
        try {
            java.lang.reflect.Field f = PythonSandboxTools.class.getDeclaredField("pythonSandboxService");
            f.setAccessible(true);
            f.set(tools, sandboxService);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        AgentContext.setRunId("run-test");
    }

    @AfterEach
    void tearDown() {
        AgentContext.clear();
    }

    @Test
    void emptyDatasetIdsShouldReturnMissingDatasetIds() throws Exception {
        String result = tools.executePython("print(1)", "", null, null);
        JsonNode root = mapper.readTree(result);
        assertFalse(root.path("ok").asBoolean());
        assertEquals("MISSING_DATASET_IDS", root.path("error").path("code").asText());
        verify(sandboxService, never()).createTask(any());
    }

    @Test
    void blankRunIdShouldReturnRunLevelIdsUnavailable() throws Exception {
        AgentContext.clear();
        String result = tools.executePython("print(1)", "1", null, null);
        JsonNode root = mapper.readTree(result);
        assertFalse(root.path("ok").asBoolean());
        assertEquals("RUN_LEVEL_IDS_UNAVAILABLE", root.path("error").path("code").asText());
        verify(sandboxService, never()).createTask(any());
    }

    @Test
    void illegalDatasetNumberShouldReportLegalNumbers() throws Exception {
        // 只有 1 个 dataset(编号 1) + 1 个 manifest(编号 1)，但 caller 传了 2, 99, abc
        AgentRunDatasetEntry ds = AgentRunDatasetEntry.forDataset(
                1, "ds-a", "/p/ds-a", "000300.SH", "a.csv");
        when(registry.listDatasetNumbers("run-test")).thenReturn(List.of(1));
        when(registry.listManifestNumbers("run-test")).thenReturn(List.of(1));
        when(registry.findDatasetByNumber("run-test", 2)).thenReturn(Optional.empty());
        when(registry.findManifestByNumber("run-test", 2)).thenReturn(Optional.empty());
        when(registry.findDatasetByNumber("run-test", 99)).thenReturn(Optional.empty());
        when(registry.findManifestByNumber("run-test", 99)).thenReturn(Optional.empty());
        when(registry.snapshot("run-test"))
                .thenReturn(new world.willfrog.agent.workflow.AgentRunDatasetSnapshot(
                        List.of(ds),
                        List.of(AgentRunDatasetEntry.forManifest(
                                1, "m-x", "/p/m-x", "UNCERTAIN", "manifest.json", List.of("ds-a")))));

        String result = tools.executePython("print(1)", "2,99,abc", null, null);
        JsonNode root = mapper.readTree(result);
        assertFalse(root.path("ok").asBoolean());
        assertEquals("ILLEGAL_RUN_LEVEL_IDS", root.path("error").path("code").asText());
        JsonNode details = root.path("error").path("details");
        assertEquals(3, details.path("illegal_refs").size());
        assertEquals(1, details.path("legal_dataset_numbers").get(0).asInt());
        assertEquals(1, details.path("legal_manifest_numbers").get(0).asInt());
        // input "abc" → reason not_an_integer
        boolean sawNotInt = false;
        for (JsonNode ref : details.path("illegal_refs")) {
            if ("abc".equals(ref.path("input").asText())) {
                assertEquals("not_an_integer", ref.path("reason").asText());
                sawNotInt = true;
            }
        }
        assertTrue(sawNotInt, "abc 应当标记为 not_an_integer");
        verify(sandboxService, never()).createTask(any());
    }

    @Test
    void validRunLevelNumbersShouldInjectCsvAndCallSandbox() throws Exception {
        AgentRunDatasetEntry ds = AgentRunDatasetEntry.forDataset(
                1, "ds-a", "/p/ds-a", "000300.SH", "a.csv");
        AgentRunDatasetEntry mf = AgentRunDatasetEntry.forManifest(
                1, "m-x", "/p/m-x", "UNCERTAIN", "manifest.json", List.of("ds-a"));

        when(registry.listDatasetNumbers("run-test")).thenReturn(List.of(1));
        when(registry.listManifestNumbers("run-test")).thenReturn(List.of(1));
        when(registry.findDatasetByNumber("run-test", 1)).thenReturn(Optional.of(ds));
        when(registry.findManifestByNumber("run-test", 1)).thenReturn(Optional.of(mf));
        when(registry.snapshot("run-test"))
                .thenReturn(new world.willfrog.agent.workflow.AgentRunDatasetSnapshot(
                        List.of(ds), List.of(mf)));

        // 模拟 sandbox 任务一次成功
        ExecuteResponse createResp = ExecuteResponse.newBuilder()
                .setTaskId("task-1").build();
        when(sandboxService.createTask(any())).thenReturn(createResp);
        TaskStatusResponse doneStatus = TaskStatusResponse.newBuilder()
                .setStatus("SUCCEEDED").build();
        TaskResultResponse resultResp = TaskResultResponse.newBuilder()
                .setExitCode(0)
                .setStdout("hello")
                .setStderr("")
                .setDatasetDir("/sandbox/runs/task-1/input")
                .build();
        when(sandboxService.getTaskStatus(any(GetTaskStatusRequest.class))).thenReturn(doneStatus);
        when(sandboxService.getTaskResult(any(GetTaskResultRequest.class))).thenReturn(resultResp);

        // caller 传 dataset 编号 1；dataset 与 manifest 编号空间独立但都从 1 开始，
        // 所以 dataset 1 解析为 ds-a；pathManifestCsv 仍然反映全 run snapshot（含 m-x）。
        String result = tools.executePython("print('hi')", "1", null, 5);
        assertNotNull(result);

        // 抓 createTask 的入参验证 CSV 注入
        ArgumentCaptor<ExecuteRequest> captor = ArgumentCaptor.forClass(ExecuteRequest.class);
        verify(sandboxService, times(1)).createTask(captor.capture());
        ExecuteRequest sent = captor.getValue();

        // pathsDatasetCsv 反映 sub-snapshot (只 caller 选中的 dataset 1)
        String dsCsv = sent.getPathsDatasetCsv();
        assertTrue(dsCsv.contains("1,/__AF_INPUT__/ds-a/a.csv,000300.SH"),
                "pathsDatasetCsv 应含 dataset 1 行；got: " + dsCsv);
        assertTrue(dsCsv.contains("agent_run_dataset_id,dataset_file_path,from_ts_code"),
                "pathsDatasetCsv 表头应存在");

        // pathManifestCsv 反映全 run snapshot (含 manifest 1，即便 caller 没显式选)
        String mfCsv = sent.getPathManifestCsv();
        assertTrue(mfCsv.contains("1,/__AF_INPUT__/m-x/manifest.json,ds-a"),
                "pathManifestCsv 应含 manifest 1 行；got: " + mfCsv);
        assertTrue(mfCsv.contains("agent_run_manifest_id,manifest_file_path,related_dataset_ids"),
                "pathManifestCsv 表头应存在");

        // datasetId (旧字段) 取第一个 mount 的 originalId = ds-a
        assertEquals("ds-a", sent.getDatasetId());
        // datasetIds (list) 只含 caller 显式选中的 ds-a，不含 m-x
        // m-x 是 sandbox 内 cross-ref 用，走 pathManifestCsv
        assertEquals(List.of("ds-a"), sent.getDatasetIdsList());
    }

    @Test
    void emptyResolvedIdsShouldReturnEmptyResolvedIds() throws Exception {
        // registry 里什么都没有，但 caller 也不传 → empty 路径
        when(registry.listDatasetNumbers("run-test")).thenReturn(List.of());
        when(registry.listManifestNumbers("run-test")).thenReturn(List.of());
        when(registry.snapshot("run-test"))
                .thenReturn(world.willfrog.agent.workflow.AgentRunDatasetSnapshot.empty());
        String result = tools.executePython("print(1)", "1", null, null);
        JsonNode root = mapper.readTree(result);
        assertFalse(root.path("ok").asBoolean());
        assertEquals("ILLEGAL_RUN_LEVEL_IDS", root.path("error").path("code").asText());
        verify(sandboxService, never()).createTask(any());
    }

    @Test
    void loadToolDescriptionShouldFallbackWhenResourceMissing() {
        // 资源路径 prompts/python/execute_python_tool_description.txt 不存在 → 走 fallback
        String desc = PythonSandboxTools.loadToolDescription();
        assertNotNull(desc);
        assertTrue(desc.contains("Execute Python code in a secure sandbox"),
                "fallback description 应该以原句开头; got: " + desc);
    }
}
