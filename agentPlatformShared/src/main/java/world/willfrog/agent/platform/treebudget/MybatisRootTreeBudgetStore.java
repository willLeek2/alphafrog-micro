package world.willfrog.agent.platform.treebudget;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import world.willfrog.agent.platform.mapper.RootTreeBudgetMapper;

/** 一棵根树的全部额度由同一数据库行锁串行处理。 */
@Repository
@RequiredArgsConstructor
public class MybatisRootTreeBudgetStore implements RootTreeBudgetStore {
    private final RootTreeBudgetMapper mapper;

    @Override
    @Transactional
    public State reserve(String rootRunId, String operationId, Kind kind, long limit) {
        requireId(rootRunId, "rootRunId", 64);
        requireId(operationId, "operationId", 512);
        if (kind == null || limit <= 0) {
            throw new IllegalArgumentException("根树额度类别和正数上限均不能为空");
        }
        mapper.ensureRoot(rootRunId);
        if (mapper.lockRoot(rootRunId) == null) {
            throw new IllegalStateException("根 Run 不存在：" + rootRunId);
        }
        RootTreeBudgetOperationRow existing = mapper.operation(operationId);
        if (existing != null) {
            if (!rootRunId.equals(existing.getRootRunId()) || !kind.name().equals(existing.getKind())) {
                throw new IllegalStateException("操作身份已用于其他根树或额度类别：" + operationId);
            }
            return State.valueOf(existing.getState());
        }
        if (mapper.tryIncrement(rootRunId, kind.name(), limit) != 1) {
            return State.REJECTED;
        }
        if (mapper.insertOperation(operationId, rootRunId, kind.name()) != 1) {
            throw new IllegalStateException("根树额度操作记录写入失败：" + operationId);
        }
        return State.RESERVED;
    }

    @Override
    @Transactional
    public State confirm(String operationId) {
        RootTreeBudgetOperationRow row = lockOperationTree(operationId);
        State state = State.valueOf(row.getState());
        if (state == State.CONFIRMED) {
            return state;
        }
        if (state != State.RESERVED || mapper.updateState(operationId, "RESERVED", "CONFIRMED") != 1) {
            throw new IllegalStateException("额度操作不能确认：" + operationId);
        }
        return State.CONFIRMED;
    }

    @Override
    @Transactional
    public State release(String operationId) {
        RootTreeBudgetOperationRow row = lockOperationTree(operationId);
        State state = State.valueOf(row.getState());
        if (state == State.RELEASED) {
            return state;
        }
        Kind kind = Kind.valueOf(row.getKind());
        if (state == State.CONFIRMED && (kind == Kind.LLM_CALL || kind == Kind.TOOL_CALL)) {
            throw new IllegalStateException("已经发生的调用不能退回额度：" + operationId);
        }
        if (mapper.decrement(row.getRootRunId(), row.getKind()) != 1
                || mapper.updateState(operationId, state.name(), "RELEASED") != 1) {
            throw new IllegalStateException("根树额度释放失败：" + operationId);
        }
        return State.RELEASED;
    }

    @Override
    @Transactional(readOnly = true)
    public Snapshot snapshot(String rootRunId) {
        requireId(rootRunId, "rootRunId", 64);
        RootTreeBudgetSnapshotRow row = mapper.snapshot(rootRunId);
        if (row == null) {
            throw new IllegalStateException("根树额度记录不存在：" + rootRunId);
        }
        return new Snapshot(row.getLlmCalls(), row.getToolCalls(), row.getActiveNodes(), row.getExternalWaits());
    }

    private RootTreeBudgetOperationRow lockOperationTree(String operationId) {
        requireId(operationId, "operationId", 512);
        RootTreeBudgetOperationRow initial = mapper.operation(operationId);
        if (initial == null || mapper.lockRoot(initial.getRootRunId()) == null) {
            throw new IllegalStateException("额度操作不存在：" + operationId);
        }
        RootTreeBudgetOperationRow locked = mapper.operation(operationId);
        if (locked == null || !initial.getRootRunId().equals(locked.getRootRunId())) {
            throw new IllegalStateException("额度操作在锁定期间发生变化：" + operationId);
        }
        return locked;
    }

    private static void requireId(String value, String name, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " 必须是长度不超过 " + maximumLength + " 的非空字符串");
        }
    }
}
