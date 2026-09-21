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
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.idempotency.RunIdempotencyConflictException;
import world.willfrog.agent.platform.idempotency.RunRequestDigest;
import world.willfrog.agent.platform.idempotency.RunRequestFingerprint;
import world.willfrog.agent.platform.mapper.AgentRunEventMapper;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.prompt.PromptRunSelection;

import java.time.LocalDate;

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

    private AgentRunEventService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new AgentRunEventService(runMapper, eventMapper, eventRedisStore, objectMapper,
                redisTemplate, llmLocalConfigLoader, messageService, mockPromptService, transactionManager);
        org.mockito.Mockito.lenient().when(mockPromptService.snapshotPromptSelection(
                        anyString(), anyString(), any())).thenReturn(new PromptRunSelection(
                PromptRunSelection.SCHEMA_VERSION, "default-v1", "control", "bundle-digest",
                "capability-digest", LocalDate.of(2025, 2, 3)));
        org.mockito.Mockito.lenient().when(mockPromptService.snapshotDataFreshness()).thenReturn(null);
    }

    @Test
    void sameKeyAndSameDigestReadBackTheOriginalRun() {
        AgentRun existing = existingRun("r-original", digest("hello", "{}"));
        when(runMapper.findByUserIdempotencyKey(USER, KEY)).thenReturn(existing);

        AgentRunEventService.RunCreation creation = service.createRun(USER, "hello", "{}", KEY, "m", "e",
                false, "openrouter", 2, false, "{}", DEPLOYMENT_ID, DEPLOYMENT_GENERATION_ID, false, false);

        assertThat(creation.run()).isSameAs(existing);
        assertThat(creation.created())
                .as("读回原来那条 Run 不算新建：上层据此不再准入、不再启动")
                .isFalse();
        verify(runMapper, never()).insert(any());
        verify(eventMapper, never()).insert(any());
        verify(messageService, never()).createInitialMessage(anyString(), anyString());
    }

    @Test
    void sameKeyWithDifferentContentIsRejectedWithoutCreatingASecondRun() {
        AgentRun existing = existingRun("r-original", digest("hello", "{}"));
        when(runMapper.findByUserIdempotencyKey(USER, KEY)).thenReturn(existing);

        assertThatThrownBy(() -> service.createRun(USER, "hello but different", "{}", KEY, "m", "e",
                false, "openrouter", 2, false, "{}", DEPLOYMENT_ID, DEPLOYMENT_GENERATION_ID, false, false))
                .isInstanceOf(RunIdempotencyConflictException.class)
                .hasMessageContaining("r-original");
        verify(runMapper, never()).insert(any());
    }

    @Test
    void legacyRunWithoutDigestIsNotReused() {
        AgentRun existing = existingRun("r-legacy", null);
        when(runMapper.findByUserIdempotencyKey(USER, KEY)).thenReturn(existing);

        assertThatThrownBy(() -> service.createRun(USER, "hello", "{}", KEY, "m", "e", false,
                "openrouter", 2, false, "{}", DEPLOYMENT_ID, DEPLOYMENT_GENERATION_ID, false, false))
                .as("存量 Run 没有摘要，凭现在的数据算不出它当时是什么请求，只能拒绝")
                .isInstanceOf(RunIdempotencyConflictException.class);
    }

    @Test
    void concurrentDuplicateReadsBackTheWinnerInsteadOfFailing() {
        AgentRun winner = existingRun("r-winner", digest("hello", "{}"));
        when(runMapper.findByUserIdempotencyKey(USER, KEY)).thenReturn(null, winner);
        when(runMapper.insert(any())).thenThrow(new DuplicateKeyException("同键并发"));

        AgentRunEventService.RunCreation creation = service.createRun(USER, "hello", "{}", KEY, "m", "e",
                false, "openrouter", 2, false, "{}", DEPLOYMENT_ID, DEPLOYMENT_GENERATION_ID, false, false);

        assertThat(creation.run()).isSameAs(winner);
        assertThat(creation.created()).isFalse();
        verify(runMapper).insert(any());
    }

    @Test
    void blankKeyBehavesLikeNoKeyAtAll() {
        stubFreshCreate();

        AgentRunEventService.RunCreation creation = service.createRun(USER, "hello", "{}", "   ", "m", "e",
                false, "openrouter", 2, false, "{}", DEPLOYMENT_ID, DEPLOYMENT_GENERATION_ID, false, false);

        assertThat(creation.created()).isTrue();
        verify(runMapper, never()).findByUserIdempotencyKey(anyString(), anyString());
        ArgumentCaptor<AgentRun> captor = ArgumentCaptor.forClass(AgentRun.class);
        verify(runMapper).insert(captor.capture());
        assertThat(captor.getValue().getIdempotencyKey()).isNull();
        assertThat(captor.getValue().getRequestDigest()).isNull();
    }

    @Test
    void newRunWithKeyStoresKeyAndDigest() {
        stubFreshCreate();

        AgentRunEventService.RunCreation creation = service.createRun(USER, "hello", "{}", KEY, "m", "e",
                false, "openrouter", 2, false, "{}", DEPLOYMENT_ID, DEPLOYMENT_GENERATION_ID, false, false);

        assertThat(creation.created()).isTrue();
        ArgumentCaptor<AgentRun> captor = ArgumentCaptor.forClass(AgentRun.class);
        verify(runMapper).insert(captor.capture());
        assertThat(captor.getValue().getIdempotencyKey()).isEqualTo(KEY);
        assertThat(captor.getValue().getRequestDigest()).isEqualTo(digest("hello", "{}"));
    }

    /**
     * 接收事实先落库、再投射到事件流：反过来会在库回滚时留下不存在的 Run 的事件。
     *
     * <p>投射放在提交之后，所以顺序必须是「先数据库、后 Redis」。创建这条路走的是同一份实现
     * （{@code persistEvent} 加 {@code projectAppended}），这里量的就是它。</p>
     */
    @Test
    void theReceivedFactIsPersistedBeforeItIsProjected() {
        stubFreshCreate();

        service.createRun(USER, "hello", "{}", KEY, "m", "e", false, "openrouter", 2, false, "{}",
                DEPLOYMENT_ID, DEPLOYMENT_GENERATION_ID, false, false);

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(eventMapper, eventRedisStore);
        inOrder.verify(eventMapper).insert(any());
        inOrder.verify(eventRedisStore).append(any());
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
        org.mockito.Mockito.lenient().when(eventMapper.insert(any())).thenReturn(1);
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
