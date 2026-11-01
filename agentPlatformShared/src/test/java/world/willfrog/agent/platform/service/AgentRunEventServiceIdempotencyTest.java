package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.entity.AgentRunEvent;
import world.willfrog.agent.platform.idempotency.RunIdempotencyConflictException;
import world.willfrog.agent.platform.idempotency.RunRequestDigest;
import world.willfrog.agent.platform.idempotency.RunRequestFingerprint;
import world.willfrog.agent.platform.lease.ProcessInstanceIdentity;
import world.willfrog.agent.platform.lease.RunServiceLease;
import world.willfrog.agent.platform.lease.RunServiceLeaseStore;
import world.willfrog.agent.platform.mapper.AgentRunEventMapper;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.prompt.PromptRunSelection;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 创建 Run 的幂等行为：带键时按「用户 + 键」去重，同键同摘要读回原 Run，同键异摘要直接拒绝，
 * 两种都不新建第二条；不带键（或只给空白）时行为与原来完全一样。
 */
@ExtendWith(MockitoExtension.class)
class AgentRunEventServiceIdempotencyTest {

    private static final String USER = "u-idem";
    private static final String KEY = "idem-key-1";
    private static final String DEPLOYMENT_ID = "stable";
    private static final String DEPLOYMENT_GENERATION_ID = "gen-" + "b".repeat(64);

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
        coordinationStore = org.mockito.Mockito.mock(world.willfrog.agent.platform.coordination.RunCoordinationStore.class);
        org.mockito.Mockito.lenient().when(coordinationStore.ensure(anyString())).thenReturn(true);
        org.mockito.Mockito.lenient().when(instanceIdentity.value()).thenReturn("test-instance");
        org.mockito.Mockito.lenient().when(leaseStore.acquire(anyString(), anyString(), any(Duration.class)))
                .thenAnswer(invocation -> {
                    OffsetDateTime now = OffsetDateTime.now();
                    return Optional.of(new RunServiceLease(
                            invocation.getArgument(0), invocation.getArgument(1), 1L,
                            now, now, now.plusMinutes(2)));
                });
        service = new AgentRunEventService(runMapper, eventMapper, eventRedisStore, objectMapper,
                redisTemplate, llmLocalConfigLoader, messageService, mockPromptService, transactionManager,
                coordinationStore, leaseStore, instanceIdentity);
        var experimentPreparation = org.mockito.Mockito.mock(
                world.willfrog.agent.platform.experiment.ExperimentRunPreparation.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service,
                "experimentRunPreparation", experimentPreparation);
        org.mockito.Mockito.lenient().when(experimentPreparation.freezeContext(anyString()))
                .thenAnswer(invocation -> objectMapper.valueToTree(java.util.Map.of(
                        "root", java.util.Map.of("root_id", invocation.getArgument(0)),
                        "target", java.util.Map.of("target_scope_instance_id", "racing-main"))));
        org.mockito.Mockito.lenient().when(mockPromptService.snapshotDataFreshness()).thenReturn(null);
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










    /**
     * 新建 Run 这条路上用到的桩。两种情形（带键与不带键）走到的分支略有不同，
     * 共用一份桩并把它们设成宽松：用不到的那几个不算测试失败的理由。
     */
    private void stubFreshCreate() {
        org.mockito.Mockito.lenient()
                .when(runMapper.findByUserIdempotencyKey(anyString(), anyString())).thenReturn(null);
        org.mockito.Mockito.lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        org.mockito.Mockito.lenient().when(valueOperations.increment(anyString())).thenReturn(1L);
        AgentRun created = new AgentRun();
        created.setId("r-new");
        created.setUserId(USER);
        org.mockito.Mockito.lenient()
                .when(runMapper.findByIdAndUserForDeployment(
                        anyString(), anyString(), anyString(), anyString()))
                .thenReturn(created);
    }

    private static String digest(String message, String contextJson) {
        return RunRequestDigest.digest(new RunRequestFingerprint(USER, message, contextJson, "m", "e",
                "openrouter", false, 2, false, false, "{}"), new ObjectMapper());
    }

    private static AgentRun existingRun(String runId, String requestDigest) {
        AgentRun run = new AgentRun();
        run.setId(runId);
        run.setUserId(USER);
        run.setRequestDigest(requestDigest);
        return run;
    }
}
