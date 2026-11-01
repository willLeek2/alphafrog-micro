package world.willfrog.agent.tools.dataanalysis;

import static world.willfrog.agent.tools.support.ToolPromptTestFixture.configure;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisCapacityService;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceClass;
import world.willfrog.agent.platform.dataanalysis.PythonSandboxDispatchStore;
import world.willfrog.agent.platform.exception.RunStartedAtMissingException;
import world.willfrog.agent.platform.service.AgentRunBudgetService;
import world.willfrog.agent.tools.python.DataAnalysisCapacityProperties;
import world.willfrog.agent.tools.sandboxjob.SandboxTerminalResultView;
import world.willfrog.agent.workflow.AgentRunDatasetEntry;
import world.willfrog.agent.workflow.AgentRunDatasetRegistry;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisTerminalRecorder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * executeQuery 工具侧语义的单元测试：入口参数校验、运行器渲染与 SQL 回填、
 * 信封到统一响应的映射。沙箱 RPC 面与生命周期编排的测试在 executePython 既有测试网
 * 与框架测试里。
 */
class SqlQueryToolsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SqlQueryTools tools = configure(new SqlQueryTools(objectMapper));
    private final SqlQueryJobResultAdapter resultAdapter = new SqlQueryJobResultAdapter(objectMapper);

    // ---------- 入口参数校验（先于任何上下文访问，纯构造即可测） ----------






    // ---------- 扫描量准入（元数据行数/字节数求和对照容量硬上限） ----------







    // ---------- 运行器渲染 ----------





    // ---------- 信封 → 统一响应 ----------









    private JsonNode decodeSpec(String rendered) throws Exception {
        String specJson = new String(java.util.Base64.getDecoder().decode(
                java.util.regex.Pattern.compile("SPEC_B64 = \"([A-Za-z0-9+/=]+)\"")
                        .matcher(rendered).results().findFirst().orElseThrow().group(1)),
                java.nio.charset.StandardCharsets.UTF_8);
        return objectMapper.readTree(specJson);
    }

    private static SandboxTerminalResultView view(String status, Integer exitCode, String stdout, String stderr) {
        return new SandboxTerminalResultView(status, exitCode, stdout, stderr, null, null, null, null, null, null);
    }

    /** 构造两条 run 级数据集条目（1=CSV，2=Parquet）。 */
    private static final class AgentRunDatasetEntryBuilder {
        List<world.willfrog.agent.workflow.AgentRunDatasetEntry> two() {
            return List.of(
                    world.willfrog.agent.workflow.AgentRunDatasetEntry.forDataset(
                            1, "ds-1", "/persisted/ds-1", "000001.SZ", "daily.csv"),
                    world.willfrog.agent.workflow.AgentRunDatasetEntry.forDataset(
                            2, "ds-2", "/persisted/ds-2", "000002.SZ", "weekly.parquet"));
        }
    }
}
