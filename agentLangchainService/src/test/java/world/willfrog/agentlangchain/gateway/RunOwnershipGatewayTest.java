package world.willfrog.agentlangchain.gateway;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 认领/受理入口的归属判定：本代际放行、他代际拒绝、部署身份缺失 fail-closed。
 * 业务包不再自己比对部署身份，全部经由本类。
 */
class RunOwnershipGatewayTest {

    private static final String GENERATION = "gen-" + "a".repeat(64);
    private static final String OTHER_GENERATION = "gen-" + "b".repeat(64);

    private final AgentRunMapper runMapper = mock(AgentRunMapper.class);
    private final RunOwnershipGateway gateway = new RunOwnershipGateway(
            GatewayTestFixtures.identityProvider("beta-test", GENERATION), runMapper);









    private static AgentRun run(String id, String deploymentId, String generationId) {
        AgentRun run = new AgentRun();
        run.setId(id);
        run.setUserId("user-1");
        run.setDeploymentId(deploymentId);
        run.setDeploymentGenerationId(generationId);
        run.setStatus(AgentRunStatus.WAITING_TOOL_JOB);
        return run;
    }

    private static ToolJobAnchor anchor() {
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId("run-mine:call-1:1");
        anchor.setResumeState("READY");
        anchor.setResumeToken("token-1");
        anchor.setResumeLeaseVersion(3);
        return anchor;
    }
}
