package world.willfrog.agent.tools.finance;

import java.util.Optional;

/**
 * 提供当前运行时可信的目标执行环境清单。
 *
 * <p>实现由组合侧（例如 {@code FinanceRecordChannelConfigLoader.Snapshot} 适配）注入；
 * 本接口只读取，模型与调用参数永远不可伪造。</p>
 */
public interface FinanceTargetEnvironmentProvider {

    /**
     * 返回当前目标环境；若不存在则返回空 Optional。
     */
    Optional<FinanceMethodSuggestionRenderer.TargetEnvironment> currentTargetEnvironment();
}
