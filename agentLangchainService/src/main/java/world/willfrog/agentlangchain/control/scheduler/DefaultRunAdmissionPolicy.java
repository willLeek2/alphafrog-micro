package world.willfrog.agentlangchain.control.scheduler;

import org.springframework.stereotype.Component;

/**
 * 默认准入策略：容量权重 + 队列上限 + core/max 伸缩。
 *
 * <p>规则顺序（与旧 scheduler 语义保持一致，只增加容量维度）：</p>
 * <ol>
 *   <li>单 Run 权重大于总容量 → 立即 REJECTED（capacity_full）：排队永远
 *       拿不到容量，只会永久堵死队首；</li>
 *   <li>队列为空、core 有空位、容量放得下 → RUNNING；</li>
 *   <li>队列有名额 → QUEUED（容量不足时也允许排队等待，不误拒）；</li>
 *   <li>队列已满但 max 之下还有弹性空间 → ELASTIC（队列为空时还要求当前
 *       请求容量放得下；队首提升的容量检查在 scheduler 的提升逻辑里做）；</li>
 *   <li>否则 REJECTED（reason=queue_full / capacity_full）。</li>
 * </ol>
 */
@Component
public class DefaultRunAdmissionPolicy implements RunAdmissionPolicy {

    @Override
    public AdmissionDecision evaluate(AdmissionState state) {
        if (state.weight() > state.maxCapacityUnits()) {
            return AdmissionDecision.REJECTED;
        }
        if (state.queueEmpty()
                && state.running() < state.corePoolSize()
                && state.capacityFits()) {
            return AdmissionDecision.RUNNING;
        }
        if (state.reservedQueued() < state.queueCapacity()) {
            return AdmissionDecision.QUEUED;
        }
        if (state.running() < state.maxPoolSize()
                && (state.queueEmpty() ? state.capacityFits() : true)) {
            return AdmissionDecision.ELASTIC;
        }
        return AdmissionDecision.REJECTED;
    }
}
