package world.willfrog.agentlangchain.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.event.AgentRunFinalizedEvent;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentityProvider;
import world.willfrog.agent.platform.workitem.SchedulerVersion;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static world.willfrog.agentlangchain.acceptance.AcceptanceFixtureExecutionException.refuse;

/**
 * 执行层按 Run 找夹具：不带编号的走原路，带编号的要么拿到脚本模型，要么当场报错。
 *
 * <p>消费位置不在这个组件里了（在数据库的认领表里），所以这里要核的是「给的是脚本模型、
 * 每次调用要自己带身份、旧调度版本与读不出来的脚本一律拒绝」。</p>
 */
class AcceptanceFixtureModelRegistryTest {

    private static final String LANE = "beta-lane-0910";
    private static final String GENERATION = "gen-" + "a".repeat(64);
    private static final String GOOD_SCRIPT = """
            {"turns":[{"for":{"stage":"planning","planPhase":"strategy"},"text":"计划"},
                      {"for":{"stage":"answer"},"text":"答案"}]}
            """;

    private final AcceptanceFixtureStore store = mock(AcceptanceFixtureStore.class);
    private final FixtureCallStore callStore = mock(FixtureCallStore.class);
    private final DeploymentIdentityProvider identityProvider = mock(DeploymentIdentityProvider.class);
    private final ObjectMapper objectMapper = new ObjectMapper();













    private AcceptanceFixtureModelRegistry registry() {
        when(identityProvider.current()).thenReturn(new DeploymentIdentity(LANE, GENERATION));
        return new AcceptanceFixtureModelRegistry(
                new AcceptanceFixtureResolver(store, identityProvider, objectMapper), callStore, objectMapper);
    }

    private AgentRun fixtureRun(String fixtureId) {
        return run("run-1", "{\"execution_mode\":\"DAG\",\"acceptanceFixtureId\":\"" + fixtureId + "\"}");
    }

    private AgentRun run(String runId, String contextJson) {
        AgentRun run = new AgentRun();
        run.setId(runId);
        run.setUserId("7");
        run.setDeploymentId(LANE);
        run.setDeploymentGenerationId(GENERATION);
        // 夹具只在「一个分段一次模型调用」的调度器版本下跑：默认按那个版本造数据。
        run.setSchedulerVersion(SchedulerVersion.DUAL_POOL_V2.name());
        try {
            run.setExt(objectMapper.writeValueAsString(Map.of("context_json", contextJson)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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
