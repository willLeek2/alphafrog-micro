package world.willfrog.agentlangchain.gateway;

import org.mockito.Mockito;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentityProvider;

/**
 * 业务单测的 gateway 夹具：给被测类一个固定部署身份，或按需放行归属判定。
 *
 * <p>只用于测试。归属判定本身的真实行为（本代际放行、他代际拒绝、身份缺失抛错）由
 * {@link RunOwnershipGatewayTest} 覆盖。</p>
 */
public final class GatewayTestFixtures {

    public static final String TEST_DEPLOYMENT_ID = "test-deployment";
    public static final String TEST_GENERATION_ID = "gen-" + "t".repeat(64);

    private GatewayTestFixtures() {
    }

    public static DeploymentIdentityProvider fixedIdentityProvider() {
        return identityProvider(TEST_DEPLOYMENT_ID, TEST_GENERATION_ID);
    }

    public static DeploymentIdentityProvider identityProvider(String deploymentId, String generationId) {
        return () -> new DeploymentIdentity(deploymentId, generationId);
    }

    /** 真实判定 + 固定身份：用于覆盖「本代际放行、他代际拒绝」的用例。 */
    public static RunOwnershipGateway withIdentity(AgentRunMapper runMapper,
                                                   String deploymentId,
                                                   String generationId) {
        return new RunOwnershipGateway(identityProvider(deploymentId, generationId), runMapper);
    }

    /** 放行判定：单测聚焦业务语义时使用，不额外校验 Run 上的部署身份。 */
    public static RunOwnershipGateway permissive() {
        return permissive(Mockito.mock(AgentRunMapper.class));
    }

    public static RunOwnershipGateway permissive(AgentRunMapper runMapper) {
        return new RunOwnershipGateway(fixedIdentityProvider(), runMapper) {
            @Override
            public boolean owns(AgentRun run) {
                return true;
            }

            @Override
            public boolean owns(String runId) {
                return true;
            }

            @Override
            public AgentRun findOwnedRun(String runId) {
                AgentRun run = new AgentRun();
                run.setId(runId);
                return run;
            }
        };
    }
}
