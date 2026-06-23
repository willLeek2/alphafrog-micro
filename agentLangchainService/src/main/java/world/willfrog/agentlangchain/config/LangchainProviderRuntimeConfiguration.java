package world.willfrog.agentlangchain.config;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import world.willfrog.agent.tools.AgentToolsAutoConfiguration;
import world.willfrog.agent.workflow.AgentRunDatasetRegistry;
import world.willfrog.alphafrogmicro.common.config.nacos.NacosConfigBridge;

@Configuration
@ConditionalOnProperty(prefix = "agent.langchain.provider", name = "enabled", havingValue = "true")
@EnableDubbo(scanBasePackages = "world.willfrog.agentlangchain")
@EnableScheduling
@EnableAsync
@MapperScan({
        "world.willfrog.agent.platform.mapper",
        "world.willfrog.alphafrogmicro.common.dao"
})
@ComponentScan(basePackages = "world.willfrog.agent.platform")
@Import({AgentToolsAutoConfiguration.class, NacosConfigBridge.class})
public class LangchainProviderRuntimeConfiguration {

    /**
     * 260623-agent-service-deprecation task #47 (P0-1)：
     * agentToolsShared 通过 {@link AgentToolsAutoConfiguration} 拉进 {@code world.willfrog.agent.tools} 包，
     * {@link AgentRunDatasetRegistry} 位于 {@code world.willfrog.agent.workflow}（agentPlatformShared），
     * 不在本配置类 {@code @ComponentScan(basePackages = "world.willfrog.agent.platform")} 覆盖范围，
     * 也不在 AgentToolsAutoConfiguration 的 {@code world.willfrog.agent.tools} 扫描范围内。
     * 若不显式注册 bean，{@link world.willfrog.agent.tools.python.PythonSandboxTools} 和
     * {@link world.willfrog.agent.tools.dataset.ListMyDataTool} 的
     * {@code @Autowired(required=false) AgentRunDatasetRegistry} 启动时静默 null，
     * executePython / listMyData 运行时抛 {@code RUN_LEVEL_IDS_UNAVAILABLE}。
     *
     * <p>此处显式 {@code @Bean} 而非扩大 {@code @ComponentScan}：
     * {@code world.willfrog.agent.workflow} 包下还包含 agentService 专用的
     * {@code TodoItem} / {@code TodoStatus} / {@code WorkflowState} / {@code PlanExecutionMode} /
     * {@code TodoPlanner} / {@code WorkflowExecutor} 等类，扩大 scan 会把这些无关 bean 也拉进来，
     * 破坏 agentLangchainService 与 agentService 的职责边界（task #47 验收口径）。
     */
    @Bean
    public AgentRunDatasetRegistry agentRunDatasetRegistry() {
        return new AgentRunDatasetRegistry();
    }
}
