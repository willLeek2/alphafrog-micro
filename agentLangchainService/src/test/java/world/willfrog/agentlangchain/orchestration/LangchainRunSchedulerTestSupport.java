package world.willfrog.agentlangchain.orchestration;

import world.willfrog.agent.platform.entity.AgentRun;

final class LangchainRunSchedulerTestSupport {

    private LangchainRunSchedulerTestSupport() {
    }

    static LangchainRunConcurrencyScheduler immediateScheduler() {
        return new LangchainRunConcurrencyScheduler(null, null) {
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
