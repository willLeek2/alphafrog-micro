package world.willfrog.agentlangchain.orchestration;

import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agentlangchain.orchestration.scheduler.DefaultRunAdmissionPolicy;
import world.willfrog.agentlangchain.orchestration.scheduler.DefaultRunPriorityPolicy;
import world.willfrog.agentlangchain.orchestration.scheduler.DefaultRunWeightPolicy;
import world.willfrog.agentlangchain.orchestration.scheduler.InProcessRunCapacityLedger;
import world.willfrog.agentlangchain.orchestration.scheduler.LangchainSchedulerMetrics;

final class LangchainRunSchedulerTestSupport {

    private LangchainRunSchedulerTestSupport() {
    }

    static LangchainRunConcurrencyScheduler immediateScheduler() {
        return new LangchainRunConcurrencyScheduler(
                null,
                null,
                new DefaultRunAdmissionPolicy(),
                new DefaultRunPriorityPolicy(),
                new DefaultRunWeightPolicy(),
                new InProcessRunCapacityLedger(4),
                new LangchainSchedulerMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                "test-support-app",
                false) {
            @Override
            public Reservation reserve() {
                return null;
            }

            @Override
            public void submit(Reservation reservation, AgentRun run, Runnable task) {
                Thread thread = new Thread(task, "immediate-scheduler-test");
                thread.start();
            }

            @Override
            public void release(Reservation reservation) {
            }
        };
    }
}
