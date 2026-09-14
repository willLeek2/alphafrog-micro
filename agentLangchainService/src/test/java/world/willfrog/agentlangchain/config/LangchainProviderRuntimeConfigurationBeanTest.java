package world.willfrog.agentlangchain.config;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.workflow.AgentRunDatasetRegistry;
import world.willfrog.agent.workflow.DatasetPersistedEvent;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 260623-agent-service-deprecation task #47 (P0-1)：
 * {@link LangchainProviderRuntimeConfiguration#agentRunDatasetRegistry()} 必须在
 * agentLangchainService profile 启用时显式提供 {@link AgentRunDatasetRegistry} bean，
 * 否则 {@code PythonSandboxTools} / {@code ListMyDataTool} 的
 * {@code @Autowired(required=false)} 启动时静默 null，运行时抛 {@code RUN_LEVEL_IDS_UNAVAILABLE}
 * （task #45 压测 39/39 全失败直接根因，见 exec B audit 报告 §5）。
 *
 * <p>本测试不启动 Spring 容器（{@code LangchainProviderRuntimeConfiguration} 上挂的
 * {@code @EnableDubbo} / {@code @MapperScan} / {@code @Import(NacosConfigBridge.class)} 等
 * 会拉进整套中间件连接）。通过直接调用 {@code @Bean} 方法验证：
 * <ol>
 *   <li>返回值非 null</li>
 *   <li>类型正确</li>
 *   <li>多次调用返回不同实例（与 {@code @Bean} 默认 singleton 行为一致——
 *       Spring 容器内只会注册第一次返回的实例，重复调用验证工厂方法本身是无副作用的）</li>
 * </ol>
 *
 * <p>Spring 容器内的 singleton 行为由 {@code @Bean} 容器管理保证，不是工厂方法本身的责任；
 * 容器级验证不在本测试范围。
 */
class LangchainProviderRuntimeConfigurationBeanTest {

    @Test
    void agentRunDatasetRegistryBeanShouldReturnNonNullRegistry() {
        LangchainProviderRuntimeConfiguration config = new LangchainProviderRuntimeConfiguration();
        AgentRunDatasetRegistry registry = config.agentRunDatasetRegistry();
        assertNotNull(registry, "@Bean factory must return non-null AgentRunDatasetRegistry");
    }

    @Test
    void agentRunDatasetRegistryBeanShouldReturnIndependentInstances() {
        // 工厂方法被多次调用应返回不同实例（factory 每次 new AgentRunDatasetRegistry()）。
        // Spring 容器内由 @Bean singleton 保证只有一个实例被注册 — 那是容器责任，不是工厂方法责任。
        LangchainProviderRuntimeConfiguration config = new LangchainProviderRuntimeConfiguration();
        AgentRunDatasetRegistry a = config.agentRunDatasetRegistry();
        AgentRunDatasetRegistry b = config.agentRunDatasetRegistry();
        assertNotNull(a);
        assertNotNull(b);
        assertNotSame(a, b, "@Bean factory must not cache — each invocation should produce a fresh registry");
    }

    @Test
    void agentRunDatasetRegistryBeanShouldBeReadyToReceiveEvents() {
        // 工厂方法返回的 registry 必须立刻可消费 DatasetPersistedEvent
        // （与 agentToolsShared 内 DatasetRegistry 发布事件链路保持一致）
        LangchainProviderRuntimeConfiguration config = new LangchainProviderRuntimeConfiguration();
        AgentRunDatasetRegistry registry = config.agentRunDatasetRegistry();

        DatasetPersistedEvent event = new DatasetPersistedEvent(this, "run-1", "ds-a",
                "/data/domestic_listed_asset/600000.SH/ds-a/a.csv", "600000.SH", "a.csv");
        registry.onDatasetPersisted(event);

        assertTrue(registry.hasRunState("run-1"),
                "factory-returned registry must immediately accept DatasetPersistedEvent");
    }
}