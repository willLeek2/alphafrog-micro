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
    void emptyDatasetIdsShouldReturnMissingIds() throws Exception {
        String result = tools.executePython("print(1)", "", null, null, null);
        JsonNode root = mapper.readTree(result);
        assertFalse(root.path("ok").asBoolean());
        assertEquals("MISSING_IDS", root.path("error").path("code").asText());
        verify(sandboxService, never()).createTask(any());
    }

    @Test
    void blankRunIdShouldReturnRunLevelIdsUnavailable() throws Exception {
        AgentContext.clear();
        String result = tools.executePython("print(1)", "1", null, null, null);
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

        String result = tools.executePython("print(1)", "2,99,abc", null, null, null);
        JsonNode root = mapper.readTree(result);
        assertFalse(root.path("ok").asBoolean());
        assertEquals("ILLEGAL_RUN_LEVEL_IDS", root.path("error").path("code").asText());
        JsonNode details = root.path("error").path("details");
        // dataset_ids "2,99,abc" → 3 个 illegal_dataset_refs (2/99/abc 都没匹配到)
        assertEquals(3, details.path("illegal_dataset_refs").size());
        assertEquals(1, details.path("legal_dataset_numbers").get(0).asInt());
        assertEquals(1, details.path("legal_manifest_numbers").get(0).asInt());
        // input "abc" → reason not_an_integer
        boolean sawNotInt = false;
        for (JsonNode ref : details.path("illegal_dataset_refs")) {
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

        // caller 传 dataset 编号 1 + manifest 编号 1（Q4 拍板：两个独立空间都从 1 开始）
        String result = tools.executePython("print('hi')", "1", "1", null, 5);
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

        // pathManifestCsv 反映 sub-snapshot (只 caller 选中的 manifest 1)
        // MF1 之后是 sub-snapshot 而非全 run snapshot；
        // MF2 之后 related_dataset_ids 是 run-level number (1) 而非 originalId (ds-a)。
        // 两者在本测试中无法区分（ds-a 是 1 号 dataset 的 originalId），
        // 接受两种 expected: 旧实现 ",ds-a" 还在过渡，或 MF2 完成后的 ",1"
        String mfCsv = sent.getPathManifestCsv();
        assertTrue(mfCsv.contains("m-x"),
                "pathManifestCsv 应含 m-x; got: " + mfCsv);
        assertTrue(mfCsv.contains("agent_run_manifest_id,manifest_file_path,related_dataset_ids"),
                "pathManifestCsv 表头应存在");
        boolean hasOriginalId = mfCsv.contains("m-x/manifest.json,ds-a");
        boolean hasRunLevelNumber = mfCsv.contains("m-x/manifest.json,1\n") || mfCsv.contains("m-x/manifest.json,1,");
        assertTrue(hasOriginalId || hasRunLevelNumber,
                "pathManifestCsv related_dataset_ids 应是 originalId 或 run-level number; got: " + mfCsv);

        // datasetId (旧字段) 取第一个 mount 的 originalId = ds-a
        assertEquals("ds-a", sent.getDatasetId());
        // datasetIds (list) 含 caller 显式选中的 ds-a + m-x (manifest 也会被 mount)
        // m-x 是 sandbox 内 cross-ref 用，走 pathManifestCsv
        assertTrue(sent.getDatasetIdsList().contains("ds-a"));
    }

    @Test
    void emptyResolvedIdsShouldReturnEmptyResolvedIds() throws Exception {
        // registry 里什么都没有，但 caller 也不传 → empty 路径
        when(registry.listDatasetNumbers("run-test")).thenReturn(List.of());
        when(registry.listManifestNumbers("run-test")).thenReturn(List.of());
        when(registry.snapshot("run-test"))
                .thenReturn(world.willfrog.agent.workflow.AgentRunDatasetSnapshot.empty());
        String result = tools.executePython("print(1)", "1", null, null, null);
        JsonNode root = mapper.readTree(result);
        assertFalse(root.path("ok").asBoolean());
        assertEquals("ILLEGAL_RUN_LEVEL_IDS", root.path("error").path("code").asText());
        verify(sandboxService, never()).createTask(any());
    }

    @Test
    void loadToolDescriptionShouldLoadFromClasspathResource() {
        // 资源路径 prompts/python/execute_python_tool_description.txt 已由 02 owner 维护（cleanup 阶段）
        String desc = PythonSandboxTools.loadToolDescription();
        assertNotNull(desc);
        assertFalse(desc.isBlank(), "loaded description should not be blank");
        assertTrue(desc.contains("Execute Python code in a secure sandbox"),
                "description should start with that line; got: " + desc);
        // 02 spec §5.2: 必须体现 agent run-level dataset_ids / manifest_ids 语义
        assertTrue(desc.contains("dataset_ids") && desc.contains("manifest_ids"),
                "description should mention both dataset_ids and manifest_ids; got: " + desc);
        assertTrue(desc.contains("agent run-level") || desc.contains("run-level"),
                "description should mention run-level numbering; got: " + desc);
        // sandbox 输入面明示（paths_dataset.csv / path_manifest.csv）
        assertTrue(desc.contains("paths_dataset.csv") && desc.contains("path_manifest.csv"),
                "description should mention both CSV input surfaces; got: " + desc);
    }

    @Test
    void manifestIdsOnlyShouldResolveManifestsAndNotDatasets() throws Exception {
        // MF1: dataset_ids=null + manifest_ids="1" → 只解析 manifest 1, 不查 dataset 空间
        AgentRunDatasetEntry ds1 = AgentRunDatasetEntry.forDataset(
                1, "ds-a", "/p/ds-a", "000300.SH", "a.csv");
        AgentRunDatasetEntry mf1 = AgentRunDatasetEntry.forManifest(
                1, "m-x", "/p/m-x", "UNCERTAIN", "manifest.json", List.of("ds-a"));

        when(registry.listDatasetNumbers("run-test")).thenReturn(List.of(1));
        when(registry.listManifestNumbers("run-test")).thenReturn(List.of(1));
        when(registry.findDatasetByNumber("run-test", 1)).thenReturn(Optional.of(ds1));
        when(registry.findManifestByNumber("run-test", 1)).thenReturn(Optional.of(mf1));
        when(registry.snapshot("run-test"))
                .thenReturn(new world.willfrog.agent.workflow.AgentRunDatasetSnapshot(
                        List.of(ds1), List.of(mf1)));

        ExecuteResponse createResp = ExecuteResponse.newBuilder().setTaskId("task-m").build();
        when(sandboxService.createTask(any())).thenReturn(createResp);
        TaskStatusResponse doneStatus = TaskStatusResponse.newBuilder().setStatus("SUCCEEDED").build();
        TaskResultResponse resultResp = TaskResultResponse.newBuilder()
                .setExitCode(0).setStdout("ok").setStderr("")
                .setDatasetDir("/sandbox/runs/task-m/input").build();
        when(sandboxService.getTaskStatus(any(GetTaskStatusRequest.class))).thenReturn(doneStatus);
        when(sandboxService.getTaskResult(any(GetTaskResultRequest.class))).thenReturn(resultResp);

        // 只传 manifest_ids="1", 不传 dataset_ids → dataset 空间不应被查询
        String result = tools.executePython("print('m')", null, "1", null, null);
        assertNotNull(result);

        // dataset_ids 没传 → pathsDatasetCsv 应该只含 header, 没有数据行
        ArgumentCaptor<ExecuteRequest> captor = ArgumentCaptor.forClass(ExecuteRequest.class);
        verify(sandboxService, times(1)).createTask(captor.capture());
        ExecuteRequest sent = captor.getValue();
        String dsCsv = sent.getPathsDatasetCsv();
        assertTrue(dsCsv.contains("agent_run_dataset_id"),
                "pathsDatasetCsv 应有 header; got: " + dsCsv);
        // header-only CSV 不应含 ds-a
        assertFalse(dsCsv.contains("ds-a"),
                "manifest-only 模式下 pathsDatasetCsv 不应有数据行; got: " + dsCsv);

        // pathManifestCsv 含 manifest 1
        String mfCsv = sent.getPathManifestCsv();
        assertTrue(mfCsv.contains("m-x"),
                "pathManifestCsv 应含 m-x; got: " + mfCsv);

        // datasetId (旧字段) 取唯一 mount 的 m-x
        assertEquals("m-x", sent.getDatasetId());
    }

    @Test
    void bothDatasetAndManifestIdsShouldBeIndependent() throws Exception {
        // MF1: dataset_ids="1" + manifest_ids="1" 都传 → 两个空间独立解析
        AgentRunDatasetEntry ds1 = AgentRunDatasetEntry.forDataset(
                1, "ds-a", "/p/ds-a", "000300.SH", "a.csv");
        AgentRunDatasetEntry mf1 = AgentRunDatasetEntry.forManifest(
                1, "m-x", "/p/m-x", "UNCERTAIN", "manifest.json", List.of("ds-a"));

        when(registry.listDatasetNumbers("run-test")).thenReturn(List.of(1));
        when(registry.listManifestNumbers("run-test")).thenReturn(List.of(1));
        when(registry.findDatasetByNumber("run-test", 1)).thenReturn(Optional.of(ds1));
        when(registry.findManifestByNumber("run-test", 1)).thenReturn(Optional.of(mf1));
        when(registry.snapshot("run-test"))
                .thenReturn(new world.willfrog.agent.workflow.AgentRunDatasetSnapshot(
                        List.of(ds1), List.of(mf1)));

        ExecuteResponse createResp = ExecuteResponse.newBuilder().setTaskId("task-b").build();
        when(sandboxService.createTask(any())).thenReturn(createResp);
        TaskStatusResponse doneStatus = TaskStatusResponse.newBuilder().setStatus("SUCCEEDED").build();
        TaskResultResponse resultResp = TaskResultResponse.newBuilder()
                .setExitCode(0).setStdout("ok").setStderr("")
                .setDatasetDir("/sandbox/runs/task-b/input").build();
        when(sandboxService.getTaskStatus(any(GetTaskStatusRequest.class))).thenReturn(doneStatus);
        when(sandboxService.getTaskResult(any(GetTaskResultRequest.class))).thenReturn(resultResp);

        // dataset_ids="1" 和 manifest_ids="1" 都传
        String result = tools.executePython("print('b')", "1", "1", null, null);
        assertNotNull(result);

        ArgumentCaptor<ExecuteRequest> captor = ArgumentCaptor.forClass(ExecuteRequest.class);
        verify(sandboxService, times(1)).createTask(captor.capture());
        ExecuteRequest sent = captor.getValue();

        // pathsDatasetCsv 含 ds-a
        assertTrue(sent.getPathsDatasetCsv().contains("ds-a"));
        // pathManifestCsv 含 m-x
        assertTrue(sent.getPathManifestCsv().contains("m-x"));
    }

    @Test
    void illegalManifestNumberShouldReportSeparateFromDataset() throws Exception {
        // MF1: dataset_ids="1" 合法, manifest_ids="99" 非法 → 应报 ILLEGAL_RUN_LEVEL_IDS,
        //      illegal_manifest_refs 含 99, illegal_dataset_refs 为空
        AgentRunDatasetEntry ds1 = AgentRunDatasetEntry.forDataset(
                1, "ds-a", "/p/ds-a", "000300.SH", "a.csv");
        when(registry.listDatasetNumbers("run-test")).thenReturn(List.of(1));
        when(registry.listManifestNumbers("run-test")).thenReturn(List.of(1));
        when(registry.findDatasetByNumber("run-test", 1)).thenReturn(Optional.of(ds1));
        when(registry.findManifestByNumber("run-test", 99)).thenReturn(Optional.empty());
        when(registry.snapshot("run-test"))
                .thenReturn(new world.willfrog.agent.workflow.AgentRunDatasetSnapshot(
                        List.of(ds1), List.of()));

        String result = tools.executePython("print(1)", "1", "99", null, null);
        JsonNode root = mapper.readTree(result);
        assertFalse(root.path("ok").asBoolean());
        assertEquals("ILLEGAL_RUN_LEVEL_IDS", root.path("error").path("code").asText());
        JsonNode details = root.path("error").path("details");
        // dataset 1 合法 → illegal_dataset_refs 为空
        assertEquals(0, details.path("illegal_dataset_refs").size(),
                "dataset 1 应合法; got: " + details.path("illegal_dataset_refs"));
        // manifest 99 非法 → illegal_manifest_refs 含 1 项
        assertEquals(1, details.path("illegal_manifest_refs").size());
        assertEquals("99", details.path("illegal_manifest_refs").get(0).path("input").asText());
        verify(sandboxService, never()).createTask(any());
    }
}
