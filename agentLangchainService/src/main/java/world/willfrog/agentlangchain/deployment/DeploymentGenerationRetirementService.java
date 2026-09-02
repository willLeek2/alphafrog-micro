package world.willfrog.agentlangchain.deployment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentityProvider;

import java.util.function.Supplier;

/** 接受可信的代际退役请求，并把本代际尚未结束的 Run 写成数据库可见终态。 */
@Component
@Slf4j
public class DeploymentGenerationRetirementService {

    private final AgentRunMapper runMapper;
    private final DeploymentIdentityProvider identityProvider;
    private final DeploymentRetirementAuthorizer retirementAuthorizer;
    private volatile boolean retired;
    private boolean retirementPersisted;
    private int closedRunCount;

    public DeploymentGenerationRetirementService(
            AgentRunMapper runMapper,
            DeploymentIdentityProvider identityProvider,
            DeploymentRetirementAuthorizer retirementAuthorizer,
            @Value("${agent.deployment.retirement-only:false}") boolean retirementOnly) {
        this.runMapper = runMapper;
        this.identityProvider = identityProvider;
        this.retirementAuthorizer = retirementAuthorizer;
        this.retired = retirementOnly;
    }

    public synchronized int retire(
            String deploymentId,
            String deploymentGenerationId,
            String retirementToken) {
        retirementAuthorizer.authorize(retirementToken);
        DeploymentIdentity identity = identityProvider.current();
        identity.requireExactMatch(deploymentId, deploymentGenerationId);
        retired = true;
        if (retirementPersisted) {
            return closedRunCount;
        }
        int closed = runMapper.closeNonTerminalRunsForDeployment(
                identity.deploymentId(), identity.generationId());
        closedRunCount = closed;
        retirementPersisted = true;
        log.warn("Agent 部署代际已收到退役信号，{} 个未结束 Run 已写成终态", closed);
        return closed;
    }

    /**
     * 让新 Run 准入与代际退役串行。操作要么在退役批量关闭之前全部提交，
     * 要么在退役标志已写入后直接拒绝，不留“批量关闭后才新建 Run”的窗口。
     */
    public synchronized <T> T executeWhileActive(Supplier<T> operation) {
        if (retired) {
            throw new IllegalStateException("deployment_generation_inactive");
        }
        return operation.get();
    }

    public boolean isRetired() {
        return retired;
    }
}
