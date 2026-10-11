package world.willfrog.agentlangchain.health;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import world.willfrog.agent.platform.PlatformModuleMarker;
import world.willfrog.agent.platform.capacity.SchedulerBackpressureProbe;
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

    @GetMapping("/tool-throttle")
    public Map<String, Object> toolThrottle() {
        return toolThrottle.throttleMetrics();
    }

    private static boolean isClassLoaded(Class<?> type) {
        return type != null;
    }
}
