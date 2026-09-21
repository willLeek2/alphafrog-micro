package world.willfrog.agentlangchain.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.event.AgentRunFinalizedEvent;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.workitem.SchedulerVersion;
import world.willfrog.agentlangchain.control.dualpool.DualPoolSchedulerSettings;
import world.willfrog.agentlangchain.control.dualpool.TestSchedulerSettings;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentityProvider;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 执行层按 Run 找夹具：不带编号的走原路，带编号的要么拿到脚本模型，要么当场报错。
 */
class AcceptanceFixtureModelRegistryTest {

    private static final String LANE = "beta-lane-0910";
    private static final String GENERATION = "gen-" + "a".repeat(64);
    private static final String GOOD_SCRIPT = "{\"turns\":[{\"text\":\"计划\"},{\"text\":\"答案\"}]}";

    private final AcceptanceFixtureStore store = mock(AcceptanceFixtureStore.class);
    private final DeploymentIdentityProvider identityProvider = mock(DeploymentIdentityProvider.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void aRunWithoutAFixtureIdNeverLooksAnythingUp() throws Exception {
        AcceptanceFixtureModelRegistry registry = registry();

        Optional<AcceptanceFixtureModelRegistry.ScriptedStage> stage =
                registry.stageForRun(run("run-1", "{\"execution_mode\":\"DAG\"}"));

        assertThat(stage).isEmpty();
        // 普通 Run 连夹具表与部署身份都不碰，行为与从前完全一致。
        verifyNoInteractions(store);
        verifyNoInteractions(identityProvider);
    }

    @Test
    void aFixtureRunGetsTheSameModelForEverySegmentOfThatRun() throws Exception {
        AcceptanceFixtureModelRegistry registry = registry();
        when(store.find(LANE, GENERATION, "fx-1"))
                .thenReturn(Optional.of(row(true, future(), GOOD_SCRIPT)));

        AcceptanceFixtureModelRegistry.ScriptedStage first = registry.stageForRun(fixtureRun("fx-1")).orElseThrow();
        AcceptanceFixtureModelRegistry.ScriptedStage second = registry.stageForRun(fixtureRun("fx-1")).orElseThrow();

        // 同一个实例：脚本的位置跟着 Run 走，一段一个位置会从头上再喂一遍。
        assertThat(second.model()).isSameAs(first.model());
        assertThat(second.fixtureId()).isEqualTo("fx-1");
        assertThat(second.scenarioId()).isEqualTo("scenario-a");
        assertThat(second.modelName()).isEqualTo("acceptance-fixture:fx-1");
        // 每次取用都重新核对夹具还在、已启用、没过期。
        verify(store, times(2)).find(LANE, GENERATION, "fx-1");
    }

    @Test
    void aFixtureThatIsNotThereIsRefused() throws Exception {
        AcceptanceFixtureModelRegistry registry = registry();
        when(store.find(LANE, GENERATION, "fx-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> registry.stageForRun(fixtureRun("fx-1")))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("acceptance_fixture_not_found"));
    }

    @Test
    void aFixtureThatIsNotEnabledOrAlreadyExpiredIsRefused() throws Exception {
        AcceptanceFixtureModelRegistry registry = registry();
        when(store.find(LANE, GENERATION, "fx-1"))
                .thenReturn(Optional.of(row(false, future(), GOOD_SCRIPT)));

        assertThatThrownBy(() -> registry.stageForRun(fixtureRun("fx-1")))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("acceptance_fixture_not_enabled"));

        when(store.find(LANE, GENERATION, "fx-1"))
                .thenReturn(Optional.of(row(true, OffsetDateTime.now().minusMinutes(1), GOOD_SCRIPT)));

        assertThatThrownBy(() -> registry.stageForRun(fixtureRun("fx-1")))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("acceptance_fixture_expired"));
    }

    @Test
    void aFixtureThatExpiresWhileTheRunIsMidwayIsRefusedOnTheNextSegment() throws Exception {
        AcceptanceFixtureModelRegistry registry = registry();
        when(store.find(LANE, GENERATION, "fx-1"))
                .thenReturn(Optional.of(row(true, future(), GOOD_SCRIPT)));
        assertThat(registry.stageForRun(fixtureRun("fx-1"))).isPresent();

        // 夹具在跑的中途到期：下一次取模型必须停住，不能接着用脚本，更不能换真实模型。
        when(store.find(LANE, GENERATION, "fx-1"))
                .thenReturn(Optional.of(row(true, OffsetDateTime.now().minusSeconds(1), GOOD_SCRIPT)));

        assertThatThrownBy(() -> registry.stageForRun(fixtureRun("fx-1")))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("acceptance_fixture_expired"));
    }

