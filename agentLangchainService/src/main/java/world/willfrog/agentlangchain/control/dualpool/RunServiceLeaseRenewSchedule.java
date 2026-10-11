package world.willfrog.agentlangchain.control.dualpool;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.time.Duration;

/**
 * 把服务所有权续期的周期任务挂到调度器上。
 *
 * <p>周期必须用 {@link RunServiceLeaseKeeper} 构造完之后已经归一化的毫秒数。
 * 这个配置在 bean 都建完之后才跑，所以可以安全读取那个数，而不必在创建
 * {@code RunServiceLeaseKeeper} 的过程中用 SpEL 再去引用它自己。</p>
 */
@Configuration
class RunServiceLeaseRenewSchedule implements SchedulingConfigurer {

    private final RunServiceLeaseKeeper keeper;

    RunServiceLeaseRenewSchedule(RunServiceLeaseKeeper keeper) {
        this.keeper = keeper;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(keeper::renewPeriodically, Duration.ofMillis(keeper.renewIntervalMillis()));
    }
}
