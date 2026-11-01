package world.willfrog.agentlangchain.control.dualpool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import world.willfrog.agent.platform.childrun.ChildRunIntentStore;
import world.willfrog.agent.platform.childrun.ChildRunIntentView;
import world.willfrog.agent.platform.capacity.SchedulerStateStore;
import world.willfrog.agent.platform.coordination.RunCoordination;
import world.willfrog.agent.platform.coordination.RunCoordinationDeferReason;
import world.willfrog.agent.platform.coordination.RunCoordinationStore;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.lease.ProcessInstanceIdentity;
import world.willfrog.agent.platform.lease.RunServiceLease;
import world.willfrog.agent.platform.lease.RunServiceLeaseStore;
import world.willfrog.agent.platform.lease.ServiceOwnershipScope;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.event.AgentRunFinalizationService;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.platform.service.AgentRunStateStore;
import world.willfrog.agent.platform.workitem.NodeWorkItemMutationResult;
import world.willfrog.agent.platform.workitem.NodeWorkItem;
import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;
import world.willfrog.agent.platform.workitem.NodeWorkItemState;
import world.willfrog.agent.platform.workitem.NodeWorkItemStore;
import world.willfrog.agent.platform.workitem.ServiceOwnershipFence;
import world.willfrog.agentlangchain.control.LegacyRunHandoff;
import world.willfrog.agentlangchain.execution.DualPoolWaitGroupNodeExecutor;
import world.willfrog.agentlangchain.execution.FreshRunPipeline;
import world.willfrog.agentlangchain.execution.LangchainTodoNodeExecutor;
import world.willfrog.agentlangchain.gateway.LaneScopeGateway;
import world.willfrog.agentlangchain.gateway.RunOwnershipGateway;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import world.willfrog.agentlangchain.control.dualpool.TestSchedulerSettings;

/**
 * 共享候选按行路由：三个版本排在同一份候选里，选出来之后按每一行冻结的版本分别对待。
 *
 * <p>候选共享、能不能接手不共享。旧版本的 Run 只有拿到服务所有权之后才进旧入口；所有权在别的
 * 进程手上时这一轮不接手，并按所有权原因把它推后——不推后它会一直停在候选页首，页数一满，
 * 排在它后面的双池 Run 永远看不见。版本按 Run 主表上冻结的那一个走，资格行上的只是镜像。</p>
 */
class DatabaseDualPoolWorkHandlerRoutingTest {

    private static final String TEST_INSTANCE = "agent@localhost@"
            + ProcessHandle.current().pid() + "@test-instance";
    private static final String DEPLOYMENT_ID = "beta-test";
    private static final String GENERATION = "gen-" + "a".repeat(64);

    private RunCoordinationStore coordinationStore;
    private SchedulerStateStore stateStore;
    private AgentRunMapper runMapper;
    private NodeWorkItemStore workItemStore;
    private DualPoolRunAdmissionRegistry admissionRegistry;
    private SchedulerVersionPolicy versionPolicy;
    private RunServiceLeaseStore leaseStore;
    private LegacyRunHandoff legacyHandoff;
    /** 读数用的登记表：这一份用例顺带量「节点领取有效期、两次重试与服务所有权有效期」有没有登记在用的值。 */
    private final FrozenEffectiveSettings frozenEffectiveSettings = new FrozenEffectiveSettings();

