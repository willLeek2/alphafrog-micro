package world.willfrog.agentlangchain.orchestration.scheduler;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import world.willfrog.agentlangchain.orchestration.LangchainRunConcurrencyScheduler;

/**
 * 周期全量调度诊断日志。默认关闭
 * （{@code agent.langchain.run.scheduler.advanced-diagnostics-enabled}），
 * 开启后才创建本组件与定时线程；基础 Micrometer 指标不受本开关影响。
 */
@Component
@ConditionalOnProperty(
        name = "agent.langchain.run.scheduler.advanced-diagnostics-enabled",
        havingValue = "true",
        matchIfMissing = false)
public class LangchainSchedulerDiagnostics {

    private final LangchainRunConcurrencyScheduler scheduler;

    public LangchainSchedulerDiagnostics(LangchainRunConcurrencyScheduler scheduler) {
        this.scheduler = scheduler;
    }

    @Scheduled(fixedDelayString = "${agent.langchain.run.executor.diag-interval-ms:30000}")
    public void publishDiagnostics() {
        scheduler.diagLog();
    }
}
