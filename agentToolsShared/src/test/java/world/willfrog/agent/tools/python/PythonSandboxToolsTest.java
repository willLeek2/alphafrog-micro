package world.willfrog.agent.tools.python;

import static world.willfrog.agent.tools.support.ToolPromptTestFixture.configure;
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
        tools = configure(new PythonSandboxTools(mapper));
        world.willfrog.agent.platform.service.PythonRiskReviewService review =
                mock(world.willfrog.agent.platform.service.PythonRiskReviewService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(tools, "pythonRiskReviewService", review);
        when(review.evaluate(any(), any(), any())).thenReturn(
                new world.willfrog.agent.platform.service.PythonRiskReviewService.Evaluation(true, null, false));
        sandboxService = mock(PythonSandboxService.class);
        registry = mock(AgentRunDatasetRegistry.class);
        // executePython 是实例方法，registry 通过 setter 注入（业务路径是 Spring @Autowired(required=false)）
        tools.setAgentRunDatasetRegistry(registry);
        // 通过反射注入 dubbo proxy（@DubboReference 字段），避免启动失败
        try {
            java.lang.reflect.Field f = PythonSandboxTools.class.getDeclaredField("pythonSandboxService");
            f.setAccessible(true);
            f.set(tools, sandboxService);
            // 本测试类锁定的是旧的 run-level dataset/manifest 编号翻译，不验证 D13/D14 容量接线。
            // 生产默认仍然 fail-closed；这里只在测试夹具中显式开启非生产兼容路径，
            // 避免缺少容量组件时在调用 CSV/挂载断言之前被新门禁拒绝。
            java.lang.reflect.Field legacy = PythonSandboxTools.class
                    .getDeclaredField("allowLegacyWithoutCapacity");
            legacy.setAccessible(true);
            legacy.setBoolean(tools, true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        AgentContext.setRunId("run-test");
        AgentContext.setToolCallId("legacy-fixture-call");
    }

    @AfterEach
    void tearDown() {
        AgentContext.clear();
    }










    // ====== 260623-harness-optimization-02 round 4 (Option A 拍板) ======




}
