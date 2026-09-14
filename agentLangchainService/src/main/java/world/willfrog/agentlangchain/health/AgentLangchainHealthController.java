package world.willfrog.agentlangchain.health;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import world.willfrog.agent.platform.PlatformModuleMarker;
import world.willfrog.agent.tools.router.ToolRouter;
import world.willfrog.agentlangchain.config.LangchainServiceProperties;
import world.willfrog.agentlangchain.config.LangchainToolConcurrencyThrottle;
import world.willfrog.agentlangchain.control.AgentLangchainOrchestrator;
import world.willfrog.agentlangchain.control.LangchainRunConcurrencyScheduler;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/agent-langchain")
@RequiredArgsConstructor
public class AgentLangchainHealthController {

    private final LangchainServiceProperties properties;
    private final AgentLangchainOrchestrator orchestrator;
    private final LangchainRunConcurrencyScheduler concurrencyScheduler;
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
        return concurrencyScheduler.schedulerSnapshot();
    }

    @GetMapping("/tool-throttle")
    public Map<String, Object> toolThrottle() {
        return toolThrottle.throttleMetrics();
    }

    private static boolean isClassLoaded(Class<?> type) {
        return type != null;
    }
}
