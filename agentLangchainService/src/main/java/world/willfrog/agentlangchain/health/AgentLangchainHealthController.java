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
    private final SchedulerBackpressureProbe schedulerBackpressureProbe;
    private final LangchainToolConcurrencyThrottle toolThrottle;

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
        dualPool.put("backpressure", schedulerBackpressureProbe.snapshot());
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
