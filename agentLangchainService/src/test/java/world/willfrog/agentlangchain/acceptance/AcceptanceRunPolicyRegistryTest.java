package world.willfrog.agentlangchain.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.event.AgentRunFinalizedEvent;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentityProvider;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

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
 * 按 Run 取回放行策略：不带夹具编号的 Run 拿到空，带编号的按夹具里的那份策略走。
 *
 * <p>这里量三件事：普通 Run 一步都不查；同一份策略读两次拿到同一个对象（策略是不可变值，解析一次
 * 就够了）；夹具在跑的中途失效时读策略当场报错。控制 Run 第一次读过之后，行过期仍返回冻结的那一版。</p>
 */
class AcceptanceRunPolicyRegistryTest {

    private static final String LANE = "beta-lane-0910";
    private static final String GENERATION = "gen-" + "a".repeat(64);
    private static final String POLICY =
            "{\"rules\":[{\"for\":{\"planGeneration\":7,\"nodeId\":\"n1\",\"nodeAttempt\":1,\"segmentSequence\":0,\"modelTurn\":0,\"memberSeq\":0},\"holdUntilPoint\":\"point-a\"}]}";
    private static final String POLICY_HOLDING_POINT_A = "point-a";

    private final AcceptanceFixtureStore store = mock(AcceptanceFixtureStore.class);
    private final DeploymentIdentityProvider identityProvider = mock(DeploymentIdentityProvider.class);
    private final FixtureRuleHitStore ruleHitStore = mock(FixtureRuleHitStore.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

















    private AcceptanceRunPolicyRegistry registry() {
        when(identityProvider.current()).thenReturn(new DeploymentIdentity(LANE, GENERATION));
        return new AcceptanceRunPolicyRegistry(
                new AcceptanceFixtureResolver(store, identityProvider, objectMapper), objectMapper,
                ruleHitStore);
    }

    private void row(String fixtureId, String policyJson) {
        when(store.find(LANE, GENERATION, fixtureId)).thenReturn(Optional.of(
                new AcceptanceFixtureRow(fixtureId, "scenario-a", true,
                        OffsetDateTime.now().minusMinutes(5), OffsetDateTime.now().plusHours(1),
                        null, null, policyJson)));
    }

    private AgentRun fixtureRun(String fixtureId) throws Exception {
        return fixtureRun("run-1", fixtureId);
    }

    private AgentRun fixtureRun(String runId, String fixtureId) throws Exception {
        return run(runId, "{\"execution_mode\":\"DAG\",\"acceptanceFixtureId\":\"" + fixtureId + "\"}");
    }

    private AgentRun controlRun(String controlId) throws Exception {
        return run("run-1", "{\"execution_mode\":\"DAG\",\"acceptanceControlId\":\"" + controlId + "\"}");
    }

    private AgentRun run(String runId, String contextJson) throws Exception {
        AgentRun run = new AgentRun();
        run.setId(runId);
        run.setUserId("7");
        run.setDeploymentId(LANE);
        run.setDeploymentGenerationId(GENERATION);
        run.setExt(objectMapper.writeValueAsString(Map.of("context_json", contextJson)));
        return run;
    }
}
