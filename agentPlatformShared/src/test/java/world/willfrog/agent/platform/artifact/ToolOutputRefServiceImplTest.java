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











}
