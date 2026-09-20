package world.willfrog.agentlangchain.execution;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agentlangchain.control.LangchainRunConcurrencyScheduler;
import world.willfrog.agentlangchain.control.LangchainRunRejectedException;
import world.willfrog.agentlangchain.control.dualpool.DualPoolDispatcher;
import world.willfrog.agentlangchain.control.dualpool.DualPoolRunAdmissionRegistry;
import world.willfrog.agentlangchain.control.dualpool.RunCoordinationHint;
import world.willfrog.agentlangchain.tooljob.ToolJobResumeContext;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * {@code DUAL_POOL_V1} 的 Run 入口。
 *
 * <p>入口只补发一次协调提示，不在请求线程或旧 Run worker 中执行完整工作流。
 * 提示队列满时数据库扫描会重新发现该 Run，因此不能把提示丢失改写成 Run 失败。</p>
 */
@Component
@Slf4j
public class DualPoolRunPipeline implements LangchainLinearRunPipeline {

    private final DualPoolDispatcher dispatcher;
    private final DualPoolRunAdmissionRegistry admissionRegistry;

    public DualPoolRunPipeline(DualPoolDispatcher dispatcher,
                               DualPoolRunAdmissionRegistry admissionRegistry) {
        this.dispatcher = dispatcher;
        this.admissionRegistry = admissionRegistry;
    }

    @Override
    public void launchAsync(AgentRun run) {
        if (run == null || !admissionRegistry.isAdmitted(run.getId())) {
            throw new LangchainRunRejectedException("dual_pool_run_not_admitted_in_current_process");
        }
        enqueue(run, RunCoordinationHint.Reason.SCAN_REDISCOVERED);
    }

    @Override
    public void launchAsync(AgentRun run, LangchainRunConcurrencyScheduler.Reservation reservation) {
        if (reservation != null) {
            throw new IllegalStateException("dual_pool_run_must_not_use_legacy_reservation");
        }
        enqueue(run, RunCoordinationHint.Reason.NEW_RUN);
    }

    /**
     * 新版本的非终态记录在进程意外重启后不自动恢复。本阶段只允许读取、观察和显式取消。
     */
    @Override
    public boolean launchRestartedAsync(AgentRun run) {
        log.warn("双池 Run 在启动恢复阶段保持失败关闭，不补发执行提示: runId={}",
                run == null ? null : run.getId());
        return false;
    }

    /** 长工具结果接入属于后续阶段，本阶段不能把它误接回旧 Run 调度器。 */
    @Override
    public boolean launchResumedAsync(AgentRun run,
                                      ToolJobResumeContext context,
                                      BooleanSupplier terminalConsumed,
                                      Consumer<Boolean> completion) {
        log.warn("双池 Run 尚未开放长工具恢复入口: runId={}", run == null ? null : run.getId());
        return false;
    }

    public void enqueue(AgentRun run, RunCoordinationHint.Reason reason) {
        if (run == null || run.getId() == null || run.getId().isBlank()) {
            throw new IllegalArgumentException("agent_run_id_required");
        }
        if (!admissionRegistry.isAdmitted(run.getId())) {
            throw new IllegalStateException("dual_pool_run_not_admitted_in_current_process");
        }
        if (!dispatcher.isReady()) {
            throw new IllegalStateException("dual_pool_work_handler_unavailable");
        }
        boolean hinted = dispatcher.offerRun(new RunCoordinationHint(run.getId(), reason));
        if (!hinted) {
            log.info("Run 协调提示队列已满，等待数据库扫描重新发现: runId={} reason={}",
                    run.getId(), reason);
        }
    }
}
