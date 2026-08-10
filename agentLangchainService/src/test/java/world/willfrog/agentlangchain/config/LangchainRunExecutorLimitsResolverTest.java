package world.willfrog.agentlangchain.config;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LangchainRunExecutorLimitsResolverTest {

    @Test
    void currentLimits_shouldDefaultToHardWhenParallelCurrentMissing() {
        AgentLlmProperties cfg = new AgentLlmProperties();
        AgentLlmProperties.ExecutorConfig executor = new AgentLlmProperties.ExecutorConfig();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(50);

        AgentLlmProperties.ExecutorParallelConfig parallel = new AgentLlmProperties.ExecutorParallelConfig();
        AgentLlmProperties.ExecutorConfig hard = new AgentLlmProperties.ExecutorConfig();
        hard.setCorePoolSize(100);
        hard.setMaxPoolSize(100);
        hard.setQueueCapacity(1000);
        parallel.setHard(hard);
        executor.setParallel(parallel);
        cfg.setExecutor(executor);

        AgentLlmLocalConfigLoader loader = mock(AgentLlmLocalConfigLoader.class);
        when(loader.current()).thenReturn(Optional.of(cfg));

        LangchainRunExecutorLimitsResolver resolver =
                new LangchainRunExecutorLimitsResolver(1, 1, 1, "default-", loader);

        assertThat(resolver.hardLimits().getCorePoolSize()).isEqualTo(100);
        assertThat(resolver.currentLimits().getCorePoolSize()).isEqualTo(100);
        assertThat(resolver.currentLimits().getQueueCapacity()).isEqualTo(1000);
    }

    @Test
    void currentLimits_shouldClampToHardGate() {
        AgentLlmProperties cfg = new AgentLlmProperties();
        AgentLlmProperties.ExecutorConfig executor = new AgentLlmProperties.ExecutorConfig();
        AgentLlmProperties.ExecutorParallelConfig parallel = new AgentLlmProperties.ExecutorParallelConfig();

        AgentLlmProperties.ExecutorConfig hard = new AgentLlmProperties.ExecutorConfig();
        hard.setCorePoolSize(10);
        hard.setMaxPoolSize(20);
        hard.setQueueCapacity(100);
        parallel.setHard(hard);

        AgentLlmProperties.ExecutorConfig current = new AgentLlmProperties.ExecutorConfig();
        current.setCorePoolSize(30);
        current.setMaxPoolSize(40);
        current.setQueueCapacity(200);
        parallel.setCurrent(current);
        executor.setParallel(parallel);
        cfg.setExecutor(executor);

        AgentLlmLocalConfigLoader loader = mock(AgentLlmLocalConfigLoader.class);
        when(loader.current()).thenReturn(Optional.of(cfg));

        LangchainRunExecutorLimitsResolver resolver =
                new LangchainRunExecutorLimitsResolver(1, 1, 1, "default-", loader);

        assertThat(resolver.currentLimits().getCorePoolSize()).isEqualTo(10);
        assertThat(resolver.currentLimits().getMaxPoolSize()).isEqualTo(20);
        assertThat(resolver.currentLimits().getQueueCapacity()).isEqualTo(100);
    }

    // ===== D03: getHardVersusEffectiveGap =====

    @Test
    void gap_requestedBelowHard_allDimensionsNotClamped() {
        AgentLlmProperties cfg = configWithHardAndCurrent(
                100, 100, 1000, "prefix-hard-",
                10, 20, 100, "prefix-current-");

        AgentLlmLocalConfigLoader loader = mock(AgentLlmLocalConfigLoader.class);
        when(loader.current()).thenReturn(Optional.of(cfg));

        LangchainRunExecutorLimitsResolver resolver =
                new LangchainRunExecutorLimitsResolver(1, 1, 1, "default-", loader);

        Map<String, Object> gap = resolver.getHardVersusEffectiveGap();

        // prefix: "prefix-current-" vs "prefix-hard-" 不同 → restartRequired=true
        assertThat(gap.get("restartRequired")).isEqualTo(true);

        @SuppressWarnings("unchecked")
        Map<String, Object> dims = (Map<String, Object>) gap.get("dimensions");

        // core: requested(10) < hard(100) → clamped=false
        @SuppressWarnings("unchecked")
        Map<String, Object> core = (Map<String, Object>) dims.get("corePoolSize");
        assertThat(core.get("hard")).isEqualTo(100);
        assertThat(core.get("requested")).isEqualTo(10);
        assertThat(core.get("effective")).isEqualTo(10);
        assertThat(core.get("clamped")).isEqualTo(false);

        // max: requested(20) < hard(100) → clamped=false
        @SuppressWarnings("unchecked")
        Map<String, Object> max = (Map<String, Object>) dims.get("maxPoolSize");
        assertThat(max.get("clamped")).isEqualTo(false);

        // queue: requested(100) < hard(1000) → clamped=false
        @SuppressWarnings("unchecked")
        Map<String, Object> queue = (Map<String, Object>) dims.get("queueCapacity");
        assertThat(queue.get("clamped")).isEqualTo(false);

        // prefix: requested != hard → clamped=true（需重启）
        @SuppressWarnings("unchecked")
        Map<String, Object> prefix = (Map<String, Object>) dims.get("threadNamePrefix");
        assertThat(prefix.get("hard")).isEqualTo("prefix-hard-");
        assertThat(prefix.get("requested")).isEqualTo("prefix-current-");
        assertThat(prefix.get("effective")).isEqualTo("prefix-hard-"); // 启动冻结
        assertThat(prefix.get("clamped")).isEqualTo(true);
    }

    @Test
    void gap_requestedEqualsHard_notClamped() {
        AgentLlmProperties cfg = configWithHardAndCurrent(
                10, 20, 100, "agent-run-",
                10, 20, 100, "agent-run-");

        AgentLlmLocalConfigLoader loader = mock(AgentLlmLocalConfigLoader.class);
        when(loader.current()).thenReturn(Optional.of(cfg));

        LangchainRunExecutorLimitsResolver resolver =
                new LangchainRunExecutorLimitsResolver(1, 1, 1, "default-", loader);

        Map<String, Object> gap = resolver.getHardVersusEffectiveGap();

        assertThat(gap.get("restartRequired")).isEqualTo(false);

        @SuppressWarnings("unchecked")
        Map<String, Object> dims = (Map<String, Object>) gap.get("dimensions");

        @SuppressWarnings("unchecked")
        Map<String, Object> core = (Map<String, Object>) dims.get("corePoolSize");
        assertThat(core.get("hard")).isEqualTo(10);
        assertThat(core.get("requested")).isEqualTo(10);
        assertThat(core.get("effective")).isEqualTo(10);
        assertThat(core.get("clamped")).isEqualTo(false);

        @SuppressWarnings("unchecked")
        Map<String, Object> prefix = (Map<String, Object>) dims.get("threadNamePrefix");
        assertThat(prefix.get("clamped")).isEqualTo(false);
    }

    @Test
    void gap_requestedExceedsHard_clampedAndRestartRequired() {
        AgentLlmProperties cfg = configWithHardAndCurrent(
                10, 20, 100, "agent-run-",
                30, 40, 200, "agent-run-");

        AgentLlmLocalConfigLoader loader = mock(AgentLlmLocalConfigLoader.class);
        when(loader.current()).thenReturn(Optional.of(cfg));

        LangchainRunExecutorLimitsResolver resolver =
                new LangchainRunExecutorLimitsResolver(1, 1, 1, "default-", loader);

        Map<String, Object> gap = resolver.getHardVersusEffectiveGap();

        // 至少一个维度超出 hard → restartRequired
        assertThat(gap.get("restartRequired")).isEqualTo(true);

        @SuppressWarnings("unchecked")
        Map<String, Object> dims = (Map<String, Object>) gap.get("dimensions");

        // core: requested(30) > hard(10) → clamped=true, effective=hard
        @SuppressWarnings("unchecked")
        Map<String, Object> core = (Map<String, Object>) dims.get("corePoolSize");
        assertThat(core.get("hard")).isEqualTo(10);
        assertThat(core.get("requested")).isEqualTo(30);
        assertThat(core.get("effective")).isEqualTo(10); // clamped to hard
        assertThat(core.get("clamped")).isEqualTo(true);

        // max: requested(40) > hard(20) → clamped=true, effective=hard
        @SuppressWarnings("unchecked")
        Map<String, Object> max = (Map<String, Object>) dims.get("maxPoolSize");
        assertThat(max.get("hard")).isEqualTo(20);
        assertThat(max.get("requested")).isEqualTo(40);
        assertThat(max.get("effective")).isEqualTo(20);
        assertThat(max.get("clamped")).isEqualTo(true);

        // queue: requested(200) > hard(100) → clamped=true
        @SuppressWarnings("unchecked")
        Map<String, Object> queue = (Map<String, Object>) dims.get("queueCapacity");
        assertThat(queue.get("hard")).isEqualTo(100);
        assertThat(queue.get("requested")).isEqualTo(200);
        assertThat(queue.get("effective")).isEqualTo(100);
        assertThat(queue.get("clamped")).isEqualTo(true);
    }

    @Test
    void gap_adaptiveCoreOverrideDoesNotAffectRestartRequired() {
        // hard: core=10, requested also 10（不超 hard）
        AgentLlmProperties cfg = configWithHardAndCurrent(
                10, 20, 100, "agent-run-",
                10, 20, 100, "agent-run-");

        AgentLlmLocalConfigLoader loader = mock(AgentLlmLocalConfigLoader.class);
        when(loader.current()).thenReturn(Optional.of(cfg));

        LangchainRunExecutorLimitsResolver resolver =
                new LangchainRunExecutorLimitsResolver(1, 1, 1, "default-", loader);

        // 设置 adaptive core 覆盖，把 core 临时降到 3
        resolver.setAdaptiveCoreOverride(3);

        Map<String, Object> gap = resolver.getHardVersusEffectiveGap();

        // 因为 requested(10) ≤ hard(10)，不受 adaptive 影响，restartRequired 仍为 false
        assertThat(gap.get("restartRequired")).isEqualTo(false);

        @SuppressWarnings("unchecked")
        Map<String, Object> dims = (Map<String, Object>) gap.get("dimensions");

        @SuppressWarnings("unchecked")
        Map<String, Object> core = (Map<String, Object>) dims.get("corePoolSize");
        assertThat(core.get("hard")).isEqualTo(10);
        assertThat(core.get("requested")).isEqualTo(10);
        // effective 反映 adaptive 降低后的值
        assertThat(core.get("effective")).isEqualTo(3);
        // clamped 仍基于 requested vs hard 比较，不是 adaptive 造成的降低
        assertThat(core.get("clamped")).isEqualTo(false);
    }

    @Test
    void gap_prefixChangeMarksRestartRequired() {
        AgentLlmProperties cfg = configWithHardAndCurrent(
                10, 20, 100, "agent-run-v1-",
                10, 20, 100, "agent-run-v2-");

        AgentLlmLocalConfigLoader loader = mock(AgentLlmLocalConfigLoader.class);
        when(loader.current()).thenReturn(Optional.of(cfg));

        LangchainRunExecutorLimitsResolver resolver =
                new LangchainRunExecutorLimitsResolver(1, 1, 1, "default-", loader);

        Map<String, Object> gap = resolver.getHardVersusEffectiveGap();

        // requested prefix 与 hard 不同 → restartRequired
        assertThat(gap.get("restartRequired")).isEqualTo(true);

        @SuppressWarnings("unchecked")
        Map<String, Object> dims = (Map<String, Object>) gap.get("dimensions");

        @SuppressWarnings("unchecked")
        Map<String, Object> prefix = (Map<String, Object>) dims.get("threadNamePrefix");
        assertThat(prefix.get("hard")).isEqualTo("agent-run-v1-");
        assertThat(prefix.get("requested")).isEqualTo("agent-run-v2-");
        // effective 仍是启动 hard 值（thread name 不支持 hot-reload）
        assertThat(prefix.get("effective")).isEqualTo("agent-run-v1-");
        assertThat(prefix.get("clamped")).isEqualTo(true);
    }

    private static AgentLlmProperties configWithHardAndCurrent(
            int hardCore, int hardMax, int hardQueue, String hardPrefix,
            int curCore, int curMax, int curQueue, String curPrefix) {
        AgentLlmProperties cfg = new AgentLlmProperties();
        AgentLlmProperties.ExecutorConfig executor = new AgentLlmProperties.ExecutorConfig();
        AgentLlmProperties.ExecutorParallelConfig parallel = new AgentLlmProperties.ExecutorParallelConfig();

        AgentLlmProperties.ExecutorConfig hard = new AgentLlmProperties.ExecutorConfig();
        hard.setCorePoolSize(hardCore);
        hard.setMaxPoolSize(hardMax);
        hard.setQueueCapacity(hardQueue);
        hard.setThreadNamePrefix(hardPrefix);
        parallel.setHard(hard);

        AgentLlmProperties.ExecutorConfig current = new AgentLlmProperties.ExecutorConfig();
        current.setCorePoolSize(curCore);
        current.setMaxPoolSize(curMax);
        current.setQueueCapacity(curQueue);
        current.setThreadNamePrefix(curPrefix);
        parallel.setCurrent(current);

        executor.setParallel(parallel);
        cfg.setExecutor(executor);
        return cfg;
    }
}
