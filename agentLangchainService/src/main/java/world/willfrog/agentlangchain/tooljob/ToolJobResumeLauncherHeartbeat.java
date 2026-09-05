package world.willfrog.agentlangchain.tooljob;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 恢复 launcher 的数据库租约周期续租，只在耐久恢复开启时存在。
 *
 * <p>Active 包含正在排队和正在执行的恢复任务。每轮只做数据库窄续租；如果 rows=0，
 * 说明 token/version/owner 已被 takeover 或下一次 PREPARING 替换，本实例立即停止续租。
 * 耐久恢复关闭时没有 heartbeat 线程，进程内续接由 ToolJobContinuationTracker
 * 独占驱动，数据库 LAUNCHING 租约也不存在跨实例竞争。</p>
 */
@Service
@ConditionalOnProperty(name = "agent.tool-job.durable-recovery-enabled", havingValue = "true")
@Slf4j
public class ToolJobResumeLauncherHeartbeat {

    private final ToolJobResumeLauncherImpl launcher;
    private final ObjectProvider<ToolJobResumeService> resumeServiceProvider;

    public ToolJobResumeLauncherHeartbeat(ToolJobResumeLauncherImpl launcher,
                                          ObjectProvider<ToolJobResumeService> resumeServiceProvider) {
        this.launcher = launcher;
        this.resumeServiceProvider = resumeServiceProvider;
    }

    @Scheduled(fixedDelayString = "${agent.tool-job.resume-launcher-heartbeat-interval-ms:5000}")
    void heartbeatActiveClaims() {
        ToolJobResumeService service = resumeServiceProvider.getIfAvailable();
        if (service == null) {
            return;
        }
        for (ToolJobResumeClaimKey key : launcher.activeClaimKeys()) {
            try {
                if (!service.heartbeat(key.runId(), key.token(), key.version(), key.ownerId())) {
                    launcher.removeClaim(key);
                }
            } catch (Exception e) {
                // 数据库暂时不可用时保留本地声明，下一轮继续续租；数据库 lease 仍会自然过期并允许接管。
                log.warn("Resume heartbeat failed runId={} token={} version={}: {}",
                        key.runId(), key.token(), key.version(), e.getMessage());
            }
        }
    }
}
