package world.willfrog.agent.tools.compaction;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import world.willfrog.agent.platform.artifact.RunRawRefLocalStore;
import world.willfrog.agent.platform.artifact.RunRawRefStoreImpl;
import world.willfrog.agent.platform.artifact.ToolOutputRefService;
import world.willfrog.agent.platform.context.AgentContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * raw_ref 全链路组合测试（260814 scheduler-03：本地磁盘后端）。
 *
 * <p>真实的 RereadToolHandler（工具层）+ 真实的 RunRawRefStoreImpl（短 ID 映射层）+
 * 真实的 RunRawRefLocalStore（本地磁盘存储层）叠在一起跑完整读写链——三层之间没有
 * 任何 mock 隔断，钉住的是「组合后的行为」而不是单层合同（单层合同各归其位：存储层
 * 归 agentPlatformShared 的 RunRawRefLocalStoreTest，服务层归
 * ToolOutputRefServiceImplTest，工具层参数门禁归 RereadToolHandlerTest）。</p>
 *
 * <p>与旧 Redis 版组合测试的差异：fake Redis（计数器键、映射哈希、ZSET 索引、touch
 * 滑动脚本）整体废除——内容与索引都落在 @TempDir 的本地磁盘上。随 Redis 内部结构
 * 一起消失的语义（读取 touch 滑动 TTL、映射比制品活得久）不再钉；替换为本地后端的
 * 等价行为合同：Run 终态清理后短 ID 既不能解析也不能读内容（fail-closed）。</p>
 *
 * <p>钉住的组合行为：</p>
 * <ul>
 *   <li>①注册→短 ID→reread 全链路往返（每 run 序号发号、内容原样读回）——
 *       {@link #fullChainRegisterAndRereadShouldRoundTripContent}</li>
 *   <li>②keyword/range 两种读取模式经全链路后的切片正确性 ——
 *       {@link #rereadKeywordAndRangeModesShouldSliceCorrectlyThroughChain}</li>
 *   <li>③内容层严格归属：短 ID 属于该 run 也不放行，userId 错误/空白必须在
 *       内容层 fail-closed —— {@link #wrongOrBlankUserShouldBeRejectedAtContentLayer}</li>
 *   <li>④跨 run 短 ID 不可解析 —— {@link #crossRunShortIdShouldNotResolve}</li>
 *   <li>⑤belongsToRun 按 run 隔离 —— {@link #belongsToRunShouldDistinguishRuns}</li>
 *   <li>⑥Run 终态清理后：短 ID 解析失败、内容读取失败、磁盘目录整体删除 ——
 *       {@link #terminalCleanupShouldFailClosedOnContentAndRemoveFiles}</li>
 * </ul>
 */
class RawRefRereadCompositeChainTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RunRawRefLocalStore localStore;
    private RunRawRefStoreImpl store;
    private RereadToolHandler handler;
    private Path rawRefRoot;

    @BeforeEach
    void setUp() {
        rawRefRoot = tempDir.resolve("raw-ref");
        localStore = new RunRawRefLocalStore(rawRefRoot.toString(),
                8_388_608L, 512, 536_870_912L);
        store = new RunRawRefStoreImpl(localStore);
        handler = new RereadToolHandler(mock(ToolOutputRefService.class), objectMapper,
                Optional.empty(), Optional.of(store));
        AgentContext.clear();
    }

    @AfterEach
    void tearDown() {
        AgentContext.clear();
    }

    // ===== ① 全链路往返 =====

    @Test
    void fullChainRegisterAndRereadShouldRoundTripContent() throws Exception {
        AgentContext.setRunId("run-chain");
        AgentContext.setUserId("user-chain");
        String content = "line1\nline2-needle\nline3";

        String shortId = store.register("run-chain", "user-chain", "大输出", content, 7200);
        assertEquals("raw_ref_001", shortId, "首发号必须生成 raw_ref_001");

        // 工具层 reread 全链路读回：ok=true、内容一字不差
        Map<String, Object> response = objectMapper.readValue(
                handler.reread("raw_ref_001", null, null, null), new TypeReference<>() {});
        assertEquals(Boolean.TRUE, response.get("ok"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertEquals(content, data.get("content"), "全链路读回内容必须与注册原文一致");
        assertEquals(Boolean.FALSE, data.get("hasMore"));

        // 第二次注册：短 ID 递增，且两条都保持可读
        String shortId2 = store.register("run-chain", "user-chain", "大输出2", "second", 7200);
        assertEquals("raw_ref_002", shortId2);
        Map<String, Object> second = objectMapper.readValue(
                handler.reread("raw_ref_002", null, null, null), new TypeReference<>() {});
        @SuppressWarnings("unchecked")
        Map<String, Object> secondData = (Map<String, Object>) second.get("data");
        assertEquals("second", secondData.get("content"));
    }

    // ===== ② keyword / range 切片 =====

    @Test
    void rereadKeywordAndRangeModesShouldSliceCorrectlyThroughChain() throws Exception {
        AgentContext.setRunId("run-slice");
        AgentContext.setUserId("user-slice");
        String longContent = "abcdefghij".repeat(300); // 3000 字符
        store.register("run-slice", "user-slice", "长输出", longContent, 7200);

        // range 模式第一段：offset=0 limit=1500（无 keyword 时 limit 必须 ≥1001，1500 合法）
        Map<String, Object> first = objectMapper.readValue(
                handler.reread("raw_ref_001", null, 0, 1500), new TypeReference<>() {});
        assertEquals(Boolean.TRUE, first.get("ok"));
        @SuppressWarnings("unchecked")
        Map<String, Object> firstData = (Map<String, Object>) first.get("data");
        assertEquals(longContent.substring(0, 1500), firstData.get("content"));
        assertEquals(Boolean.TRUE, firstData.get("hasMore"), "3000 字符读 1500 必须还有剩余");
        assertEquals(1500, firstData.get("nextOffset"));
        assertEquals(3000, firstData.get("totalLength"));

        // range 模式续读：从 nextOffset 继续读完
        Map<String, Object> second = objectMapper.readValue(
                handler.reread("raw_ref_001", null, 1500, 1500), new TypeReference<>() {});
        @SuppressWarnings("unchecked")
        Map<String, Object> secondData = (Map<String, Object>) second.get("data");
        assertEquals(longContent.substring(1500), secondData.get("content"));
        assertEquals(Boolean.FALSE, secondData.get("hasMore"));
        assertEquals(3000, secondData.get("nextOffset"));

        // keyword 模式：只回匹配行（经短 ID→内容→逐行过滤全链）
        AgentContext.setRunId("run-kw");
        store.register("run-kw", "user-slice", "kw", "alpha\nneedle-one\nbeta\nneedle-two\n", 7200);
        Map<String, Object> kw = objectMapper.readValue(
                handler.reread("raw_ref_001", "needle", null, null), new TypeReference<>() {});
        assertEquals(Boolean.TRUE, kw.get("ok"));
        @SuppressWarnings("unchecked")
        Map<String, Object> kwData = (Map<String, Object>) kw.get("data");
        assertEquals("needle-one\nneedle-two", kwData.get("content"));
        assertEquals("needle", kwData.get("keyword"));
    }

    // ===== ③ 内容层严格归属（短 ID 属于该 run 也不放行） =====

    @Test
    void wrongOrBlankUserShouldBeRejectedAtContentLayer() {
        AgentContext.setRunId("run-guard");
        AgentContext.setUserId("user-guard");
        store.register("run-guard", "user-guard", "秘密", "top-secret-content", 7200);

        // 同 run 但 userId 错误：短 ID 属于该 run 也不放行——内容层四值校验
        // fail-closed。归属拒绝有独立消息（"does not belong to current
        // run/user"），与条目缺失的 "rawRef not found" 区分——两者都是
        // IllegalArgumentException fail-closed
        AgentContext.setUserId("intruder");
        IllegalArgumentException wrongUser = assertThrows(IllegalArgumentException.class,
                () -> handler.reread("raw_ref_001", null, null, null));
        assertTrue(wrongUser.getMessage().contains("does not belong to current run/user"),
                wrongUser.getMessage());

        // userId 空白同样 fail-closed
        AgentContext.setUserId("");
        assertThrows(IllegalArgumentException.class,
                () -> handler.reread("raw_ref_001", null, null, null));
    }

    // ===== ④ 跨 run 短 ID 不可解析 =====

    @Test
    void crossRunShortIdShouldNotResolve() {
        AgentContext.setRunId("run-A");
        AgentContext.setUserId("user-A");
        store.register("run-A", "user-A", "A 的输出", "content-A", 7200);

        // 另一个 run 拿着同样的短 ID：索引按 (runId, ref) 隔离，解析必须直接失败
        AgentContext.setRunId("run-B");
        IllegalArgumentException notFound = assertThrows(IllegalArgumentException.class,
                () -> handler.reread("raw_ref_001", null, null, null));
        assertTrue(notFound.getMessage().contains("rawRef not found"), notFound.getMessage());
    }

    // ===== ⑤ belongsToRun 按 run 隔离 =====

    @Test
    void belongsToRunShouldDistinguishRuns() {
        store.register("run-owner", "user-1", "输出", "content", 7200);
        assertTrue(store.belongsToRun("run-owner", "raw_ref_001"));
        assertFalse(store.belongsToRun("run-other", "raw_ref_001"), "别的 run 不得认领该 shortId");
        assertFalse(store.belongsToRun(null, "raw_ref_001"));
        assertFalse(store.belongsToRun("run-owner", null));
    }

    // ===== ⑥ Run 终态清理：解析失败 + 内容失败 + 文件删除 =====

    @Test
    void terminalCleanupShouldFailClosedOnContentAndRemoveFiles() throws Exception {
        AgentContext.setRunId("run-decay");
        AgentContext.setUserId("user-decay");
        store.register("run-decay", "user-decay", "会清理的", "decay-content", 7200);
        Path runDir = rawRefRoot.resolve("run-decay");
        assertTrue(Files.isDirectory(runDir));

        localStore.cleanupRun("run-decay");

        // 磁盘目录整体删除；短 ID 解析失败；内容读取随条目消失 fail-closed
        assertFalse(Files.exists(runDir), "终态清理必须删除 run 目录");
        assertFalse(store.belongsToRun("run-decay", "raw_ref_001"));
        IllegalArgumentException gone = assertThrows(IllegalArgumentException.class,
                () -> handler.reread("raw_ref_001", null, null, null));
        assertTrue(gone.getMessage().contains("rawRef not found"), gone.getMessage());
    }
}