    private DatabaseDualPoolWorkHandler handler;
    private FreshRunPipeline freshRunPipeline;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        coordinationStore = Mockito.mock(RunCoordinationStore.class);
        stateStore = Mockito.mock(SchedulerStateStore.class);
        runMapper = Mockito.mock(AgentRunMapper.class);
        workItemStore = Mockito.mock(NodeWorkItemStore.class);
        admissionRegistry = Mockito.mock(DualPoolRunAdmissionRegistry.class);
        versionPolicy = Mockito.mock(SchedulerVersionPolicy.class);
        leaseStore = Mockito.mock(RunServiceLeaseStore.class);
        legacyHandoff = Mockito.mock(LegacyRunHandoff.class);
        ProcessInstanceIdentity identity = Mockito.mock(ProcessInstanceIdentity.class);
        ObjectProvider<LegacyRunHandoff> handoffProvider = Mockito.mock(ObjectProvider.class);
        Mockito.lenient().when(admissionRegistry.snapshotRunIds()).thenReturn(Set.of());
        Mockito.lenient().when(stateStore.currentRound(any())).thenReturn(1L);
        Mockito.lenient().when(identity.value()).thenReturn(TEST_INSTANCE);
        Mockito.lenient().when(handoffProvider.getIfAvailable()).thenReturn(legacyHandoff);
        Mockito.lenient().when(versionPolicy.isDualPoolFamily(any(world.willfrog.agent.platform.entity.AgentRun.class)))
                .thenReturn(true);
        freshRunPipeline = Mockito.mock(FreshRunPipeline.class);
        handler = new DatabaseDualPoolWorkHandler(
                runMapper,
                freshRunPipeline,
                workItemStore,
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
                leaseStore,
                identity,
                handoffProvider,
                // 上限与水位按默认值；这一份用例量的是路由，不是参数解析。
                TestSchedulerSettings.propertyOnly(),
                null,
                300, 1000, 5000, 120, frozenEffectiveSettings);
    }





    /** 本进程已经握着这条 Run 的服务所有权：读一次就够，不必再写一遍。 */
    private void ownedByThisProcess(String runId) {
        RunServiceLease lease = new RunServiceLease(runId, TEST_INSTANCE, 1L,
                OffsetDateTime.now().minusMinutes(1), OffsetDateTime.now(),
                OffsetDateTime.now().plusMinutes(2));
        Mockito.lenient().when(leaseStore.find(runId)).thenReturn(Optional.of(lease));
    }














    private ChildRunIntentStore childRunIntents;
    private AgentRunStateStore runStateStore;
    private AgentRunFinalizationService finalizationService;

    @SuppressWarnings("unchecked")
    private DatabaseDualPoolWorkHandler handlerWith(RunOwnershipGateway ownership,
                                                    AgentRunEventService events,
                                                    DualPoolSchedulerSettings settings) {
        ProcessInstanceIdentity identity = Mockito.mock(ProcessInstanceIdentity.class);
        ObjectProvider<LegacyRunHandoff> handoffProvider = Mockito.mock(ObjectProvider.class);
        Mockito.lenient().when(identity.value()).thenReturn(TEST_INSTANCE);
        Mockito.lenient().when(handoffProvider.getIfAvailable()).thenReturn(legacyHandoff);
        runStateStore = Mockito.mock(AgentRunStateStore.class);
        finalizationService = Mockito.mock(AgentRunFinalizationService.class);
        return new DatabaseDualPoolWorkHandler(
                runMapper,
                freshRunPipeline,
                workItemStore,
                Mockito.mock(NodeWorkPlanAdapter.class),
                Mockito.mock(LangchainTodoNodeExecutor.class),
                events,
                new ObjectMapper(),
                Mockito.mock(DualPoolDispatcher.class),
                admissionRegistry,
                versionPolicy,
                Mockito.mock(DualPoolToolJobCoordinator.class),
                Mockito.mock(DualPoolWaitGroupNodeExecutor.class),
                coordinationStore,
                stateStore,
                leaseStore,
                identity,
                handoffProvider,
                settings,
                ownership,
                childRunIntents = Mockito.mock(ChildRunIntentStore.class),
                runStateStore,
                finalizationService,
                300, 1000, 5000, 120, new FrozenEffectiveSettings());
    }

    private static RunCoordination candidate(String runId, String version) {
        RunCoordination coordination = new RunCoordination();
        coordination.setRunId(runId);
        coordination.setSchedulerVersion(version);
        coordination.setPlanGeneration(0);
        coordination.setCoordinationServedRound(0L);
        coordination.setNextVisibleAt(OffsetDateTime.now());
        return coordination;
    }

    private static AgentRun run(String runId, String version) {
        AgentRun run = new AgentRun();
        run.setId(runId);
        run.setUserId("user-routing");
        run.setStatus(AgentRunStatus.EXECUTING);
        run.setSchedulerVersion(version);
        run.setPlanGeneration(0);
        run.setRunControlVersion(0L);
        return run;
    }
}
