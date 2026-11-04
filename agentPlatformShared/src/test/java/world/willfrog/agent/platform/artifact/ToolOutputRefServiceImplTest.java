package world.willfrog.agent.platform.artifact;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ToolOutputRefServiceImpl 服务层契约测试（260814 scheduler-03：本地磁盘后端）。
 *
 * <p>服务不再依赖 Redis（{@link PersistentArtifactRegistry}），内容与索引都落在
 * {@link RunRawRefLocalStore} 的 @TempDir 根目录上。本文件只测服务自身的契约面：
 * 分页/过滤读取、跨 run 拒绝、locator 重绑定、显式上下文 overload、无上下文
 * fail-closed、不可猜测引用格式。存储层的上限/TTL/清理/重启等机械性语义归
 * {@link RunRawRefLocalStoreTest}。</p>
 *
 * <p>测试里 maxLimit 配置为 8、rawRef TTL 为 1 小时（与旧 Redis 版测试同款配置，
 * 保证分页截断断言不变）。</p>
 */
class ToolOutputRefServiceImplTest {

    @TempDir
    Path tempDir;

    private RunRawRefLocalStore localStore;
    private ToolOutputRefServiceImpl service;

    @BeforeEach
    void setUp() {
        localStore = new RunRawRefLocalStore(tempDir.resolve("raw-ref").toString(),
                8_388_608L, 512, 536_870_912L);
        AgentLlmLocalConfigLoader loader = mock(AgentLlmLocalConfigLoader.class);
        AgentLlmProperties cfg = new AgentLlmProperties();
        cfg.getTools().getReread().setMaxLimit(8);
        cfg.getTools().getRawRef().setTtlHours(1);
        when(loader.current()).thenReturn(Optional.of(cfg));
        service = new ToolOutputRefServiceImpl(localStore, Optional.of(loader));
        AgentContext.clear();
        AgentContext.setRunId("run-1");
        AgentContext.setUserId("user-1");
    }

    @AfterEach
    void tearDown() {
        AgentContext.clear();
    }

    @Test
    void readKeywordShouldReturnHitWindowsWithOffsets() {
        // 014-opt-10：keyword 不再按行过滤，改为命中窗口——段头标注每个命中的
        // 原文偏移，重叠窗口合并成一段。本用例两个命中（下标 0 与 17）在半径
        // 内合并；测试配置 maxLimit=8 小于段文本长度，验证首段必出（同参数
        // 续查始终有前进）且不再做第二次截取。
        PersistentArtifactRegistration registration = service.registerRawOutput("tool-1", "工具输出",
                "alpha\nbeta\ngamma\nalphabet");

        ToolOutputReadResult result = service.read(registration.getArtifactId(), 0, 100, "alpha");

        assertEquals("@0 @17\nalpha\nbeta\ngamma\nalphabet", result.getContent());
        assertEquals(2, result.getHitCount());
        assertFalse(result.isHasMore());
        assertEquals(25, result.getTotalLength());
        assertEquals(25, result.getNextOffset());
    }

    // ===== 以下为 014-opt-10 命中窗口的服务层边界用例 =====

    /** 按需构造带 keywordWindowRadius/keywordWindowMaxHits/maxLimit 配置的服务实例（共享同一 localStore）。 */
    private ToolOutputRefServiceImpl serviceWithKeywordWindowConfig(Integer radius, Integer maxHits, Integer maxLimit) {
        AgentLlmLocalConfigLoader loader = mock(AgentLlmLocalConfigLoader.class);
        AgentLlmProperties cfg = new AgentLlmProperties();
        if (radius != null) {
            cfg.getTools().getReread().setKeywordWindowRadius(radius);
        }
        if (maxHits != null) {
            cfg.getTools().getReread().setKeywordWindowMaxHits(maxHits);
        }
        if (maxLimit != null) {
            cfg.getTools().getReread().setMaxLimit(maxLimit);
        }
        when(loader.current()).thenReturn(Optional.of(cfg));
        return new ToolOutputRefServiceImpl(localStore, Optional.of(loader));
    }

