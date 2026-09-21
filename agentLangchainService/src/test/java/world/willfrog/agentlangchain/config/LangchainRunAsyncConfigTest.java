package world.willfrog.agentlangchain.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import world.willfrog.agentlangchain.control.dualpool.DualPoolSchedulerSettings;
import world.willfrog.agentlangchain.control.dualpool.FrozenEffectiveSettings;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 双池两个线程池建好之后，把自己真正在用的值登记到读数里。
 *
 * <p>健康读数要是去读属性源，看到的是请求值：{@code core=8, max=2} 会显示成 8/2，而线程池其实是
 * 2/2；负的存活秒数会被抬到 0，空线程名前缀会换成默认名。验收与排查读的就是读数，所以这里量的是
 * 「登记进来的等于构造完之后字段里的那个数」。</p>
 */
class LangchainRunAsyncConfigTest {

    @Test
    void theCoordinationPoolRegistersTheValuesItActuallyUses() {
        FrozenEffectiveSettings inUse = new FrozenEffectiveSettings();

        ThreadPoolTaskExecutor executor = new LangchainRunAsyncConfig()
                .agentLangchainRunCoordinationTaskExecutor(inUse, 8, 2, -5, " ");

        assertThat(executor.getCorePoolSize()).as("核心线程数不超过上限").isEqualTo(2);
        assertThat(executor.getMaxPoolSize()).isEqualTo(2);
        assertThat(executor.getKeepAliveSeconds()).as("负的存活秒数抬到 0").isZero();
        assertThat(inUse.inUseBy(DualPoolSchedulerSettings.KEY_RUN_WORKER_CORE_POOL_SIZE))
                .containsEntry("agentLangchainRunCoordinationTaskExecutor", 2);
        assertThat(inUse.inUseBy(DualPoolSchedulerSettings.KEY_RUN_WORKER_MAX_POOL_SIZE))
                .containsEntry("agentLangchainRunCoordinationTaskExecutor", 2);
        assertThat(inUse.inUseBy(DualPoolSchedulerSettings.KEY_RUN_WORKER_KEEP_ALIVE_SECONDS))
                .containsEntry("agentLangchainRunCoordinationTaskExecutor", 0);
        assertThat(inUse.inUseBy(DualPoolSchedulerSettings.KEY_RUN_WORKER_THREAD_NAME_PREFIX))
                .as("空名字换成默认前缀，登记的是换完之后那个")
                .containsEntry("agentLangchainRunCoordinationTaskExecutor", "agent-dual-pool-");
    }

    @Test
    void theNodePoolRegistersTheValuesItActuallyUses() {
        FrozenEffectiveSettings inUse = new FrozenEffectiveSettings();

        ThreadPoolTaskExecutor executor = new LangchainRunAsyncConfig()
                .agentLangchainNodeTaskExecutor(inUse, 0, 0, 60, "agent-node-");

        assertThat(executor.getCorePoolSize()).as("至少要有一个线程，否则节点池不干活").isEqualTo(1);
        assertThat(executor.getMaxPoolSize()).isEqualTo(1);
        assertThat(inUse.inUseBy(DualPoolSchedulerSettings.KEY_NODE_WORKER_CORE_POOL_SIZE))
                .containsEntry("agentLangChainNodeTaskExecutor", 1);
        assertThat(inUse.inUseBy(DualPoolSchedulerSettings.KEY_NODE_WORKER_MAX_POOL_SIZE))
                .containsEntry("agentLangChainNodeTaskExecutor", 1);
        assertThat(inUse.inUseBy(DualPoolSchedulerSettings.KEY_NODE_WORKER_KEEP_ALIVE_SECONDS))
                .containsEntry("agentLangChainNodeTaskExecutor", 60);
        assertThat(inUse.inUseBy(DualPoolSchedulerSettings.KEY_NODE_WORKER_THREAD_NAME_PREFIX))
                .containsEntry("agentLangChainNodeTaskExecutor", "agent-node-");
    }
}
