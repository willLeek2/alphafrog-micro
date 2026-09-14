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
    void readShouldPageAndFilterWithinCurrentRun() {
        PersistentArtifactRegistration registration = service.registerRawOutput("tool-1", "工具输出",
                "alpha\nbeta\ngamma\nalphabet");

        ToolOutputReadResult result = service.read(registration.getArtifactId(), 0, 100, "alpha");

        assertEquals("alpha\nal", result.getContent());
        assertTrue(result.isHasMore());
        assertEquals(8, result.getNextOffset());
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