    @Test
    void readKeywordZeroHitShouldReturnHintTextAndFullLengthNextOffset() {
        // ① 零命中：返回 zeroHitMessage 提示文本，hitCount=0、hasMore=false、
        // nextOffset=全文长度（10），不再有续查
        PersistentArtifactRegistration registration = service.registerRawOutput("tool-1", "工具输出", "alpha\nbeta");

        ToolOutputReadResult result = service.read(registration.getArtifactId(), 0, 100, "zzz");

        assertEquals("关键词\"zzz\"未命中：全文共 10 字符。可更换关键词，或去掉 keyword 后按 offset 分段读取。",
                result.getContent());
        assertEquals(0, result.getHitCount());
        assertFalse(result.isHasMore());
        assertEquals(10, result.getNextOffset());
        assertEquals(10, result.getTotalLength());
    }

    @Test
    void readKeywordOffsetBeyondTotalShouldReturnZeroHitHint() {
        // ① 零命中的另一来源：offset（查找起点）越过全文末尾，检索范围为空
        PersistentArtifactRegistration registration = service.registerRawOutput("tool-1", "工具输出", "alpha");

        ToolOutputReadResult result = service.read(registration.getArtifactId(), 100, 100, "alpha");

        assertEquals("关键词\"alpha\"未命中：全文共 5 字符。可更换关键词，或去掉 keyword 后按 offset 分段读取。",
                result.getContent());
        assertEquals(0, result.getHitCount());
        assertFalse(result.isHasMore());
        assertEquals(5, result.getNextOffset());
    }

    @Test
    void readKeywordSmallLimitShouldDeferLaterSegmentsToNextCall() {
        // ④ charBudget 不足：limit 经 capLimit 变成预算，装不下整段的后续段留给
        // 下一次调用；预算缩到 1 时首段必出（超出预算仍返回）
        ToolOutputRefServiceImpl configured = serviceWithKeywordWindowConfig(3, 10, 100);
        PersistentArtifactRegistration registration = configured.registerRawOutput(
                "run-1", "user-1", "tool-1", "工具输出", "KW.......KW.......KW");

        // 预算 15：第一段（8 字符）返回，第二段 8+2+11=21 > 15 停止
        ToolOutputReadResult first = configured.read(registration.getArtifactId(), 0, 15, "KW");
        assertEquals("@0\nKW...", first.getContent());
        assertEquals(3, first.getHitCount());
        assertTrue(first.isHasMore());
        assertEquals(9, first.getNextOffset());

        // 预算 1（小于第一段长度）：首段必出，同参数续查始终有前进
        ToolOutputReadResult tiny = configured.read(registration.getArtifactId(), 0, 1, "KW");
        assertEquals("@0\nKW...", tiny.getContent());
        assertTrue(tiny.isHasMore());
        assertEquals(9, tiny.getNextOffset());

        // 从 nextOffset=9 续查：拿到第二段（预算 15 仍装不下第三段）
        ToolOutputReadResult second = configured.read(registration.getArtifactId(), 9, 15, "KW");
        assertEquals("@9\n...KW...", second.getContent());
        assertEquals(2, second.getHitCount());
        assertTrue(second.isHasMore());
        assertEquals(18, second.getNextOffset());

        // 从 18 续查：最后一段返回后 hasMore=false、nextOffset=全文长度
        ToolOutputReadResult third = configured.read(registration.getArtifactId(), 18, 15, "KW");
        assertEquals("@18\n...KW", third.getContent());
        assertEquals(1, third.getHitCount());
        assertFalse(third.isHasMore());
        assertEquals(20, third.getNextOffset());
    }

    @Test
    void readBlankOrNullKeywordShouldPageWithoutHitCount() {
        // ⑥ keyword 为空白串或 null：走非关键词分页分支（整段 offset/limit 截取，
        // 受 maxLimit=8 截顶），hitCount 为 null
        PersistentArtifactRegistration registration = service.registerRawOutput("tool-1", "工具输出", "0123456789");

        ToolOutputReadResult blank = service.read(registration.getArtifactId(), 0, 100, "");
        assertEquals("01234567", blank.getContent());
        assertTrue(blank.isHasMore());
        assertEquals(8, blank.getNextOffset());
        assertEquals(10, blank.getTotalLength());
        assertNull(blank.getHitCount());

        ToolOutputReadResult whitespace = service.read(registration.getArtifactId(), 0, 100, "   ");
        assertEquals("01234567", whitespace.getContent());
        assertNull(whitespace.getHitCount());

        ToolOutputReadResult tail = service.read(registration.getArtifactId(), 8, 100, null);
        assertEquals("89", tail.getContent());
        assertFalse(tail.isHasMore());
        assertEquals(10, tail.getNextOffset());
        assertNull(tail.getHitCount());
    }

