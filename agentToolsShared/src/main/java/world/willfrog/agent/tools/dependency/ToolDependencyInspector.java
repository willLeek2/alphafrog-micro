package world.willfrog.agent.tools.dependency;

import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.spring.ReferenceBean;
import org.apache.dubbo.config.spring.reference.ReferenceBeanManager;
import org.apache.dubbo.rpc.Invoker;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.tools.registry.AgentToolRegistry.Dependency;
import world.willfrog.agent.workflow.PlanDependencyCheckReport.Basis;
import world.willfrog.agent.workflow.PlanDependencyCheckReport.Status;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** 只读取进程已有对象，不创建引用、不初始化客户端，也不发出业务请求。 */
@Component
public class ToolDependencyInspector {
    private final ConfigurableListableBeanFactory beanFactory;

    public ToolDependencyInspector(ConfigurableListableBeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    public Inspection inspect(Dependency dependency) {
        try {
            if (dependency.interfaceName() != null) {
                return inspectReference(dependency);
            }
            return switch (dependency) {
                case ADJ_FACTOR_CONFIG -> inspectAdjFactorConfig();
                case MARKET_TARGET -> unchecked("需要执行时的资产类型或查询条件才能确定完整目标；当前未检查。");
                case INDUSTRY_DATABASE -> unchecked("当前没有复用的行业数据库只读检查入口；数据库查询未检查。");
                case SEARCH_BACKEND -> unchecked("后端配置位于外部信息服务；搜索或检索业务未检查。");
                case SANDBOX_EXECUTOR -> unchecked("当前没有实际沙箱执行器的只读检查入口；真实执行能力未检查。");
                case DOCUMENT_TARGET -> unchecked("文档地址需要执行参数才能确定；当前未检查。");
                default -> unchecked("当前没有这项运行条件的检查依据；未检查。");
            };
        } catch (Exception ignored) {
            // 异常正文可能含服务地址和配置，用户报告只保留固定说明。
            return unchecked("读取已有对象时发生异常；未检查。");
        }
    }

    @SuppressWarnings("deprecation")
    private Inspection inspectReference(Dependency dependency) {
        Object existing = beanFactory.getSingleton(ReferenceBeanManager.BEAN_NAME);
        if (!(existing instanceof ReferenceBeanManager manager)) {
            return unchecked("当前没有已初始化的引用管理器；未检查。");
        }
        ReferenceConfig<?> configuration;
        if (dependency.referenceBeanName() != null) {
            ReferenceBean<?> reference = manager.getById(dependency.referenceBeanName());
            configuration = reference == null ? null : reference.getReferenceConfig();
            if (configuration == null || !dependency.interfaceName().equals(configuration.getInterface())) {
                return unchecked("指定引用尚未初始化或接口不匹配；未检查。");
            }
        } else {
            Set<ReferenceConfig<?>> candidates = Collections.newSetFromMap(new IdentityHashMap<>());
            for (ReferenceBean<?> reference : manager.getReferences()) {
                ReferenceConfig<?> candidate = reference.getReferenceConfig();
                if (candidate != null && dependency.interfaceName().equals(candidate.getInterface())) {
                    candidates.add(candidate);
                }
            }
            if (candidates.size() != 1) {
                return unchecked(candidates.isEmpty()
                        ? "没有唯一可读的已有引用；未检查。"
                        : "存在多个不同的实际引用目标；未检查。");
            }
            configuration = candidates.iterator().next();
        }
        Invoker<?> invoker = configuration.getInvoker();
        if (invoker == null) {
            return unchecked("实际引用尚无已初始化的调用器；未检查。");
        }
        boolean available = invoker.isAvailable();
        return new Inspection(available ? Status.AVAILABLE : Status.UNAVAILABLE,
                Basis.REFERENCE_STATE, available
                ? "服务引用当前报告可用；回源业务仍需以实际调用结果为准。"
                : "服务引用当前报告不可用；该步骤回源可能受影响，已有缓存仍可能可用。");
    }

    private Inspection inspectAdjFactorConfig() {
        AgentLlmLocalConfigLoader loader = existingUniqueBean(AgentLlmLocalConfigLoader.class);
        Boolean enabled = loader == null ? null : loader.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getExecution)
                .map(AgentLlmProperties.Execution::getAdjFactorEnabled)
                .orElse(null);
        if (enabled == null) {
            AgentLlmProperties properties = existingUniqueBean(AgentLlmProperties.class);
            if (properties == null) {
                return unchecked("没有可读的现有复权因子配置；未检查。");
            }
            enabled = properties.getRuntime() != null && properties.getRuntime().getExecution() != null
                    && Boolean.TRUE.equals(properties.getRuntime().getExecution().getAdjFactorEnabled());
        }
        return new Inspection(enabled ? Status.AVAILABLE : Status.UNAVAILABLE, Basis.CONFIG_PRESENT,
                enabled ? "本地复权因子查询开关已启用；业务调用未检查。"
                        : "本地复权因子查询开关未启用；该工具调用会受影响。");
    }

    private <T> T existingUniqueBean(Class<T> type) {
        Set<T> found = Collections.newSetFromMap(new IdentityHashMap<>());
        for (String name : beanFactory.getSingletonNames()) {
            Object instance = beanFactory.getSingleton(name);
            if (type.isInstance(instance)) {
                found.add(type.cast(instance));
            }
        }
        return found.size() == 1 ? found.iterator().next() : null;
    }

    public static Inspection unchecked(String reason) {
        return new Inspection(Status.UNCHECKED, Basis.NONE, reason);
    }

    public record Inspection(Status status, Basis basis, String reason) {
    }
}
