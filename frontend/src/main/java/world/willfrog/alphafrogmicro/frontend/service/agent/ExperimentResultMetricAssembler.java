package world.willfrog.alphafrogmicro.frontend.service.agent;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.rpc.RpcException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import world.willfrog.alphafrogmicro.agent.idl.AgentDubboService;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunCostMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunMessage;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunCostRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunRequest;

/**
 * 按根运行读取已有运行接口。每个不同的根运行调用一次。
 * 公开的单根读取自行解析登录。批量读取使用调用方已经解析的登录上下文，自己不再解析。
 * 没有评分字段时分数固定为未评分。缺失的耗时和费用保持空，不填 0。
 */
@Service
public class ExperimentResultMetricAssembler {
    public static final String UNRATED = "未评分";

    @DubboReference(group = "langchain", check = false)
    private AgentDubboService runs;

    private final AgentAuthSupport authSupport;

    public ExperimentResultMetricAssembler(AgentAuthSupport authSupport) {
        this.authSupport = authSupport;
    }

    public RootRunMetrics loadByRoot(Authentication authentication, String rootRunId) {
        AgentAuthSupport.AgentAuthContext caller = authSupport.resolve(authentication);
        if (caller.userId() == null) {
            return RootRunMetrics.unsigned();
        }
        if (rootRunId == null || rootRunId.isBlank()) {
            return RootRunMetrics.unavailable();
        }
        return readOne(caller, rootRunId);
    }

    /**
     * 使用已经解析的登录上下文，按根运行编号去重后读取。空白编号不进入结果。
     * 同一个编号只保留第一次出现的位置，并且只读取一次。
     */
    public Map<String, RootRunMetrics> loadByRoots(AgentAuthSupport.AgentAuthContext caller, List<String> rootRunIds) {
        Map<String, RootRunMetrics> metrics = new LinkedHashMap<>();
        if (rootRunIds == null) {
            return metrics;
        }
        boolean signedIn = caller != null && caller.userId() != null;
        for (String rootRunId : rootRunIds) {
            if (rootRunId == null || rootRunId.isBlank() || metrics.containsKey(rootRunId)) {
                continue;
            }
            metrics.put(rootRunId, signedIn ? readOne(caller, rootRunId) : RootRunMetrics.unsigned());
        }
        return metrics;
    }

    private RootRunMetrics readOne(AgentAuthSupport.AgentAuthContext caller, String rootRunId) {
        AgentRunMessage run;
        try {
            run = runs.getRun(GetAgentRunRequest.newBuilder()
                    .setUserId(caller.userId())
                    .setId(rootRunId)
                    .setIsAdmin(caller.admin())
                    .build());
        } catch (RpcException failure) {
            return RootRunMetrics.unavailable();
        }
        Double cost = null;
        try {
            AgentRunCostMessage loaded = runs.getRunCost(GetAgentRunCostRequest.newBuilder()
                    .setUserId(caller.userId())
                    .setId(rootRunId)
                    .build());
            if (loaded.getHasTotalCost()) {
                cost = loaded.getTotalCost();
            }
        } catch (RpcException failure) {
            cost = null;
        }
        String startedAt = blankToNull(run.getStartedAt());
        String completedAt = blankToNull(run.getCompletedAt());
        return new RootRunMetrics(blankToNull(run.getStatus()), startedAt, completedAt,
                durationMillis(startedAt, completedAt), cost, UNRATED, true);
    }

    private static Long durationMillis(String startedAt, String completedAt) {
        if (startedAt == null || completedAt == null) {
            return null;
        }
        try {
            long millis = Instant.parse(completedAt).toEpochMilli() - Instant.parse(startedAt).toEpochMilli();
            return millis < 0 ? null : millis;
        } catch (DateTimeParseException failure) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public record RootRunMetrics(String status, String startedAt, String completedAt, Long durationMillis,
            Double totalCost, String scoreLabel, boolean signedIn) {
        public static RootRunMetrics unsigned() {
            return new RootRunMetrics(null, null, null, null, null, UNRATED, false);
        }

        public static RootRunMetrics unavailable() {
            return new RootRunMetrics(null, null, null, null, null, UNRATED, true);
        }
    }
}