    @Test
    void readKeywordTotalLengthShouldStayFullContentLength() {
        // ⑦ totalLength 恒为全文长度：窗口拼接文本（13 字符）远小于全文（126 字符），
        // totalLength 仍报全文长度
        ToolOutputRefServiceImpl configured = serviceWithKeywordWindowConfig(3, 10, 4000);
        PersistentArtifactRegistration registration = configured.registerRawOutput(
                "run-1", "user-1", "tool-1", "工具输出", "NEEDLE" + "x".repeat(120));

        ToolOutputReadResult result = configured.read(registration.getArtifactId(), 0, 4000, "NEEDLE");

        assertEquals("@0\nNEEDLExxx", result.getContent());
        assertEquals(12, result.getContent().length());
        assertEquals(126, result.getTotalLength());
        assertEquals(1, result.getHitCount());
        assertFalse(result.isHasMore());
        assertEquals(126, result.getNextOffset());
    }

    @Test
    void keywordWindowConfigShouldDriveRadiusAndMaxHits() {
        // ⑧ 配置生效：keywordWindowRadius=3 让两个间距 9 的命中分属两段（默认 200
        // 会并成一段）；keywordWindowMaxHits=2 让 3 个命中首次只回 2 个（hitCount
        // 仍报检索范围总数 3，nextOffset 指向第一个未返回命中 18），续查后收口
        ToolOutputRefServiceImpl configured = serviceWithKeywordWindowConfig(3, 2, 4000);
        PersistentArtifactRegistration registration = configured.registerRawOutput(
                "run-1", "user-1", "tool-1", "工具输出", "KW.......KW.......KW");

        ToolOutputReadResult first = configured.read(registration.getArtifactId(), 0, 100, "KW");
        assertEquals("@0\nKW...\n\n@9\n...KW...", first.getContent());
        assertEquals(3, first.getHitCount());
        assertTrue(first.isHasMore());
        assertEquals(18, first.getNextOffset());

        ToolOutputReadResult second = configured.read(registration.getArtifactId(), 18, 100, "KW");
        assertEquals("@18\n...KW", second.getContent());
        assertEquals(1, second.getHitCount());
        assertFalse(second.isHasMore());
        assertEquals(20, second.getNextOffset());
    }

    @Test
    void keywordWindowConfigMissingShouldFallBackToRadius200AndMaxHits20() {
        // ⑧ 配置缺失（字段未设置）回退默认 200/20：
        // 半径回退用窗口边界钉死 200——命中在 210，radius=200 时窗口恰为 [10,412)
        // （命中词前后各 200 个 y），199 会取 [11,...]、201 会取 [9,...]
        ToolOutputRefServiceImpl fallback = serviceWithKeywordWindowConfig(null, null, 4000);
        String radiusContent = "y".repeat(210) + "KW" + "y".repeat(210);
        PersistentArtifactRegistration radiusRef = fallback.registerRawOutput(
                "run-1", "user-1", "tool-1", "工具输出", radiusContent);

        ToolOutputReadResult radiusResult = fallback.read(radiusRef.getArtifactId(), 0, 4000, "KW");
        assertEquals("@210\n" + "y".repeat(200) + "KW" + "y".repeat(200), radiusResult.getContent());
        assertEquals(1, radiusResult.getHitCount());
        assertEquals(422, radiusResult.getTotalLength());

        // 命中数上限回退用数量钉死 20：21 个命中时第 21 个（下标 1200）留给续查
        String overflowContent = ("KW" + "z".repeat(58)).repeat(20) + "KW";
        PersistentArtifactRegistration overflowRef = fallback.registerRawOutput(
                "run-1", "user-1", "tool-2", "工具输出", overflowContent);

        ToolOutputReadResult overflow = fallback.read(overflowRef.getArtifactId(), 0, 4000, "KW");
        assertEquals(21, overflow.getHitCount());
        assertTrue(overflow.isHasMore());
        assertEquals(1200, overflow.getNextOffset());
        assertTrue(overflow.getContent().contains("@1140"), "第 20 个命中（下标 1140）应在本批返回");
        assertFalse(overflow.getContent().contains("@1200"), "第 21 个命中（下标 1200）不应在本批返回");

        // 20 个命中时恰好不截断（若回退值小于 20，这里会 hasMore=true）
        String exactContent = ("KW" + "z".repeat(58)).repeat(19) + "KW";
        PersistentArtifactRegistration exactRef = fallback.registerRawOutput(
                "run-1", "user-1", "tool-3", "工具输出", exactContent);

        ToolOutputReadResult exact = fallback.read(exactRef.getArtifactId(), 0, 4000, "KW");
        assertEquals(20, exact.getHitCount());
        assertFalse(exact.isHasMore());
        assertEquals(exactContent.length(), exact.getNextOffset());
    }

