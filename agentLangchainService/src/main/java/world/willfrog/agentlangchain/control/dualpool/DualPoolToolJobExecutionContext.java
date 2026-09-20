package world.willfrog.agentlangchain.control.dualpool;

import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;
import world.willfrog.agent.platform.workitem.NodeWorkItemVersions;

/**
 * 双池节点调用长工具前冻结的持久身份。
 *
 * <p>executePython 会在真正调用 Sandbox 之前把这组身份复制到工具锚点。进程如果在外部调用前后
 * 退出，启动恢复可以只凭数据库里的锚点找到原工作项，不需要依赖旧线程或内存提示。</p>
 */
public final class DualPoolToolJobExecutionContext {

    private static final ThreadLocal<Snapshot> CURRENT = new ThreadLocal<>();

    private DualPoolToolJobExecutionContext() {
    }

    public static Scope install(NodeWorkItemIdentity identity,
                                NodeWorkItemVersions versions,
                                String claimant,
                                String payloadJson) {
        Snapshot previous = CURRENT.get();
        CURRENT.set(new Snapshot(identity, versions, claimant, payloadJson));
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    public static Snapshot current() {
        return CURRENT.get();
    }

    public record Snapshot(NodeWorkItemIdentity identity,
                           NodeWorkItemVersions versions,
                           String claimant,
                           String payloadJson) {
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
