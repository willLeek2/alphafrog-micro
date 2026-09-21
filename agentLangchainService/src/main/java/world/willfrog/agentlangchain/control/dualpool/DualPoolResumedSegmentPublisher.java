package world.willfrog.agentlangchain.control.dualpool;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.wait.RecoveryConsumptionResult;
import world.willfrog.agent.platform.wait.WaitGroupStore;
import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;
import world.willfrog.agentlangchain.execution.ResumedSegmentPublisher;

/**
 * 「提交之后马上唤醒」这一条恢复路径：整组在同一次领取里就齐备时，由写结果的那个线程把通知消费掉，
 * 下一段立刻重新进入节点池。
 *
 * <p>只有这一条路是不够的：进程退出、通知写出来时没人守着、内存提示丢了这些情况都需要周期补扫与
 * 启动扫描。那一套（每轮处理上限、退避与抖动、启动分页重建）属于阶段三第四组，届时把这一条并进去，
 * 不在这里另起一套。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DualPoolResumedSegmentPublisher implements ResumedSegmentPublisher {

    private static final String DISPATCHER_ID = "dual-pool-wait-group-online";

    private final WaitGroupStore waitGroupStore;
    private final DualPoolDispatcher dispatcher;

    @Autowired(required = false)
    private DualPoolRunAdmissionRegistry admissionRegistry;

    @Override
    public boolean publish(long notificationId, long runControlVersion, NodeWorkItemIdentity nextSegment) {
        if (nextSegment == null) {
            throw new IllegalArgumentException("放行下一段必须给出分段身份");
        }
        RecoveryConsumptionResult consumed = waitGroupStore.consumeRecovery(
                notificationId, DISPATCHER_ID, runControlVersion);
        if (consumed.inconsistent()) {
            log.error("恢复通知与下一段的状态不一致，人工检查这条等待链：notification={} group={}",
                    notificationId, consumed.groupId());
            return false;
        }
        if (!consumed.succeeded()) {
            // Run 已经不在执行中（取消、暂停、计划代际失效）或控制版本变了：通知留在原地，
            // 由取消路径或下一次补扫处理，这一次不放行。
            log.info("这条恢复通知暂时不能消费：notification={} runControlVersion={}",
                    notificationId, runControlVersion);
            return false;
        }
        if (admissionRegistry != null
                && !admissionRegistry.restorePersistedToolJob(nextSegment.runId())) {
            // 业务准入没拿回来时先不投递：投了也会被节点池按「未准入」丢掉。
            log.warn("下一段已放成可恢复，但业务准入没有恢复，等下一轮补扫：segment={}",
                    nextSegment.describe());
            return false;
        }
        return dispatcher.offerNode(nextSegment);
    }
}