    @Test
    void aRunThatWasAlreadyPlannedWithoutALivePositionIsRefused() throws Exception {
        AcceptanceFixtureModelRegistry registry = registry();
        when(store.find(LANE, GENERATION, "fx-1"))
                .thenReturn(Optional.of(row(true, future(), GOOD_SCRIPT)));
        AgentRun run = fixtureRun("fx-1");
        run.setPlanJson("{\"items\":[]}");

        // 进程重启或换了实例：脚本位置重建不了，从头再喂一遍会让后面的回合与实际调用错开。
        assertThatThrownBy(() -> registry.stageForRun(run))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("acceptance_fixture_script_position_lost"));
    }

    @Test
    void aRunFromAnotherLaneIsRefused() throws Exception {
        AcceptanceFixtureModelRegistry registry = registry();
        AgentRun run = fixtureRun("fx-1");
        run.setDeploymentGenerationId("gen-" + "b".repeat(64));

        assertThatThrownBy(() -> registry.stageForRun(run))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("acceptance_fixture_lane_mismatch"));
        verify(store, never()).find(anyString(), anyString(), anyString());
    }

    @Test
    void aContextThatMentionsTheFixtureButCannotBeReadIsRefused() throws Exception {
        AcceptanceFixtureModelRegistry registry = registry();

        assertThatThrownBy(() -> registry.stageForRun(run("run-1", "{\"acceptanceFixtureId\":")))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("acceptance_fixture_invalid"));

        assertThatThrownBy(() -> registry.stageForRun(run("run-1", "{\"acceptanceFixtureId\":\"  \"}")))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("acceptance_fixture_invalid"));
    }

    @Test
    void aMessageThatMerelyMentionsTheFixtureFieldIsStillAnOrdinaryRun() throws Exception {
        AcceptanceFixtureModelRegistry registry = registry();
        AgentRun run = new AgentRun();
        run.setId("run-1");
        run.setUserId("7");
        run.setDeploymentId(LANE);
        run.setDeploymentGenerationId(GENERATION);
        // ext 里还存着用户消息正文：正文里出现这个词不等于这次请求带了夹具编号。
        run.setExt(objectMapper.writeValueAsString(Map.of(
                "message", "acceptanceFixtureId 这个字段是干什么用的？",
                "context_json", "{\"execution_mode\":\"DAG\"}")));

        assertThat(registry.stageForRun(run)).isEmpty();
        verifyNoInteractions(store);
    }

    @Test
    void aScriptThatCannotBeReadIsRefused() throws Exception {
        AcceptanceFixtureModelRegistry registry = registry();
        when(store.find(LANE, GENERATION, "fx-1"))
                .thenReturn(Optional.of(row(true, future(), "{\"turns\":[{\"toolcalls\":[]}]}")));

        assertThatThrownBy(() -> registry.stageForRun(fixtureRun("fx-1")))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("acceptance_fixture_script_invalid"));
    }

    @Test
    void theFixtureOfARunCannotChangeWhileItRuns() throws Exception {
        AcceptanceFixtureModelRegistry registry = registry();
        when(store.find(LANE, GENERATION, "fx-1"))
                .thenReturn(Optional.of(row(true, future(), GOOD_SCRIPT)));
        when(store.find(LANE, GENERATION, "fx-2"))
                .thenReturn(Optional.of(new AcceptanceFixtureRow("fx-2", "scenario-b", true,
                        OffsetDateTime.now(), future(), null, GOOD_SCRIPT, null)));
        assertThat(registry.stageForRun(fixtureRun("fx-1"))).isPresent();

        assertThatThrownBy(() -> registry.stageForRun(fixtureRun("fx-2")))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("acceptance_fixture_identity_changed"));
    }

    @Test
    void thePositionIsDroppedWhenTheRunFinishes() throws Exception {
        AcceptanceFixtureModelRegistry registry = registry();
        when(store.find(LANE, GENERATION, "fx-1"))
                .thenReturn(Optional.of(row(true, future(), GOOD_SCRIPT)));
        AcceptanceFixtureModelRegistry.ScriptedStage first = registry.stageForRun(fixtureRun("fx-1")).orElseThrow();

        registry.evict("run-1");

        AcceptanceFixtureModelRegistry.ScriptedStage afterEviction =
                registry.stageForRun(fixtureRun("fx-1")).orElseThrow();
        assertThat(afterEviction.model()).isNotSameAs(first.model());
    }

    @Test
    void aTerminalEventDropsThePosition() throws Exception {
        AcceptanceFixtureModelRegistry registry = registry();
        when(store.find(LANE, GENERATION, "fx-1"))
                .thenReturn(Optional.of(row(true, future(), GOOD_SCRIPT)));
        AcceptanceFixtureModelRegistry.ScriptedStage first = registry.stageForRun(fixtureRun("fx-1")).orElseThrow();

        registry.onRunFinalized(new AgentRunFinalizedEvent(
                "run-1", 7L, AgentRunStatus.COMPLETED.name(), false));

        assertThat(registry.stageForRun(fixtureRun("fx-1")).orElseThrow().model())
                .isNotSameAs(first.model());
    }

    @Test
    void tooManyTrackedRunsIsRefusedInsteadOfDroppingALiveOne() throws Exception {        AcceptanceFixtureModelRegistry registry = registry();
        when(store.find(anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(row(true, future(), GOOD_SCRIPT)));
        for (int index = 0; index < AcceptanceFixtureModelRegistry.MAX_TRACKED_RUNS; index++) {
            assertThat(registry.stageForRun(fixtureRun("run-" + index, "fx-1"))).isPresent();
        }

        // 到顶时拒绝新的夹具 Run，不去挤掉正在跑的那些：挤掉会让那条 Run 的脚本位置凭空消失。
        assertThatThrownBy(() -> registry.stageForRun(fixtureRun("run-overflow", "fx-1")))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("acceptance_fixture_tracked_runs_full"));
    }

    /**
     * 终态事件走真的 Spring 事件通路把位置放掉。
     *
     * <p>只调方法测不出「监听有没有挂上」：注解删掉、Bean 没注册，直接调也照样通过。这里把注册表
     * 当成 Bean 装起来，事件从上下文发出去，看位置是不是真的放了——放了就会重新建一份，没放就还是
     * 原来那份。</p>
     */
    @Test
    void aTerminalEventDropsThePositionThroughTheRealSpringPath() {
        when(store.find(LANE, GENERATION, "fx-1"))
                .thenReturn(Optional.of(row(true, future(), GOOD_SCRIPT)));
        when(identityProvider.current()).thenReturn(new DeploymentIdentity(LANE, GENERATION));
        AtomicReference<Object> before = new AtomicReference<>();
        AtomicReference<Object> after = new AtomicReference<>();

        new ApplicationContextRunner()
                .withBean(AcceptanceFixtureResolver.class,
                        () -> new AcceptanceFixtureResolver(store, identityProvider, objectMapper))
                .withBean(ObjectMapper.class, () -> objectMapper)
                .withBean(DualPoolSchedulerSettings.class, () -> TestSchedulerSettings.propertyOnly(
                        DualPoolSchedulerSettings.KEY_PER_RUN_UNFINISHED_LIMIT, "1"))
                .withUserConfiguration(AcceptanceFixtureModelRegistry.class)
                .run(context -> {
                    AcceptanceFixtureModelRegistry registry =
                            context.getBean(AcceptanceFixtureModelRegistry.class);
                    before.set(registry.stageForRun(fixtureRun("fx-1")).orElseThrow().model());
                    context.publishEvent(new AgentRunFinalizedEvent(
                            "run-1", 7L, AgentRunStatus.COMPLETED.name(), false));
                    after.set(registry.stageForRun(fixtureRun("fx-1")).orElseThrow().model());
                });

        assertThat(after.get()).as("终态事件之后是重新建的一份，说明监听真的挂上了")
                .isNotSameAs(before.get());
    }

    /**
     * 两个线程同时为同一条 Run 要模型，两边拿到同一份位置。
     *
     * <p>这是脚本能按顺序消费的前提：各拿一份位置就会各自从头喂一遍脚本。</p>
     */
    @Test
    void twoThreadsAskingAtOnceShareOnePosition() throws Exception {
        AcceptanceFixtureModelRegistry registry = registry();
        when(store.find(LANE, GENERATION, "fx-1"))
                .thenReturn(Optional.of(row(true, future(), GOOD_SCRIPT)));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<ScriptedChatModel> ask = () -> {
                start.await();
                return registry.stageForRun(fixtureRun("fx-1")).orElseThrow().model();
            };
            Future<ScriptedChatModel> first = pool.submit(ask);
            Future<ScriptedChatModel> second = pool.submit(ask);
            start.countDown();

            assertThat(first.get(10, TimeUnit.SECONDS)).isSameAs(second.get(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    private AcceptanceFixtureModelRegistry registry() {
        return registry(TestSchedulerSettings.propertyOnly(
                DualPoolSchedulerSettings.KEY_PER_RUN_UNFINISHED_LIMIT, "1"));
    }

    private AcceptanceFixtureModelRegistry registry(DualPoolSchedulerSettings settings) {
        when(identityProvider.current()).thenReturn(new DeploymentIdentity(LANE, GENERATION));
        return new AcceptanceFixtureModelRegistry(
                new AcceptanceFixtureResolver(store, identityProvider, objectMapper), objectMapper,
                settings);
    }

    /**
     * 上限不是 1 就不给脚本：同一个 Run 同时跑两个分段时，两段谁先取到下一回合的回复说不准。
     *
     * <p>这条前提是可以运行期改的热配置，所以要在领脚本之前当场核对，不能靠约定。</p>
     */
    @Test
    void aRunAllowedToHaveTwoWorkItemsInFlightIsRefusedInsteadOfHandedTheScript() throws Exception {
        AcceptanceFixtureModelRegistry loose = registry(TestSchedulerSettings.propertyOnly(
                DualPoolSchedulerSettings.KEY_PER_RUN_UNFINISHED_LIMIT, "2"));
        when(store.find(LANE, GENERATION, "fx-1"))
                .thenReturn(Optional.of(row(true, future(), GOOD_SCRIPT)));

        assertThatThrownBy(() -> loose.stageForRun(fixtureRun("fx-1")))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .satisfies(e -> assertThat(code(e))
                        .isEqualTo("acceptance_fixture_needs_single_in_flight_work_item"))
                .hasMessageContaining(DualPoolSchedulerSettings.KEY_PER_RUN_UNFINISHED_LIMIT);

        // 上限调回 1 之后同一条 Run 照常拿到脚本：拒绝的是配置，不是这条 Run。
        AcceptanceFixtureModelRegistry strict = registry();
        when(store.find(LANE, GENERATION, "fx-1"))
                .thenReturn(Optional.of(row(true, future(), GOOD_SCRIPT)));
        assertThat(strict.stageForRun(fixtureRun("fx-1"))).isPresent();
    }

    /**
     * 旧调度路径下不发脚本：那里一个节点内部是工具循环，一次模型调用换一个工具回合。
     *
     * <p>脚本按「一次模型调用 = 一个回合」排，喂给旧路径会与夹具作者写的先后错开，跑出来的结果
     * 说不清是哪一次验收。版本是可运行期改的泳道热配置，所以按 Run 上冻结的那个版本核对。</p>
     */
    @Test
    void aRunOnTheLegacySchedulerIsRefusedInsteadOfHandedTheScript() throws Exception {
        AcceptanceFixtureModelRegistry registry = registry();
        when(store.find(LANE, GENERATION, "fx-1"))
                .thenReturn(Optional.of(row(true, future(), GOOD_SCRIPT)));
        AgentRun run = fixtureRun("fx-1");
        run.setSchedulerVersion(SchedulerVersion.LEGACY.name());

        assertThatThrownBy(() -> registry.stageForRun(run))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .satisfies(e -> assertThat(code(e))
                        .isEqualTo("acceptance_fixture_scheduler_version_unusable"))
                .hasMessageContaining("一个分段一次模型调用");
    }

    private AgentRun fixtureRun(String fixtureId) throws Exception {
        return fixtureRun("run-1", fixtureId);
    }

    private AgentRun fixtureRun(String runId, String fixtureId) throws Exception {
        return run(runId, "{\"execution_mode\":\"DAG\",\"acceptanceFixtureId\":\"" + fixtureId + "\"}");
    }

    private AgentRun run(String runId, String contextJson) throws Exception {
        AgentRun run = new AgentRun();
        run.setId(runId);
        run.setUserId("7");
        run.setDeploymentId(LANE);
        run.setDeploymentGenerationId(GENERATION);
        // 夹具只在「一个分段一次模型调用」的调度器版本下跑：默认按那个版本造数据。
        run.setSchedulerVersion(SchedulerVersion.DUAL_POOL_V2.name());
        run.setExt(objectMapper.writeValueAsString(Map.of("context_json", contextJson)));
        return run;
    }

    private static AcceptanceFixtureRow row(boolean enabled, OffsetDateTime expiresAt, String scriptJson) {
        return new AcceptanceFixtureRow("fx-1", "scenario-a", enabled,
                enabled ? OffsetDateTime.now().minusMinutes(5) : null, expiresAt, null, scriptJson, null);
    }

    private static OffsetDateTime future() {
        return OffsetDateTime.now().plusHours(1);
    }

    private static String code(Throwable error) {
        return ((AcceptanceFixtureExecutionException) error).code();
    }
}
