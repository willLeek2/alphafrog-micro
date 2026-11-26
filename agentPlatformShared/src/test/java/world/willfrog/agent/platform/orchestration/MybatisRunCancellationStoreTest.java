package world.willfrog.agent.platform.orchestration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.mapper.AsyncToolTaskMapper;
import world.willfrog.agent.platform.mapper.RunCancellationMapper;
import world.willfrog.agent.platform.wait.WaitGroup;
import world.willfrog.agent.platform.wait.WaitGroupStore;
import world.willfrog.agent.platform.workitem.NodeWorkItemStore;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 取消事务的两条路由：有旧锚点时只记取消意图，终态留给旧版按锚点收尾；
 * 无旧锚点时本事务自己写运行终态，同一事务里把子行一起收口。
 */
class MybatisRunCancellationStoreTest {

    private static final String RUN = "run-cancel";
    private static final String USER = "user-1";
    private static final String SNAPSHOT = "{\"status\":\"EXECUTING\"}";

    private RunCancellationMapper mapper;
    private AsyncToolTaskMapper tasks;
    private WaitGroupStore groups;
    private NodeWorkItemStore workItems;
    private MybatisRunCancellationStore store;

    @BeforeEach
    void setUp() {
        mapper = mock(RunCancellationMapper.class);
        tasks = mock(AsyncToolTaskMapper.class);
        groups = mock(WaitGroupStore.class);
        workItems = mock(NodeWorkItemStore.class);
        store = new MybatisRunCancellationStore(mapper, tasks, groups, workItems);
        when(groups.listOpenGroupsByRun(any(), any(Long.class), any(Integer.class)))
                .thenReturn(List.of(), List.of());
    }

    @Test
    void withActiveAnchorOnlyTheCancelIntentIsWritten() {
        when(mapper.lockRunForUser(RUN, USER)).thenReturn(run("EXECUTING", "operation-1"));
        when(mapper.persistCancelDisposition(RUN, "EXECUTING", "operation-1")).thenReturn(1);
        when(mapper.snapshotIfStatus(eq(RUN), eq(USER), eq("EXECUTING"), eq(SNAPSHOT))).thenReturn(1);

        assertThat(store.cancelRunInTransaction(RUN, USER, SNAPSHOT, null))
                .isEqualTo(CancelRunOutcome.CANCEL_INTENT_RECORDED);

        verify(mapper, never()).cancelTerminalNoActiveTask(any(), any(), any(), any());
    }

    @Test
    void snapshotCasMissKeepsTheRunForAnotherRound() {
        when(mapper.lockRunForUser(RUN, USER)).thenReturn(run("EXECUTING", "operation-1"));
        when(mapper.persistCancelDisposition(RUN, "EXECUTING", "operation-1")).thenReturn(1);
        when(mapper.snapshotIfStatus(eq(RUN), eq(USER), eq("EXECUTING"), eq(SNAPSHOT))).thenReturn(0);

        assertThat(store.cancelRunInTransaction(RUN, USER, SNAPSHOT, null))
                .isEqualTo(CancelRunOutcome.STATE_CHANGED);
    }

    @Test
    void withoutAnchorTheSameTransactionWritesTheTerminalState() {
        when(mapper.lockRunForUser(RUN, USER)).thenReturn(run("EXECUTING", null));
        when(mapper.cancelTerminalNoActiveTask(eq(RUN), eq(USER), eq(SNAPSHOT), any())).thenReturn(1);
        WaitGroup group = new WaitGroup();
        group.setId(7L);
        when(groups.listOpenGroupsByRun(eq(RUN), eq(0L), eq(100))).thenReturn(List.of(group), List.of());

        assertThat(store.cancelRunInTransaction(RUN, USER, SNAPSHOT, OffsetDateTime.now()))
                .isEqualTo(CancelRunOutcome.TERMINAL_WRITTEN);

        verify(groups).cancelChain(7L);
        verify(tasks).requestStopByRun(RUN);
        verify(mapper).cancelWaitGroups(RUN);
        verify(mapper).cancelRecoveryNotifications(RUN);
        verify(mapper).cancelAllUnfinishedWorkItems(RUN, "run_explicitly_canceled");
    }

    @Test
    void terminalWriteMissIsReportedAsNotApplied() {
        when(mapper.lockRunForUser(RUN, USER)).thenReturn(run("EXECUTING", null));
        when(mapper.cancelTerminalNoActiveTask(eq(RUN), eq(USER), eq(SNAPSHOT), any())).thenReturn(0);

        assertThat(store.cancelRunInTransaction(RUN, USER, SNAPSHOT, null))
                .isEqualTo(CancelRunOutcome.NOT_APPLIED);
        verify(tasks, never()).requestStopByRun(any());
    }

    @Test
    void alreadyTerminalRunIsLeftAlone() {
        when(mapper.lockRunForUser(RUN, USER)).thenReturn(run("CANCELED", null));

        assertThat(store.cancelRunInTransaction(RUN, USER, SNAPSHOT, null))
                .isEqualTo(CancelRunOutcome.NOT_APPLIED);
        verify(tasks, never()).requestStopByRun(any());
    }

    private static CancelableRun run(String status, String anchorOperationId) {
        CancelableRun run = new CancelableRun();
        run.setRunId(RUN);
        run.setUserId(USER);
        run.setStatus(status);
        run.setAnchorOperationId(anchorOperationId);
        return run;
    }
}
