package world.willfrog.agentlangchain.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import world.willfrog.agent.platform.capacity.PermitUsage;
import world.willfrog.agent.platform.capacity.SchedulerPermitLedger;
import world.willfrog.agentlangchain.control.dualpool.DualPoolDispatcher;
import world.willfrog.agentlangchain.control.dualpool.DualPoolRunAdmissionRegistry;
import world.willfrog.agentlangchain.control.dualpool.FrozenEffectiveSettings;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 双池调度器要能真的从容器里拿到它要的那两个线程池。
 *
 * <p>调度器按限定名要节点线程池，线程池按 {@code @Bean(name = …)} 注册。两边都是字符串，改了一处
 * 忘了另一处，任何直接调工厂方法的用例都不会失败——它根本不起容器；到服务启动时才报「找不到符合条件的
 * Bean」。这里走真实的容器装配：容器里两个线程池同类型，按类型挑不出来，限定名对不上就会当场失败。</p>
 *
 * <p>为了让这条用例只装它需要的这几样，容器里所有 Bean 定义都设成惰性：惰性定义不参与启动时的
 * 预实例化，只有真正被要到的（调度器与它依赖的两个线程池）才会被创建。</p>
 */
class DualPoolExecutorWiringTest {

    @Test
    void theDispatcherIsCreatedWithThePoolsItAsksForByQualifier() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(LangchainRunAsyncConfig.class, DualPoolDispatcher.class, Stubs.class);
        context.addBeanFactoryPostProcessor(factory -> {
            for (String name : factory.getBeanDefinitionNames()) {
                factory.getBeanDefinition(name).setLazyInit(true);
            }
        });
        context.refresh();
        try {
            DualPoolDispatcher dispatcher = context.getBean(DualPoolDispatcher.class);
            assertThat(dispatcher).as("限定名与 Bean 名对不上时，这一步就会抛异常").isNotNull();

            List<String> qualifiers = qualifiersOnTheDispatcherConstructor();
            assertThat(qualifiers).as("调度器至少按限定名要一个线程池，否则这条用例没有意义").isNotEmpty();
            for (String qualifier : qualifiers) {
                assertThat(context.getBean(qualifier))
                        .as("限定名 " + qualifier + " 在容器里要对得上一个线程池")
                        .isInstanceOf(ThreadPoolTaskExecutor.class);
            }
        } finally {
            context.close();
        }
    }

    /** 调度器构造器上写下的限定名，按参数顺序。 */
    private static List<String> qualifiersOnTheDispatcherConstructor() {
        Constructor<?> constructor = DualPoolDispatcher.class.getConstructors()[0];
        List<String> names = new ArrayList<>();
        for (Annotation[] annotations : constructor.getParameterAnnotations()) {
            for (Annotation annotation : annotations) {
                if (annotation instanceof Qualifier qualifier) {
                    names.add(qualifier.value());
                }
            }
        }
        return names;
    }

    /**
     * 这条用例只关心线程池与调度器的装配，其余协作者给替身：这几个类各有自己的依赖，
     * 把它们真建出来会把整个引用图拖进容器，那就不是在测装配了。
     */
    @Configuration
    static class Stubs {

        @Bean
        FrozenEffectiveSettings frozenEffectiveSettings() {
            return new FrozenEffectiveSettings();
        }

        @Bean
        DualPoolRunAdmissionRegistry admissionRegistry() {
            return mock(DualPoolRunAdmissionRegistry.class);
        }

        @Bean
        SchedulerPermitLedger permitLedger() {
            SchedulerPermitLedger ledger = mock(SchedulerPermitLedger.class);
            // 调度器构造时把三层的上限登记进读数，替身要给得出每一层的读数。
            when(ledger.usage(any())).thenAnswer(invocation ->
                    PermitUsage.of(invocation.getArgument(0), 0, 4));
            return ledger;
        }

        @Bean
        LangchainRunExecutorLimitsResolver limitsResolver() {
            LangchainRunExecutorLimitsResolver resolver = mock(LangchainRunExecutorLimitsResolver.class);
            when(resolver.hardLimits())
                    .thenReturn(new LangchainRunExecutorLimits(4, 8, 50, "agent-langchain-run-"));
            return resolver;
        }
    }
}
