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

    @Test
    void ownsAdmitsSameGenerationRowAndRejectsAnotherGenerationOrMissingRow() {
        when(runMapper.findById("run-mine")).thenReturn(run("run-mine", "beta-test", GENERATION));
        when(runMapper.findById("run-other"))
                .thenReturn(run("run-other", "beta-test", OTHER_GENERATION));

        assertThat(gateway.owns("run-mine")).isTrue();
        assertThat(gateway.owns("run-other")).isFalse();
        assertThat(gateway.owns("run-missing")).isFalse();
    }

    @Test
    void deploymentIdentityMissingFailsClosed() {
        RunOwnershipGateway failClosed = new RunOwnershipGateway(() -> {
            throw new IllegalStateException("AF_DEPLOYMENT_ID is required");
        }, runMapper);

        assertThatThrownBy(() -> failClosed.owns(run("run-mine", "beta-test", GENERATION)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AF_DEPLOYMENT_ID");
    }

    @Test
    void userAdmissionRejectsForeignGenerationWithUserVisibleError() {
        when(runMapper.findByIdAndUserForDeployment(
                "run-other", "user-1", "beta-test", GENERATION)).thenReturn(null);

        assertThatThrownBy(() -> gateway.requireOwnedRunForUser("run-other", "user-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("原测试部署已停用");
    }

    @Test
    void userAdmissionReturnsOwnedRow() {
        AgentRun mine = run("run-mine", "beta-test", GENERATION);
        when(runMapper.findByIdAndUserForDeployment(
                "run-mine", "user-1", "beta-test", GENERATION)).thenReturn(mine);

        assertThat(gateway.requireOwnedRunForUser("run-mine", "user-1")).isSameAs(mine);
    }

    @Test
    void recoveryScansPassTheLocalDeploymentIdentity() {
        gateway.listActiveAnchors(100);
        gateway.listResumeReadyAnchors(50);
        gateway.listStuckAtCasStatusAnchors(20);
        gateway.listStartupRecoveryCandidates(OffsetDateTime.now(), 30);

        verify(runMapper).listActiveToolJobAnchorsForDeployment("beta-test", GENERATION, 100);
        verify(runMapper).listResumeReadyAnchorsForDeployment("beta-test", GENERATION, 50);
        verify(runMapper).listStuckAtCasStatusAnchorsForDeployment("beta-test", GENERATION, 20);
        verify(runMapper).listStartupRecoveryCandidatesForDeployment(
                any(OffsetDateTime.class), eq("beta-test"), eq(GENERATION), eq(30));
    }

    @Test
    void resumeLauncherClaimOnlyWinsForThisGeneration() {
        when(runMapper.claimResumeLauncher(
                eq("run-mine"), any(String.class), eq(AgentRunStatus.EXECUTING),
                eq(AgentRunStatus.WAITING_TOOL_JOB), eq("token-1"), eq(3L), eq("owner-a"), eq(30L),
                any(DeploymentIdentity.class))).thenReturn(1);
        when(runMapper.claimResumeLauncher(
                eq("run-other"), any(String.class), eq(AgentRunStatus.EXECUTING),
                eq(AgentRunStatus.WAITING_TOOL_JOB), eq("token-1"), eq(3L), eq("owner-a"), eq(30L),
                any(DeploymentIdentity.class))).thenReturn(0);

        assertThat(gateway.claimResumeLauncher(
                "run-mine", anchor(), AgentRunStatus.EXECUTING, AgentRunStatus.WAITING_TOOL_JOB,
                "token-1", 3L, "owner-a", 30L)).isTrue();
        assertThat(gateway.claimResumeLauncher(
                "run-other", anchor(), AgentRunStatus.EXECUTING, AgentRunStatus.WAITING_TOOL_JOB,
                "token-1", 3L, "owner-a", 30L)).isFalse();
    }

    @Test
    void resumeLauncherClaimRejectsBlankOwnerOrNonPositiveLeaseLocally() {
        assertThat(gateway.claimResumeLauncher(
                "run-mine", anchor(), AgentRunStatus.EXECUTING, AgentRunStatus.WAITING_TOOL_JOB,
                "token-1", 3L, " ", 30L)).isFalse();
        assertThat(gateway.claimResumeLauncher(
                "run-mine", anchor(), AgentRunStatus.EXECUTING, AgentRunStatus.WAITING_TOOL_JOB,
                "token-1", 3L, "owner-a", 0L)).isFalse();
    }

    @Test
    void requireIdentityReturnsTheProcessIdentity() {
        assertThat(gateway.requireIdentity().deploymentId()).isEqualTo("beta-test");
        assertThat(gateway.requireIdentity().generationId()).isEqualTo(GENERATION);
    }

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
