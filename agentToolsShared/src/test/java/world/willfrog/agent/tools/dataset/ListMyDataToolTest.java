package world.willfrog.agent.tools.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.workflow.AgentRunDatasetEntry;
import world.willfrog.agent.workflow.AgentRunDatasetRegistry;
import world.willfrog.agent.workflow.AgentRunDatasetSnapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 260623-harness-optimization-02: 锁定 listMyData 的 query / filter / pagination 行为。
 *
 * <p>MF5 新增 8 形参 overload 的 grep raw file content 测试（spec Q10）。
 * 6 形参 overload 走 originalId 子串匹配（保留 ToolRouter 入口旧行为）。
 */
class ListMyDataToolTest {

    private ListMyDataTool tool;
    private AgentRunDatasetRegistry registry;
    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tmp;

    @BeforeEach
    void setUp() {
        tool = new ListMyDataTool(mapper);
        registry = mock(AgentRunDatasetRegistry.class);
        tool.setAgentRunDatasetRegistry(registry);
        AgentContext.setRunId("run-1");
    }

    @AfterEach
    void tearDown() {
        AgentContext.clear();
    }

    private AgentRunDatasetSnapshot snapshot() {
        AgentRunDatasetEntry ds1 = AgentRunDatasetEntry.forDataset(
                1, "ds-alpha", "/data/database_fetched/ds-alpha/a.csv",
                "000300.SH", "a.csv");
        AgentRunDatasetEntry ds2 = AgentRunDatasetEntry.forDataset(
                2, "ds-beta", "/data/database_fetched/ds-beta/b.csv",
                "000300.SH#510300.SH", "b.csv");
        AgentRunDatasetEntry ds3 = AgentRunDatasetEntry.forDataset(
                3, "ds-gamma-uncertain", "/data/database_fetched/ds-gamma/g.csv",
                "UNCERTAIN", "g.csv");
        AgentRunDatasetEntry mf1 = AgentRunDatasetEntry.forManifest(
                1, "manifest-mix", "/data/manifests/v1/manifest-manifest-mix/m.json",
                "000300.SH", "manifest.json", List.of("1", "2"));
        AgentRunDatasetEntry mf2 = AgentRunDatasetEntry.forManifest(
                2, "manifest-gamma-only", "/data/manifests/v1/manifest-gamma-only/m.json",
                "UNCERTAIN", "manifest.json", List.of("3"));
        return new AgentRunDatasetSnapshot(
                List.of(ds1, ds2, ds3),
                List.of(mf1, mf2));
    }

    /**
     * 写一个真实文件到 tmpDir，返回 dataset entry（persistedPath 指向该文件）。
     */
    private AgentRunDatasetEntry writeDatasetFile(int number, String datasetId, String sortKey,
                                                 String fromTsCode, String content) throws IOException {
        Path file = tmp.resolve(datasetId + "-" + sortKey);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return AgentRunDatasetEntry.forDataset(
                number, datasetId, file.toString(), fromTsCode, sortKey);
    }

    // ----- 6 形参版（ToolRouter 入口，旧语义） -----
    // Cindy round 2 review MF-new-1：6 形参入口改名为 listMyData6（非 @Tool 注解），
    // 避免与唯一 LLM-facing 8 形参 @Tool 入口同名歧义；行为不变（grep 对 originalId 子串匹配）。











    // ----- MF5: 8 形参版 grep raw file content（spec Q10） -----









}
