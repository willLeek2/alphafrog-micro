package world.willfrog.agentlangchain.orchestration;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agentlangchain.tooljob.ToolJobResumeContext;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Run 执行形态的路由入口。调用方（facade、恢复扫描、长工具恢复）只认本路由，
 * 不感知具体形态；路由把每种入口意图映射到对应的形态子类：
 * 全新执行 / follow-up / 断点重跑 → 全新形态；冻结计划重启 → 冻结重启形态；
 * 长工具恢复 → 长工具恢复形态。按 Run 状态自动选择形态的规则（灰度标记、
 * 重启次数等维度）后续在路由里集中扩展，调用方不变。
 */
@Primary
@Component
public class RunPipelineRouter implements LangchainLinearRunPipeline {

    private final FreshRunPipeline freshPipeline;
    private final FrozenPlanRestartPipeline frozenRestartPipeline;
    private final ToolJobResumePipeline toolJobResumePipeline;

    public RunPipelineRouter(FreshRunPipeline freshPipeline,
                             FrozenPlanRestartPipeline frozenRestartPipeline,
                             ToolJobResumePipeline toolJobResumePipeline) {
        this.freshPipeline = freshPipeline;
        this.frozenRestartPipeline = frozenRestartPipeline;
        this.toolJobResumePipeline = toolJobResumePipeline;
    }

    @Override
    public void launchAsync(AgentRun run) {
        freshPipeline.launchAsync(run);
    }

    @Override
    public void launchAsync(AgentRun run, LangchainRunConcurrencyScheduler.Reservation reservation) {
        freshPipeline.launchAsync(run, reservation);
    }

    @Override
    public boolean launchRestartedAsync(AgentRun run) {
        if (run == null || run.getId() == null || run.getId().isBlank()) {
            return false;
        }
        frozenRestartPipeline.launchAsync(run);
        return true;
    }

    @Override
    public boolean launchResumedAsync(AgentRun run,
                                      ToolJobResumeContext context,
                                      BooleanSupplier terminalConsumed,
                                      Consumer<Boolean> completion) {
        return toolJobResumePipeline.launchResumedAsync(run, context, terminalConsumed, completion);
    }
}
