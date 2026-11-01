package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.childrun.ChildRunAcceptanceControls;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.entity.AgentRunEvent;
import world.willfrog.agent.platform.lease.ProcessInstanceIdentity;
import world.willfrog.agent.platform.lease.RunServiceLease;
import world.willfrog.agent.platform.lease.RunServiceLeaseStore;
import world.willfrog.agent.platform.mapper.AgentRunEventMapper;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.prompt.PromptRunSelection;
import world.willfrog.agent.workflow.PlanExecutionMode;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentRunEventServiceTest {

    private static final String DEPLOYMENT_ID = "stable";
    private static final String DEPLOYMENT_GENERATION_ID = "gen-" + "a".repeat(64);
    private static final String INSTANCE_OWNER = "test-instance";

    @Mock
    private AgentRunMapper runMapper;
    @Mock
    private AgentRunEventMapper eventMapper;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private AgentLlmLocalConfigLoader llmLocalConfigLoader;
    @Mock
    private AgentMessageService messageService;
    @Mock
    private AgentRunEventRedisStore eventRedisStore;
    @Mock
    private AgentPromptService mockPromptService;
    @Mock
    private org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Mock
    private RunServiceLeaseStore leaseStore;
    @Mock
    private ProcessInstanceIdentity instanceIdentity;

    private world.willfrog.agent.platform.coordination.RunCoordinationStore coordinationStore;
    private AgentRunEventService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        org.mockito.Mockito.lenient().when(llmLocalConfigLoader.hotConfigIsAuthoritative()).thenReturn(true);
        org.mockito.Mockito.lenient().when(llmLocalConfigLoader.current()).thenReturn(Optional.empty());
        coordinationStore = org.mockito.Mockito.mock(world.willfrog.agent.platform.coordination.RunCoordinationStore.class);
        org.mockito.Mockito.lenient().when(coordinationStore.ensure(anyString())).thenReturn(true);
        org.mockito.Mockito.lenient().when(instanceIdentity.value()).thenReturn(INSTANCE_OWNER);
        org.mockito.Mockito.lenient().when(leaseStore.acquire(anyString(), anyString(), any(Duration.class)))
                .thenAnswer(invocation -> Optional.of(ownedLease(
                        invocation.getArgument(0), invocation.getArgument(1))));
        service = new AgentRunEventService(
                runMapper,
                eventMapper,
                eventRedisStore,
                objectMapper,
                redisTemplate,
                llmLocalConfigLoader,
                messageService,
                mockPromptService,
                transactionManager,
                coordinationStore,
                leaseStore,
                instanceIdentity
        );
        ReflectionTestUtils.setField(service, "serviceLeaseTtlSeconds", 120L);
        var experimentPreparation = org.mockito.Mockito.mock(
                world.willfrog.agent.platform.experiment.ExperimentRunPreparation.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service,
                "experimentRunPreparation", experimentPreparation);
        org.mockito.Mockito.lenient().when(experimentPreparation.freezeContext(anyString()))
                .thenAnswer(invocation -> objectMapper.valueToTree(java.util.Map.of(
                        "root", java.util.Map.of("root_id", invocation.getArgument(0)),
                        "target", java.util.Map.of("target_scope_instance_id", "racing-main"))));
        stubCanonicalEventReadBack(eventMapper);
    }

    /**
     * 让「写进去的那一条」和「读回来的那一条」是同一份内容。
     *
     * <p>投射按库里读回来的那一行做，所以这两条在测试里要指向同一份内容，断言看的才是投出去的东西。
     * 真库上时间与负载文本由库决定（写入不写 created_at、负载存成 jsonb）——那部分行为由真库用例量。</p>
     */
    private static void mirrorPersistedEvent(java.util.Map<String, AgentRunEvent> persisted,
                                             AgentRunEvent event) {
        if (event != null && event.getRunId() != null && event.getSeq() != null) {
            persisted.put(event.getRunId() + ":" + event.getSeq(), event);
        }
    }

    private static void stubCanonicalEventReadBack(AgentRunEventMapper eventMapper) {
        java.util.Map<String, AgentRunEvent> persisted = new java.util.concurrent.ConcurrentHashMap<>();
        org.mockito.Mockito.lenient().doAnswer(invocation -> {
            mirrorPersistedEvent(persisted, invocation.getArgument(0));
            return 1;
        }).when(eventMapper).insert(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.lenient().doAnswer(invocation -> {
            mirrorPersistedEvent(persisted, invocation.getArgument(0));
            return 1;
        }).when(eventMapper).insertOnce(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.lenient()
                .when(eventMapper.findByRunIdAndSeq(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(invocation -> persisted.get(
                        invocation.getArgument(0) + ":" + invocation.getArgument(1)));
    }


    private AgentRun parentWithContext(String contextJson) {
        AgentRun parent = new AgentRun();
        parent.setId("parent-run");
        parent.setUserId("user-1");
        parent.setDeploymentId(DEPLOYMENT_ID);
        parent.setDeploymentGenerationId(DEPLOYMENT_GENERATION_ID);
        parent.setSchedulerVersion("DUAL_POOL_V2");
        parent.setExt("{\"context_json\":" + objectMapper.valueToTree(contextJson) + "}");
        return parent;
    }

    private AgentRun createChild(AgentRun parent, String childId,
                                 ChildRunAcceptanceControls.MemberIdentity identity) {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("agent:run:event_seq:" + childId)).thenReturn(1L);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            return service.createChildRun(parent, childId, parent.getId(), "calculate", "",
                    null, null, 6, identity);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    private com.fasterxml.jackson.databind.JsonNode childContext(AgentRun run) throws Exception {
        return objectMapper.readTree(objectMapper.readTree(run.getExt()).path("context_json").asText());
    }

    private static ChildRunAcceptanceControls.MemberIdentity member(int plan, String node, int attempt,
                                                                     int segment, int turn, int sequence,
                                                                     String call) {
        return new ChildRunAcceptanceControls.MemberIdentity(plan, node, attempt, segment, turn,
                sequence, call);
    }

















    private void createWorkspaceTestRun(String key) {
        service.createRun("u-workspace-hot", "hello", "{}", key, "m", "e",
                false, "openrouter", 2, false, "{}", DEPLOYMENT_ID,
                DEPLOYMENT_GENERATION_ID, false, false);
    }





    private AgentRun run(String runId, String userId) {
        AgentRun run = new AgentRun();
        run.setId(runId);
        run.setUserId(userId);
        return run;
    }

    private static RunServiceLease ownedLease(String runId, String owner) {
        OffsetDateTime now = OffsetDateTime.now();
        return new RunServiceLease(runId, owner, 1L, now, now, now.plusMinutes(2));
    }
}
