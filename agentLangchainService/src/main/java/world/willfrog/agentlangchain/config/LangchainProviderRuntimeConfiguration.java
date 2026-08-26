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
     * 为 {@link AgentRunDatasetRegistry} 显式注册 bean：它位于
     * {@code world.willfrog.agent.workflow}（agentPlatformShared），不在本配置类
     * {@code @ComponentScan(basePackages = "world.willfrog.agent.platform")} 的覆盖范围内，
     * 也不在 {@link AgentToolsAutoConfiguration} 的 {@code world.willfrog.agent.tools}
     * 扫描范围内，自动扫描不会创建它。
     *
     * <p>缺少这个 bean 时，{@link world.willfrog.agent.tools.python.PythonSandboxTools} 和
     * {@link world.willfrog.agent.tools.dataset.ListMyDataTool} 里
     * {@code @Autowired(required=false) AgentRunDatasetRegistry} 会在启动时静默保持 null，
     * executePython / listMyData 运行时抛 {@code RUN_LEVEL_IDS_UNAVAILABLE}。</p>
     *
     * <p>扩大 {@code @ComponentScan} 的做法会把 {@code world.willfrog.agent.workflow}
     * 包下 agentService 专用的 {@code TodoItem} / {@code TodoStatus} / {@code WorkflowState} /
     * {@code PlanExecutionMode} / {@code TodoPlanner} / {@code WorkflowExecutor} 等类一并
     * 拉进来，打乱 agentLangchainService 与 agentService 的职责划分，因此这里显式注册
     * 单个 bean。</p>
     */
    @Bean
    public AgentRunDatasetRegistry agentRunDatasetRegistry() {
        return new AgentRunDatasetRegistry();
    }
}
