package world.willfrog.agentlangchain.execution;

import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agentlangchain.control.LangchainRunConcurrencyScheduler;
import world.willfrog.agentlangchain.control.dualpool.SchedulerVersionPolicy;
import world.willfrog.agentlangchain.tooljob.ToolJobResumeContext;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Run 调度器版本与执行形态的统一路由入口。
 *
 * <p>第一层先读取 Run 创建时已经冻结的 {@code schedulerVersion}：LEGACY 继续进入原有
 * 全 Run 调度器，DUAL_POOL_V1 只进入双池入口。第二层才在 LEGACY 内区分全新执行、
 * 冻结计划重启和长工具恢复。创建、追问、手动恢复、启动恢复和工具恢复都必须经过本类，
 * 避免同一个 Run 被两个调度器同时消费。</p>
 */
@Primary
@Component
public class RunPipelineRouter implements LangchainLinearRunPipeline {

    private final FreshRunPipeline freshPipeline;
    private final FrozenPlanRestartPipeline frozenRestartPipeline;
    private final ToolJobResumePipeline toolJobResumePipeline;
    private final DualPoolRunPipeline dualPoolPipeline;
    private final SchedulerVersionPolicy schedulerVersionPolicy;

    @Autowired
    public RunPipelineRouter(FreshRunPipeline freshPipeline,
                             FrozenPlanRestartPipeline frozenRestartPipeline,
                             ToolJobResumePipeline toolJobResumePipeline,
                             DualPoolRunPipeline dualPoolPipeline,
                             SchedulerVersionPolicy schedulerVersionPolicy) {
        this.freshPipeline = freshPipeline;
        this.frozenRestartPipeline = frozenRestartPipeline;
        this.toolJobResumePipeline = toolJobResumePipeline;
        this.dualPoolPipeline = dualPoolPipeline;
        this.schedulerVersionPolicy = schedulerVersionPolicy;
    }

    @Override
    public void launchAsync(AgentRun run) {
        if (schedulerVersionPolicy.isLegacy(run)) {
            freshPipeline.launchAsync(run);
            return;
        }
        dualPoolPipeline.launchAsync(run);
    }

    @Override
    public void launchAsync(AgentRun run, LangchainRunConcurrencyScheduler.Reservation reservation) {
        if (schedulerVersionPolicy.isLegacy(run)) {
            freshPipeline.launchAsync(run, reservation);
            return;
        }
        dualPoolPipeline.launchAsync(run, reservation);
    }

    @Override
    public boolean launchRestartedAsync(AgentRun run) {
        if (run == null || run.getId() == null || run.getId().isBlank()) {
            return false;
        }
        if (schedulerVersionPolicy.isLegacy(run)) {
            frozenRestartPipeline.launchAsync(run);
            return true;
        }
        return dualPoolPipeline.launchRestartedAsync(run);
    }

    @Override
    public boolean launchResumedAsync(AgentRun run,
                                      ToolJobResumeContext context,
                                      BooleanSupplier terminalConsumed,
                                      Consumer<Boolean> completion) {
        if (schedulerVersionPolicy.isLegacy(run)) {
            return toolJobResumePipeline.launchResumedAsync(run, context, terminalConsumed, completion);
        }
        return dualPoolPipeline.launchResumedAsync(run, context, terminalConsumed, completion);
    }
}
