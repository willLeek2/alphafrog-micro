package world.willfrog.agentlangchain.control.dualpool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import world.willfrog.agent.platform.capacity.SchedulerStateStore;
import world.willfrog.agent.platform.coordination.RunCoordination;
import world.willfrog.agent.platform.coordination.RunCoordinationStore;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.platform.workitem.NodeWorkItemStore;
import world.willfrog.agentlangchain.execution.DualPoolWaitGroupNodeExecutor;
import world.willfrog.agentlangchain.execution.FreshRunPipeline;
import world.willfrog.agentlangchain.execution.LangchainTodoNodeExecutor;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 共享候选按行路由：三个版本排在同一份候选里，选出来之后按每一行冻结的版本分别对待。
 *
 * <p>候选共享、能不能接手不共享。旧版本的 Run 这一轮只记账、不动它的任何状态：共享分发器手上
 * 没有「另一个进程也能核对、会过期、能原子转交」的所有权事实，凭一条候选就接手，会在滚动部署
 * 新旧并存的窗口里对着旧进程正在跑的图再启动一次。版本读不出来的行同理，一律失败关闭。</p>
 */
class DatabaseDualPoolWorkHandlerRoutingTest {

    private RunCoordinationStore coordinationStore;
    private SchedulerStateStore stateStore;
    private AgentRunMapper runMapper;
    private DualPoolRunAdmissionRegistry admissionRegistry;
    private SchedulerVersionPolicy versionPolicy;
    private DatabaseDualPoolWorkHandler handler;

    @BeforeEach
    void setUp() {
        coordinationStore = Mockito.mock(RunCoordinationStore.class);
        stateStore = Mockito.mock(SchedulerStateStore.class);
        runMapper = Mockito.mock(AgentRunMapper.class);
        admissionRegistry = Mockito.mock(DualPoolRunAdmissionRegistry.class);
        versionPolicy = Mockito.mock(SchedulerVersionPolicy.class);
        Mockito.lenient().when(admissionRegistry.snapshotRunIds()).thenReturn(Set.of());
        Mockito.lenient().when(stateStore.currentRound(any())).thenReturn(1L);
        handler = new DatabaseDualPoolWorkHandler(
                runMapper,
                Mockito.mock(FreshRunPipeline.class),
                Mockito.mock(NodeWorkItemStore.class),
                Mockito.mock(NodeWorkPlanAdapter.class),
                Mockito.mock(LangchainTodoNodeExecutor.class),
                Mockito.mock(AgentRunEventService.class),
                new ObjectMapper(),
                Mockito.mock(DualPoolDispatcher.class),
                admissionRegistry,
                versionPolicy,
                Mockito.mock(DualPoolToolJobCoordinator.class),
                Mockito.mock(DualPoolWaitGroupNodeExecutor.class),
                coordinationStore,
                stateStore,
                300, 256, 8, 128, 96, 1000, 5000);
    }

    @Test
    void legacyCandidatesAreCountedButNotTaken() {
        when(coordinationStore.scanDue(10)).thenReturn(List.of(candidate("run-legacy", "LEGACY")));

        assertThat(handler.scanRunnableRuns(10))
                .as("旧版本的 Run 这一轮不交给任何人执行")
                .isEmpty();
        verify(runMapper, never()).findById(anyString());
        assertThat(handler.routingSnapshot())
                .as("接不了手这件事要看得见：看不到就会以为它只是还没轮到")
                .containsEntry("legacyCandidatesNotTakenTotal", 1L);
    }

    @Test
    void unknownVersionCandidatesAreIsolated() {
        when(coordinationStore.scanDue(10)).thenReturn(List.of(candidate("run-unknown", "DUAL_POOL_V9")));

        assertThat(handler.scanRunnableRuns(10)).isEmpty();
        verify(runMapper, never()).findById(anyString());
        assertThat(handler.routingSnapshot()).containsEntry("routingIsolatedTotal", 1L);
    }

    @Test
    void admittedDualPoolCandidatesBecomeHints() {
        when(coordinationStore.scanDue(10)).thenReturn(List.of(candidate("run-v2", "DUAL_POOL_V2")));
        AgentRun run = dualPoolRun("run-v2", "DUAL_POOL_V2");
        when(runMapper.findById("run-v2")).thenReturn(run);
        when(versionPolicy.isDualPoolFamily(run)).thenReturn(true);
        when(admissionRegistry.isAdmitted("run-v2")).thenReturn(true);

        assertThat(handler.scanRunnableRuns(10))
                .extracting(RunCoordinationHint::runId)
                .containsExactly("run-v2");
        assertThat(handler.routingSnapshot())
                .as("双池的行不走旧版本那条记账")
                .containsEntry("legacyCandidatesNotTakenTotal", 0L);
    }

    @Test
    void dualPoolCandidatesThisProcessNeverAdmittedStayPut() {
        when(coordinationStore.scanDue(10)).thenReturn(List.of(candidate("run-v2", "DUAL_POOL_V2")));
        AgentRun run = dualPoolRun("run-v2", "DUAL_POOL_V2");
        when(runMapper.findById("run-v2")).thenReturn(run);
        when(versionPolicy.isDualPoolFamily(run)).thenReturn(true);
        when(admissionRegistry.isAdmitted("run-v2")).thenReturn(false);

        assertThat(handler.scanRunnableRuns(10))
                .as("库里有资格记录但这个进程没受理它：不凭一次扫描就执行")
                .isEmpty();
    }

    @Test
    void aCandidateWhoseRunChangedFamilyIsReleasedInsteadOfHinted() {
        when(coordinationStore.scanDue(10)).thenReturn(List.of(candidate("run-replaced", "DUAL_POOL_V2")));
        AgentRun run = dualPoolRun("run-replaced", "LEGACY");
        when(runMapper.findById("run-replaced")).thenReturn(run);
        when(versionPolicy.isDualPoolFamily(run)).thenReturn(false);

        assertThat(handler.scanRunnableRuns(10)).isEmpty();
        verify(admissionRegistry).releaseBusinessPermitIfCurrent(
                Mockito.eq("run-replaced"), Mockito.anyLong(), Mockito.any());
    }

    private static RunCoordination candidate(String runId, String version) {
        RunCoordination coordination = new RunCoordination();
        coordination.setRunId(runId);
        coordination.setSchedulerVersion(version);
        coordination.setPlanGeneration(0);
        coordination.setCoordinationServedRound(0L);
        coordination.setNextVisibleAt(java.time.OffsetDateTime.now());
        return coordination;
    }

    private static AgentRun dualPoolRun(String runId, String version) {
        AgentRun run = new AgentRun();
        run.setId(runId);
        run.setUserId("user-routing");
        run.setStatus(world.willfrog.agent.platform.model.AgentRunStatus.EXECUTING);
        run.setSchedulerVersion(version);
        run.setPlanGeneration(0);
        run.setRunControlVersion(0L);
        return run;
    }
}