    @Test
    void keywordWindowNonPositiveConfigShouldFallBackToDefaults() {
        // ⑧ 配置非正数（radius=0、maxHits=-5）：与缺失同款回退 200/20——
        // 半径按 200 取窗口；maxHits 按 20 截断而不是把负数当上限
        ToolOutputRefServiceImpl configured = serviceWithKeywordWindowConfig(0, -5, 4000);
        PersistentArtifactRegistration radiusRef = configured.registerRawOutput(
                "run-1", "user-1", "tool-1", "工具输出", "y".repeat(210) + "KW" + "y".repeat(210));

        ToolOutputReadResult radiusResult = configured.read(radiusRef.getArtifactId(), 0, 4000, "KW");
        assertEquals("@210\n" + "y".repeat(200) + "KW" + "y".repeat(200), radiusResult.getContent());

        PersistentArtifactRegistration overflowRef = configured.registerRawOutput(
                "run-1", "user-1", "tool-2", "工具输出", ("KW" + "z".repeat(58)).repeat(20) + "KW");

        ToolOutputReadResult overflow = configured.read(overflowRef.getArtifactId(), 0, 4000, "KW");
        assertEquals(21, overflow.getHitCount());
        assertTrue(overflow.isHasMore());
        assertEquals(1200, overflow.getNextOffset());
    }

    @Test
    void readShouldRejectCrossRunRawRef() {
        PersistentArtifactRegistration registration = service.registerRawOutput("tool-1", "工具输出", "secret");

        AgentContext.setRunId("run-2");

        assertThrows(IllegalArgumentException.class,
                () -> service.read(registration.getArtifactId(), 0, 10, null));
    }

    @Test
    void rebindFromLocatorShouldWorkWithinCurrentRun() {
        // 同 Run 内 rebind（AgentContext 不变）：允许，重绑定后内容一致
        PersistentArtifactRegistration first = service.registerRawOutput("tool-1", "工具输出", "payload");
        RawPayloadLocator locator = service.locatorFor(first.getArtifactId());

        PersistentArtifactRegistration rebound = service.rebindFromLocator("tool-1", "工具输出", locator);

        assertEquals("payload", service.read(rebound.getArtifactId(), 0, 100, null).getContent());
        assertEquals("payload",
                service.read("run-1", "user-1", first.getArtifactId(), 0, 100, null).getContent());
    }

    @Test
    void rebindFromLocatorShouldRejectCrossRun() {
        // review MUST-FIX：跨 Run rebind 破坏 Run 隔离（缓存 locator 会让别的
        // Run 读到来源 Run 的工具输出），必须 fail-closed。
        PersistentArtifactRegistration first = service.registerRawOutput("tool-1", "工具输出", "payload");
        RawPayloadLocator locator = service.locatorFor(first.getArtifactId());
        AgentContext.setRunId("run-2");

        assertThrows(IllegalArgumentException.class,
                () -> service.rebindFromLocator("tool-1", "工具输出", locator));
    }

    @Test
    void rebindFromLocatorShouldRejectWhenLocatorCleaned() {
        // review MUST-FIX：来源 Run 终态清理后 locator 失效，rebind 必须
        // fail-closed（上层缓存服务据此删缓存回源）。
        PersistentArtifactRegistration first = service.registerRawOutput("tool-1", "工具输出", "payload");
        RawPayloadLocator locator = service.locatorFor(first.getArtifactId());
        localStore.cleanupRun("run-1");

        assertThrows(IllegalArgumentException.class,
                () -> service.rebindFromLocator("tool-1", "工具输出", locator));
    }

