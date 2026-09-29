package world.willfrog.agent.tools.sandboxjob;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 沙箱后台长工具的适配器注册表。
 *
 * <p>各工具的实现类在启动时调用 {@link #register} 登记自己的适配器束；框架与
 * 路由层按工具名查询。登记发生在工具 Bean 的构造或初始化阶段，运行时只读。</p>
 */
public final class SandboxJobAdapterRegistry {

    private static final Map<String, SandboxJobAdapters> ADAPTERS = new ConcurrentHashMap<>();

    private SandboxJobAdapterRegistry() {}

    public static void register(SandboxJobAdapters adapters) {
        String toolName = adapters.toolName();
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("适配器束缺少工具名");
        }
        // 同名重复登记说明接线出错，直接失败而不是静默覆盖。
        SandboxJobAdapters existing = ADAPTERS.putIfAbsent(toolName, adapters);
        if (existing != null && existing != adapters) {
            throw new IllegalStateException("工具 " + toolName + " 的适配器束被重复登记");
        }
    }

    public static Optional<SandboxJobAdapters> find(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(ADAPTERS.get(toolName));
    }

    /** 测试用：清空注册表。生产代码不得调用。 */
    public static void clearForTests() {
        ADAPTERS.clear();
    }
}
