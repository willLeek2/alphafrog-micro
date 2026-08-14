package world.willfrog.agentlangchain.tooljob;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisCapacityService;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.tools.python.DataAnalysisCapacityProperties;
import world.willfrog.agentlangchain.orchestration.scheduler.LangchainSchedulerMetrics;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 耐久恢复开关的 Bean 门控：
 * 默认关闭时只有进程内 continuation tracker 存在；显式开启时只有
 * reconciler / startup recovery / resume launcher heartbeat 存在。
 * 两套发现机制绝不共享同一个 Run。
 */
class ToolJobDurableRecoveryGatingTest {

    @Configuration
    static class TestConfig {
        @Bean
        ToolJobAnchorService anchorService() {
            return mock(ToolJobAnchorService.class);
        }

        @Bean
        ToolJobRedisCache redisCache() {
            return mock(ToolJobRedisCache.class);
        }

        @Bean
        ToolJobFinalizer finalizer() {
            return mock(ToolJobFinalizer.class);
        }

        @Bean
        ToolJobResumeService resumeService() {
            return mock(ToolJobResumeService.class);
        }

        @Bean
        AgentRunMapper runMapper() {
            return mock(AgentRunMapper.class);
        }

        @Bean
        DataAnalysisCapacityService capacityService() {
            return mock(DataAnalysisCapacityService.class);
        }

        @Bean
        DataAnalysisCapacityProperties capacityProperties() {
            return new DataAnalysisCapacityProperties();
        }

        @Bean
        ToolJobConfig toolJobConfig() {
            return new ToolJobConfig();
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        LangchainSchedulerMetrics schedulerMetrics(MeterRegistry registry) {
            return new LangchainSchedulerMetrics(registry);
        }

        @Bean
        ToolJobResumeLauncherImpl resumeLauncher() {
            return mock(ToolJobResumeLauncherImpl.class);
        }
    }

    /**
     * 注意：AnnotatedBeanDefinitionReader 在 ctx.register(Class) 时就评估
     * @ConditionalOnProperty，因此属性源必须在注册任何候选类之前放入环境。
     */
    private AnnotationConfigApplicationContext context(String durableValue) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        if (durableValue != null) {
            ctx.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("test",
                            Map.of("agent.tool-job.durable-recovery-enabled", durableValue)));
        }
        ctx.register(TestConfig.class);
        ctx.register(ToolJobContinuationTracker.class);
        ctx.register(ToolJobReconciler.class);
        ctx.register(ToolJobStartupRecovery.class);
        ctx.register(ToolJobResumeLauncherHeartbeat.class);
        return ctx;
    }

    @Test
    void defaultModeCreatesOnlyInProcessTracker() {
        try (AnnotationConfigApplicationContext ctx = context(null)) {
            ctx.refresh();

            assertThat(ctx.getBeanNamesForType(ToolJobContinuationTracker.class)).hasSize(1);
            assertThat(ctx.getBeanNamesForType(ToolJobReconciler.class)).isEmpty();
            assertThat(ctx.getBeanNamesForType(ToolJobStartupRecovery.class)).isEmpty();
            assertThat(ctx.getBeanNamesForType(ToolJobResumeLauncherHeartbeat.class)).isEmpty();
        }
    }

    @Test
    void durableModeCreatesOnlyDurableBeans() {
        try (AnnotationConfigApplicationContext ctx = context("true")) {
            ctx.refresh();

            assertThat(ctx.getBeanNamesForType(ToolJobContinuationTracker.class)).isEmpty();
            assertThat(ctx.getBeanNamesForType(ToolJobReconciler.class)).hasSize(1);
            assertThat(ctx.getBeanNamesForType(ToolJobStartupRecovery.class)).hasSize(1);
            assertThat(ctx.getBeanNamesForType(ToolJobResumeLauncherHeartbeat.class)).hasSize(1);
        }
    }

    @Test
    void explicitDisableBehavesLikeDefault() {
        try (AnnotationConfigApplicationContext ctx = context("false")) {
            ctx.refresh();

            assertThat(ctx.getBeanNamesForType(ToolJobContinuationTracker.class)).hasSize(1);
            assertThat(ctx.getBeanNamesForType(ToolJobReconciler.class)).isEmpty();
            assertThat(ctx.getBeanNamesForType(ToolJobStartupRecovery.class)).isEmpty();
            assertThat(ctx.getBeanNamesForType(ToolJobResumeLauncherHeartbeat.class)).isEmpty();
        }
    }

    @Test
    void trackerConditionDefaultsToPresentWhileDurableBeansRequireExplicitTrue() {
        ConditionalOnProperty tracker = ToolJobContinuationTracker.class
                .getAnnotation(ConditionalOnProperty.class);
        assertThat(tracker).isNotNull();
        assertThat(tracker.matchIfMissing()).isTrue();
        assertThat(tracker.name()).contains("agent.tool-job.durable-recovery-enabled");

        for (Class<?> durable : new Class<?>[]{ToolJobReconciler.class,
                ToolJobStartupRecovery.class, ToolJobResumeLauncherHeartbeat.class}) {
            ConditionalOnProperty annotation =
                    durable.getAnnotation(ConditionalOnProperty.class);
            assertThat(annotation).as(durable.getSimpleName()).isNotNull();
            assertThat(annotation.matchIfMissing())
                    .as(durable.getSimpleName() + " must not be created by default")
                    .isFalse();
            assertThat(annotation.name()).contains("agent.tool-job.durable-recovery-enabled");
        }
    }
}