    @Test
    void explicitContextOverloadsShouldBypassAgentContext() {
        // 显式 overload 不读 AgentContext——线程态是别的 run 也能注册/读取目标 run。
        PersistentArtifactRegistration registration =
                service.registerRawOutput("run-x", "user-x", "tool-x", "工具输出", "explicit-payload");
        String rawRef = registration.getArtifactId();

        // 当前线程态仍是 run-1/user-1：显式 overload 读取 run-x 不受影响
        // （setUp maxLimit=8 截顶：16 字符 payload 只返回前 8 字符，hasMore=true）
        ToolOutputReadResult explicitRead = service.read("run-x", "user-x",
                rawRef, 0, 100, null);
        assertEquals("explicit", explicitRead.getContent());
        assertTrue(explicitRead.isHasMore());
        // 旧入口语义不变：AgentContext(run-1) 读 run-x 的 ref 仍被拒
        assertThrows(IllegalArgumentException.class,
                () -> service.read(rawRef, 0, 10, null));
    }

    @Test
    void explicitReadShouldRejectWhenCallerContextMissing() {
        // 显式入口严格校验——调用方任一值为空即拒（fail-closed）
        PersistentArtifactRegistration registration =
                service.registerRawOutput("run-x", "user-x", "tool-x", "工具输出", "payload");
        String rawRef = registration.getArtifactId();

        assertThrows(IllegalArgumentException.class,
                () -> service.read(null, "user-x", rawRef, 0, 100, null));
        assertThrows(IllegalArgumentException.class,
                () -> service.read("run-x", " ", rawRef, 0, 100, null));
        assertThrows(IllegalArgumentException.class,
                () -> service.locatorFor(null, "user-x", rawRef));
    }

    @Test
    void registerShouldFailClosedWithoutContext() {
        // 260814 scheduler-03 语义变化：旧版允许注册出"无上下文制品"（meta 的
        // runId/userId 为空，读取时再拒）；本地存储版在注册源头就拒绝空上下文，
        // 不再产生任何无主制品。
        AgentContext.clear();
        assertThrows(IllegalArgumentException.class,
                () -> service.registerRawOutput("tool-legacy", "旧输出", "legacy-payload"));
        assertThrows(IllegalArgumentException.class,
                () -> service.registerRawOutput(" ", "user-1", "tool-1", "输出", "payload"));
        assertThrows(IllegalArgumentException.class,
                () -> service.registerRawOutput("run-1", " ", "tool-1", "输出", "payload"));
    }

    @Test
    void registerShouldReturnUnguessableRefWithLocatorPath() {
        PersistentArtifactRegistration registration =
                service.registerRawOutput("tool-1", "工具输出", "payload");
        String ref = registration.getArtifactId();

        // 压缩流程走不可猜测引用（UUID 风格），而不是顺序短 ID（raw_ref_001）。
        assertTrue(ref.startsWith("raw_"), "ref 应为 raw_ 前缀: " + ref);
        assertFalse(ref.startsWith("raw_ref_"), "服务层不应发放顺序短 ID: " + ref);
        assertEquals(ref, registration.getLocator().getPath());
        assertEquals(ref, service.locatorFor(ref).getPath());
    }

    @Test
    void readShouldReturnFullContentWithoutKeyword() {
        // 内容取 7 字符（≤ maxLimit=8），验证不截断的完整读回
        PersistentArtifactRegistration registration =
                service.registerRawOutput("tool-1", "工具输出", "payload");

        ToolOutputReadResult result = service.read(registration.getArtifactId(), 0, 100, null);

        assertEquals("payload", result.getContent());
        assertFalse(result.isHasMore());
        assertEquals(7, result.getNextOffset());
        assertEquals(7, result.getTotalLength());
    }

    @Test
    void readOffsetBeyondEndShouldReturnEmpty() {
        PersistentArtifactRegistration registration =
                service.registerRawOutput("tool-1", "工具输出", "abc");

        ToolOutputReadResult result = service.read(registration.getArtifactId(), 100, 10, null);

        assertEquals("", result.getContent());
        assertFalse(result.isHasMore());
        assertEquals(3, result.getNextOffset());
    }
}
