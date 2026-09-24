package world.willfrog.agent.platform.treebudget;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import world.willfrog.agent.platform.childrun.ChildRunIntentStore;
import world.willfrog.agent.platform.mapper.RootTreeBudgetMapper;
import world.willfrog.agent.platform.workitem.NodeWorkItem;
import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;
import world.willfrog.agent.platform.wait.WaitSuspensionRequest;

import java.util.List;

/** 在同一事务中保护节点领取和外部等待，统一按 Run、根额度、业务行的顺序加锁。 */
@Component
public class RootTreeActivityBudget {
    private final RootTreeBudgetMapper mapper;
    private final ChildRunIntentStore childRuns;
    private final RootTreeBudgetStore budget;
    private final long activeNodeLimit;
    private final long externalWaitLimit;

    public RootTreeActivityBudget(RootTreeBudgetMapper mapper, ChildRunIntentStore childRuns,
                                  RootTreeBudgetStore budget,
                                  @Value("${agent.langchain.dual-pool.root-tree.active-node-limit:64}") long activeNodeLimit,
                                  @Value("${agent.langchain.dual-pool.root-tree.external-wait-limit:64}") long externalWaitLimit) {
        this.mapper = mapper;
        this.childRuns = childRuns;
        this.budget = budget;
        if (activeNodeLimit <= 0 || externalWaitLimit <= 0) {
            throw new IllegalArgumentException("根树活跃节点与外部等待上限必须为正数");
        }
        this.activeNodeLimit = activeNodeLimit;
        this.externalWaitLimit = externalWaitLimit;
    }

    /** 应用启动时拒绝没有预算记录的旧在途状态。迁移 021 也会先做一次排空检查。 */
    @PostConstruct
    public void verifyExistingActivity() {
        List<String> missing = mapper.listUntrackedActivity(20);
        if (missing == null) {
            throw new IllegalStateException("根树在途额度核验没有返回结果");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("根树在途额度缺少持久操作记录，拒绝启动：" + missing);
        }
    }

    /** 子 Run 的业务行已终结时，仍须核对其节点线程对应的未释放领取代际。 */
    public boolean hasUnreleasedActiveNodesByRun(String runId) {
        if (runId == null || runId.isBlank()) throw new IllegalArgumentException("Run 编号不能为空");
        Boolean active = mapper.hasUnreleasedActiveNodesByRun(runId);
        if (active == null) throw new IllegalStateException("无法确认 Run 的节点执行是否已经停止：" + runId);
        return active;
    }

    /** 根树业务许可不能早于节点执行和整组外部等待的持久占用归还。 */
    public boolean hasUnsettledActivity(String rootRunId) {
        RootTreeBudgetStore.Snapshot current = budget.snapshot(rootRunId);
        return current.activeNodes() > 0 || current.externalWaits() > 0;
    }

    /** 调用方必须在后续条件更新之前保持这个事务与两把锁。 */
    public String lockForRun(String runId) {
        requireTransaction();
        String rootRunId = childRuns.rootRunIdOf(runId)
                .orElseThrow(() -> new IllegalStateException("无法确定 Run 的根树身份：" + runId));
        if (mapper.lockRun(runId) == null) {
            throw new IllegalStateException("待修改的 Run 不存在：" + runId);
        }
        if (rootRunId.equals(runId)) {
            budget.lockRoot(rootRunId);
        } else {
            budget.lockExistingRoot(rootRunId);
        }
        return rootRunId;
    }

    public RootTreeBudgetStore.State reserveNode(String rootRunId, NodeWorkItem item, int claimEpoch) {
        requireTransaction();
        if (item == null || item.getId() == null || claimEpoch <= 0) {
            throw new IllegalArgumentException("节点领取缺少完整持久身份");
        }
        return budget.reserve(rootRunId, nodeOperationId(item.getId(), claimEpoch),
                RootTreeBudgetStore.Kind.ACTIVE_NODE, activeNodeLimit);
    }

    public void releaseNode(NodeWorkItem item, int claimEpoch) {
        requireTransaction();
        if (item == null || item.getId() == null || claimEpoch <= 0) {
            throw new IllegalStateException("节点释放缺少完整持久身份");
        }
        budget.release(nodeOperationId(item.getId(), claimEpoch));
    }

    public void confirmNode(NodeWorkItem item, int claimEpoch) {
        requireTransaction();
        if (item == null || item.getId() == null || claimEpoch <= 0) {
            throw new IllegalStateException("节点确认缺少完整持久身份");
        }
        budget.confirm(nodeOperationId(item.getId(), claimEpoch));
    }

    public RootTreeBudgetStore.State reserveWait(String rootRunId, WaitSuspensionRequest request) {
        requireTransaction();
        return budget.reserve(rootRunId, waitOperationId(request.segment(), request.modelTurn()),
                RootTreeBudgetStore.Kind.EXTERNAL_WAIT, externalWaitLimit);
    }

    public void confirmWait(WaitSuspensionRequest request) {
        requireTransaction();
        budget.confirm(waitOperationId(request.segment(), request.modelTurn()));
    }

    public void releaseWait(NodeWorkItemIdentity segment, int modelTurn) {
        requireTransaction();
        budget.release(waitOperationId(segment, modelTurn));
    }

    public static String nodeOperationId(long workItemId, int claimEpoch) {
        if (workItemId <= 0 || claimEpoch <= 0) {
            throw new IllegalArgumentException("节点编号和领取代际必须为正数");
        }
        return "active-node:" + workItemId + ":" + claimEpoch;
    }

    /** 六字段身份；长度前缀使节点名包含分隔符时也不会撞号。 */
    public static String waitOperationId(NodeWorkItemIdentity segment, int modelTurn) {
        if (segment == null || modelTurn < 0) {
            throw new IllegalArgumentException("外部等待缺少完整组身份");
        }
        String value = "external-wait:" + segment.runId().codePointCount(0, segment.runId().length())
                + ":" + segment.runId()
                + ":" + segment.planGeneration() + ":"
                + segment.nodeId().codePointCount(0, segment.nodeId().length()) + ":" + segment.nodeId()
                + ":" + segment.nodeAttempt() + ":" + segment.segmentSequence() + ":" + modelTurn;
        if (value.length() > 512) {
            throw new IllegalArgumentException("外部等待操作身份超过数据库上限");
        }
        return value;
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("根树额度必须与业务状态在同一个数据库事务中更新");
        }
    }
}
