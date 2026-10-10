package world.willfrog.agent.platform.observability;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;

/**
 * 结构化可观测事件上报开关的装配（{@link ObservabilityEvents} 的开关就读这里）。
 *
 * <p>取值顺序与双池调度参数一致：热配置 → 环境属性 → 代码默认。热配置认
 * {@code runtime.observabilityEvents.enabled}（Nacos 推送的 {@code agent-llm.local.json}，
 * 泳道覆盖只影响该泳道）；环境属性认 {@link #ENABLED_PROPERTY}；两层都没写时关闭。</p>
 *
 * <p>装配发生在 bean 创建时；之后每次上报都会重新取热配置，所以泳道改完配置下一轮上报就生效，
 * 不需要重启。没有这个组件时（单测，或没扫到本包的服务）上报保持关闭。</p>
 */
@Component
public class ObservabilityEventsSwitch {

    /** 环境属性层的键；与热配置的 {@code runtime.observabilityEvents.enabled} 等价。 */
    public static final String ENABLED_PROPERTY = "agent.observability.events.enabled";

    private final ObjectProvider<AgentLlmLocalConfigLoader> hotConfigProvider;
    private final Environment environment;

    public ObservabilityEventsSwitch(ObjectProvider<AgentLlmLocalConfigLoader> hotConfigProvider,
                                     Environment environment) {
        this.hotConfigProvider = hotConfigProvider;
        this.environment = environment;
        ObservabilityEvents.installSwitch(this::enabledNow);
    }

    /** 当前是否开启：热配置优先，其次环境属性，最后代码默认（关）。 */
    public boolean enabledNow() {
        Boolean hot = hotValue();
        if (hot != null) {
            return hot;
        }
        Boolean configured = environment == null ? null
                : environment.getProperty(ENABLED_PROPERTY, Boolean.class);
        return configured != null && configured;
    }

    /** 热配置里那一段的开关值；没有加载器、没配或读不出来时返回 null，按「没配」回落下一层。 */
    private Boolean hotValue() {
        AgentLlmLocalConfigLoader hotConfig = hotConfigProvider == null ? null : hotConfigProvider.getIfAvailable();
        if (hotConfig == null) {
            return null;
        }
        return hotConfig.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getObservabilityEvents)
                .map(AgentLlmProperties.ObservabilityEventsSettings::getEnabled)
                .orElse(null);
    }
}
