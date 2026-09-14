package world.willfrog.agentlangchain.gateway;

import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentityProvider;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 认领与受理入口的所有权判定：当前进程只允许处理本部署代际（deployment + generation）的 Run。
 *
 * <p>这是流量分发与业务逻辑的解耦边界。分发相关的两类判定都收在本包：
 * 入站恢复泳道见 {@link LaneScopeGateway}；「当前进程允许处理哪些行」在这里。
 * 扫描（哪些遗留 Run 归我恢复）、认领（抢救恢复租约的窄 CAS）、用户入口（取消/暂停/恢复/
 * 追问的归属校验）都必须先经过本类；业务包只接收已经判定过归属的 Run，后续写入条件只用
 * 业务字段（run id、user、status、租约 token、operationId），不再按「有没有部署身份」分叉 SQL。</p>
 *
 * <p>归属判定 fail-closed：{@link DeploymentIdentityProvider#current()} 在部署身份缺失时
 * 直接抛异常，不存在「身份为空就跳过栅栏」的静默回落。Run 行上的部署身份由受理入口写入且
 * 不可变，因此一次归属读取的结论在整条处理链内保持有效。</p>
 */
@Service
public class RunOwnershipGateway {

    private final DeploymentIdentityProvider identityProvider;
    private final AgentRunMapper runMapper;

    public RunOwnershipGateway(DeploymentIdentityProvider identityProvider,
                               AgentRunMapper runMapper) {
        this.identityProvider = identityProvider;
        this.runMapper = runMapper;
    }

    /** 受理入口一次性读取可信部署身份；缺失即失败。 */
    public DeploymentIdentity requireIdentity() {
        return identityProvider.current();
    }

    /** 该行是否属于本部署代际。行不存在时返回 false；部署身份缺失抛异常。 */
    public boolean owns(String runId) {
        AgentRun run = runMapper.findById(runId);
        return owns(run);
    }

    /** 该行是否属于本部署代际（内存判定，用于刚扫描出的 Run）。 */
    public boolean owns(AgentRun run) {
        if (run == null) {
            return false;
        }
        DeploymentIdentity local = identityProvider.current();
        return Objects.equals(local.deploymentId(), run.getDeploymentId())
                && Objects.equals(local.generationId(), run.getDeploymentGenerationId());
    }

    /** 本代际的行；不属于本代际或不存在时返回 null。 */
    public AgentRun findOwnedRun(String runId) {
        DeploymentIdentity local = identityProvider.current();
        return runMapper.findByIdForDeployment(
                runId, local.deploymentId(), local.generationId());
    }

    /** 本代际且属于该用户的行；否则返回 null。 */
    public AgentRun findOwnedRunForUser(String runId, String userId) {
        DeploymentIdentity local = identityProvider.current();
        return runMapper.findByIdAndUserForDeployment(
                runId, userId, local.deploymentId(), local.generationId());
    }

    /**
     * 用户控制入口（取消/暂停/恢复）的归属校验：不属于本代际时保持既有用户可见错误。
     */
    public AgentRun requireOwnedRunForUser(String runId, String userId) {
        AgentRun run = findOwnedRunForUser(runId, userId);
        if (run == null) {
            throw new IllegalStateException("原测试部署已停用");
        }
        return run;
    }

    /**
     * 认领入口：有界列出启动前遗留、可能归本代际恢复的 Run。
     * 调用方仍需逐条校验 Plan/checkpoint；本查询只做归属过滤与有界发现。
     */
    public List<AgentRun> listStartupRecoveryCandidates(OffsetDateTime startedBefore, int limit) {
        DeploymentIdentity local = identityProvider.current();
        return runMapper.listStartupRecoveryCandidatesForDeployment(
                startedBefore, local.deploymentId(), local.generationId(), limit);
    }

    /**
     * 认领入口：启动恢复的窄 CAS，取得本次恢复权才返回 1。
     */
    public int claimStartupRestart(String runId,
                                   AgentRunStatus expectedStatus,
                                   int expectedRestartAttempt,
                                   int maxRestartAttempts) {
        DeploymentIdentity local = identityProvider.current();
        return runMapper.claimStartupRestartForDeployment(
                runId, local.deploymentId(), local.generationId(),
                expectedStatus, expectedRestartAttempt, maxRestartAttempts);
    }

    /** 认领入口：本代际仍在等待长工具终态的 Run。 */
    public List<AgentRun> listActiveAnchors(int limit) {
        DeploymentIdentity local = identityProvider.current();
        return runMapper.listActiveToolJobAnchorsForDeployment(
                local.deploymentId(), local.generationId(), limit);
    }

    /** 认领入口：本代际 READY/超时 LAUNCHING 的恢复候选。 */
    public List<AgentRun> listResumeReadyAnchors(int limit) {
        DeploymentIdentity local = identityProvider.current();
        return runMapper.listResumeReadyAnchorsForDeployment(
                local.deploymentId(), local.generationId(), limit);
    }

    /** 认领入口：本代际 CAS_STATUS→RESUME_READY 半状态候选（只发现不推进）。 */
    public List<AgentRun> listStuckAtCasStatusAnchors(int limit) {
        DeploymentIdentity local = identityProvider.current();
        return runMapper.listStuckAtCasStatusAnchorsForDeployment(
                local.deploymentId(), local.generationId(), limit);
    }

    /**
     * 受理入口：追问准入。只有 Run 仍完成且归属本部署代际时，才在写消息前转回待执行状态。
     */
    public int admitFollowUp(String runId, String userId, OffsetDateTime ttlExpiresAt) {
        DeploymentIdentity local = identityProvider.current();
        return runMapper.admitFollowUpForDeployment(
                runId, userId, local.deploymentId(), local.generationId(), ttlExpiresAt);
    }

    /**
     * 认领入口：恢复租约 READY→LAUNCHING 的窄 CAS，只有本代际的调用者能赢。
     */
    public boolean claimResumeLauncher(String runId,
                                       ToolJobAnchor anchor,
                                       AgentRunStatus newStatus,
                                       AgentRunStatus expectedStatus,
                                       String expectedResumeToken,
                                       long expectedLeaseVersion,
                                       String launcherOwnerId,
                                       long leaseSeconds) {
        if (launcherOwnerId == null || launcherOwnerId.isBlank() || leaseSeconds <= 0) {
            return false;
        }
        DeploymentIdentity local = identityProvider.current();
        return runMapper.claimResumeLauncher(
                runId, anchor.toJson(), newStatus, expectedStatus, expectedResumeToken,
                expectedLeaseVersion, launcherOwnerId, leaseSeconds,
                local) == 1;
    }

    /**
     * 认领入口：抢占已过期恢复租约的窄 CAS，只允许本代际的调用者接管。
     */
    public boolean takeoverExpiredResumeLauncher(String runId,
                                                 ToolJobAnchor anchor,
                                                 AgentRunStatus expectedStatus,
                                                 String expectedResumeToken,
                                                 long expectedLeaseVersion,
                                                 String expectedLauncherOwnerId,
                                                 String launcherOwnerId,
                                                 long leaseSeconds,
                                                 long legacyStaleSeconds) {
        if (launcherOwnerId == null || launcherOwnerId.isBlank()
                || leaseSeconds <= 0 || legacyStaleSeconds <= 0) {
            return false;
        }
        DeploymentIdentity local = identityProvider.current();
        return runMapper.takeoverExpiredResumeLauncher(
                runId, anchor.toJson(), expectedStatus, expectedResumeToken,
                expectedLeaseVersion, expectedLauncherOwnerId, launcherOwnerId,
                leaseSeconds, legacyStaleSeconds, local) == 1;
    }
}
