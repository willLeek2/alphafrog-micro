package world.willfrog.agentlangchain.tooljob;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.dataanalysis.ToolJobFaultInjector;
import world.willfrog.agent.platform.dataanalysis.ToolJobInjectedInterruption;
import world.willfrog.agentlangchain.control.dualpool.DualPoolRunAdmissionRegistry;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentityProvider;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;

/**
 * 数据库原子消费的一次性长工具故障点。
 *
 * <p>该 Bean 默认不创建。Beta 验收显式打开后，每次命中仍需数据库里存在精确的泳道、部署代际、
 * Run 和检查点记录。更新成功后才执行中断，因此进程重启不会再次命中同一场景。</p>
 */
@Component
@ConditionalOnProperty(name = "agent.tool-job.fault-injection.enabled", havingValue = "true")
@Slf4j
public class DurableToolJobFaultInjector implements ToolJobFaultInjector {

    private static final String THREAD_INTERRUPT = "THREAD_INTERRUPT";
    private static final String PROCESS_HALT = "PROCESS_HALT";

    private final JdbcTemplate jdbcTemplate;
    private final DeploymentIdentityProvider identityProvider;
    private final DualPoolRunAdmissionRegistry admissionRegistry;
    private final boolean processHaltEnabled;
    private final String instanceId = resolveInstanceId();

    public DurableToolJobFaultInjector(
            JdbcTemplate jdbcTemplate,
            DeploymentIdentityProvider identityProvider,
            DualPoolRunAdmissionRegistry admissionRegistry,
            @Value("${agent.tool-job.fault-injection.allow-process-halt:false}")
            boolean processHaltEnabled) {
        this.jdbcTemplate = jdbcTemplate;
        this.identityProvider = identityProvider;
        this.admissionRegistry = admissionRegistry;
        this.processHaltEnabled = processHaltEnabled;
    }

    @PostConstruct
    void markRestartObserved() {
        DeploymentIdentity identity = identityProvider.current();
        int updated = jdbcTemplate.update("""
                UPDATE alphafrog_agent_tool_job_fault_injection
                SET restart_observed_at = CURRENT_TIMESTAMP
                WHERE lane_id = ?
                  AND deployment_version = ?
                  AND action = 'PROCESS_HALT'
                  AND consumed_at IS NOT NULL
                  AND restart_observed_at IS NULL
                """, identity.deploymentId(), identity.generationId());
        if (updated > 0) {
            log.warn("长工具故障演练已观察到同部署重启: lane={} generation={} records={}",
                    identity.deploymentId(), identity.generationId(), updated);
        }
    }

    @Override
    public void hit(String runId, String checkpoint) {
        if (runId == null || runId.isBlank() || checkpoint == null || checkpoint.isBlank()) {
            return;
        }
        DeploymentIdentity identity = identityProvider.current();
        List<Map<String, Object>> consumed = jdbcTemplate.queryForList("""
                WITH candidate AS (
                    SELECT id
                    FROM alphafrog_agent_tool_job_fault_injection
                    WHERE lane_id = ?
                      AND deployment_version = ?
                      AND run_id = ?
                      AND checkpoint = ?
                      AND enabled = TRUE
                      AND consumed_at IS NULL
                      AND expires_at > CURRENT_TIMESTAMP
                    ORDER BY enabled_at, id
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE alphafrog_agent_tool_job_fault_injection target
                SET consumed_at = CURRENT_TIMESTAMP,
                    triggered_at = CURRENT_TIMESTAMP,
                    trigger_instance = ?
                FROM candidate
                WHERE target.id = candidate.id
                RETURNING target.scenario_id, target.action
                """, identity.deploymentId(), identity.generationId(), runId, checkpoint, instanceId);
        if (consumed.isEmpty()) {
            return;
        }
        String scenarioId = String.valueOf(consumed.get(0).get("scenario_id"));
        String action = String.valueOf(consumed.get(0).get("action"));
        log.error("长工具一次性故障点已触发: runId={} scenario={} checkpoint={} action={}",
                runId, scenarioId, checkpoint, action);

        admissionRegistry.forgetForFaultInjection(runId);
        if (PROCESS_HALT.equals(action)) {
            if (!processHaltEnabled) {
                throw new IllegalStateException("tool_job_process_halt_not_enabled:" + scenarioId);
            }
            Runtime.getRuntime().halt(79);
        }
        if (!THREAD_INTERRUPT.equals(action)) {
            throw new IllegalStateException("tool_job_fault_action_unsupported:" + action);
        }
        throw new ToolJobInjectedInterruption(scenarioId, checkpoint);
    }

    private static String resolveInstanceId() {
        try {
            return InetAddress.getLocalHost().getHostName() + ":"
                    + ManagementFactory.getRuntimeMXBean().getName();
        } catch (Exception ignored) {
            return ManagementFactory.getRuntimeMXBean().getName();
        }
    }
}
