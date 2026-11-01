package world.willfrog.agent.tools.compaction;

import static world.willfrog.agent.tools.support.ToolPromptTestFixture.configure;
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
        handler = configure(new RereadToolHandler(mock(ToolOutputRefService.class), objectMapper,
                Optional.empty(), Optional.of(store)));
        AgentContext.clear();
    }

    @AfterEach
    void tearDown() {
        AgentContext.clear();
    }

    // ===== ① 全链路往返 =====


    // ===== ② keyword / range 切片 =====


    // ===== ③ 内容层严格归属（短 ID 属于该 run 也不放行） =====


    // ===== ④ 跨 run 短 ID 不可解析 =====


    // ===== ⑤ belongsToRun 按 run 隔离 =====


    // ===== ⑥ Run 终态清理：解析失败 + 内容失败 + 文件删除 =====

}
