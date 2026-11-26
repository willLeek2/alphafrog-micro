package world.willfrog.agentlangchain.health;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import world.willfrog.agent.platform.PlatformModuleMarker;
import world.willfrog.agent.platform.capacity.SchedulerBackpressureProbe;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.tools.router.ToolRouter;
import world.willfrog.agentlangchain.config.LangchainServiceProperties;
import world.willfrog.agentlangchain.config.LangchainToolConcurrencyThrottle;
import world.willfrog.agentlangchain.control.AgentLangchainOrchestrator;
import world.willfrog.agentlangchain.control.LangchainRunConcurrencyScheduler;
import world.willfrog.agentlangchain.control.dualpool.DualPoolDispatcher;
import world.willfrog.agentlangchain.control.dualpool.DualPoolRecoveryDispatcher;
import world.willfrog.agentlangchain.control.dualpool.DualPoolSchedulerSettings;
import world.willfrog.agentlangchain.control.dualpool.FrozenEffectiveSettings;
import world.willfrog.agentlangchain.tooljob.WaitMemberResultReceiver;
import world.willfrog.agentlangchain.workspace.PythonWorkspaceRetentionCoordinator;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/agent-langchain")
@RequiredArgsConstructor
public class AgentLangchainHealthController {

    private final LangchainServiceProperties properties;
    private final AgentLangchainOrchestrator orchestrator;
    private final LangchainRunConcurrencyScheduler concurrencyScheduler;
    private final DualPoolDispatcher dualPoolDispatcher;
    private final DualPoolRecoveryDispatcher dualPoolRecoveryDispatcher;
    private final SchedulerBackpressureProbe schedulerBackpressureProbe;
    private final LangchainToolConcurrencyThrottle toolThrottle;
    private final DualPoolSchedulerSettings schedulerSettings;
    private final FrozenEffectiveSettings frozenEffectiveSettings;
    private final WaitMemberResultReceiver waitMemberResultReceiver;
    private final PythonWorkspaceRetentionCoordinator retentionCoordinator;
    private final AgentRunEventService runEventService;

    @Value("${agent.langchain.service.version:UNKNOWN}")
    private String serviceVersion;

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", "agentLangchainService");
        body.put("version", serviceVersion);
        boolean providerEnabled = properties.getProvider().isEnabled();
        body.put("providerEnabled", providerEnabled);
        body.put("orchestrationStatus", orchestrator.orchestrationStatus(providerEnabled));
        body.put("platformSharedLoaded", isClassLoaded(PlatformModuleMarker.class));
        body.put("toolsSharedLoaded", isClassLoaded(ToolRouter.class));
        body.put("status", "UP");
        return body;
    }

    @GetMapping("/scheduler")
    public Map<String, Object> scheduler() {
        // 保留旧调度器字段在顶层，避免现有观测调用方失效；双池数据以独立子树追加。
        Map<String, Object> snapshot = new LinkedHashMap<>(concurrencyScheduler.schedulerSnapshot());
        Map<String, Object> dualPool = new LinkedHashMap<>(dualPoolDispatcher.snapshot());
        dualPool.putAll(dualPoolRecoveryDispatcher.snapshot());
        // 结果接收方的读数：接回多少成员、推后多少、被夹具策略压住多少、被点名按失败收尾多少。
        // 验收看的就是这几个数，所以和双池其余读数放在同一棵树里。
        dualPool.putAll(waitMemberResultReceiver.snapshot());
        dualPool.put("backpressure", schedulerBackpressureProbe.snapshot());
        // 生效的双池参数：每个值都带来源（热配置／环境属性／代码默认）、改了要不要重启，
        // 以及被丢掉的值与原因。验收与排查都从这一份读，不靠猜某个泳道配了什么。
        dualPool.put("settings", schedulerSettings.snapshot());
        // 启动冻结的那些参数，各组件此刻真正在用的值，按参数名分组：一个参数只有一个消费者时值就是
        // 一个数，几个消费者用的数不一样时（租约时长这种）并排列出。setting 那一份里同一个参数报的是
        // 同一个值，两处对得上说明读数就是系统在用的东西。
        dualPool.put("settingsInUse", frozenEffectiveSettings.all());
        dualPool.put("settingsSampledAt", OffsetDateTime.now().toString());
        snapshot.put("dualPool", dualPool);
        return snapshot;
    }

    /**
     * 沙箱治理参数的生效值读数：工作区保留时长、中断 Run 存活期、清理扫描周期，以及清理扫描
     * 实际在跑的两个生效条件。对齐 {@link #scheduler()} 的「值 + 来源 + 改了要不要重启」读法，
     * 但各值从各自的持有方现算，不复用双池的 {@link DualPoolSchedulerSettings}（那一份绑死双池键名）。
     */
    @GetMapping("/sandbox-governance")
    public Map<String, Object> sandboxGovernance() {
        Map<String, Object> body = new LinkedHashMap<>();
        // ②工作区保留时长：协调器删盘判定前每轮重读热配置，这份读数就是下一轮判定会用的值；
        // 被下限钳过时原始配置值一并带出。
        body.put("retentionHours", retentionCoordinator.retentionHoursWithSource());
        // ③中断 Run 存活期：热配置 → 环境属性 → 代码默认三层解析，与各 TTL 写入点同一口径。
        body.put("interruptedTtlDays", runEventService.interruptedTtlDaysWithSource());
        // ④清理扫描周期：@Scheduled 启动时读一次定死，改后需重启，读数报的是启动时定下的值。
        Map<String, Object> cleanupScanMs = new LinkedHashMap<>();
        cleanupScanMs.put("value", retentionCoordinator.cleanupScanMs());
        cleanupScanMs.put("restartRequired", true);
        cleanupScanMs.put("source", "property");
        body.put("cleanupScanMs", cleanupScanMs);
        // 清理扫描实际在跑的两个生效条件，与协调器 scan() 开头的判断同一处：清理端口组件在位
        // （网关接口未升级时为空）+ 热配置已同步权威。两者合取才是扫描实际在跑。
        body.put("retentionPortPresent", retentionCoordinator.retentionPortPresent());
        body.put("hotConfigIsAuthoritative", retentionCoordinator.hotConfigIsAuthoritative());
        // ①排队期限固化在 pythonSandbox 进程内（启动时读一次环境变量），本端点不跨服务聚合；
        // 生效值从 pythonSandbox 的 /health 读。
        Map<String, Object> queueWaitTimeoutSeconds = new LinkedHashMap<>();
        queueWaitTimeoutSeconds.put("readFrom", "pythonSandbox /health");
        body.put("queueWaitTimeoutSeconds", queueWaitTimeoutSeconds);
        body.put("sampledAt", OffsetDateTime.now().toString());
        return body;
    }

    @GetMapping("/tool-throttle")
    public Map<String, Object> toolThrottle() {
        return toolThrottle.throttleMetrics();
    }

    private static boolean isClassLoaded(Class<?> type) {
        return type != null;
    }
}
