package world.willfrog.agentlangchain.gateway;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.alphafrogmicro.agent.idl.CheckRunPersistentResourceEligibilityRequest;
import world.willfrog.alphafrogmicro.agent.idl.CheckRunPersistentResourceEligibilityResponse;
import world.willfrog.alphafrogmicro.agent.idl.RunPersistentResourceEligibility;
import world.willfrog.alphafrogmicro.common.lane.LaneContext;

import java.util.Objects;

/**
 * 沙箱网关取得 Run 关联持久资源前的只读资格回查。
 *
 * <p>资格来自本 Agent 实例的部署身份、数据库中的 Run 与删除意图，以及本次
 * Dubbo 调用的入站泳道标签。它只是一道准入预检；查询返回后发生的并发删除，
 * 仍由沙箱取得和封住 Run 时共用的持久锁裁决。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RunPersistentResourceEligibilityService {

    private static final ObjectMapper EXT_MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final RunOwnershipGateway ownershipGateway;
    private final AgentRunMapper runMapper;

    public CheckRunPersistentResourceEligibilityResponse check(
            CheckRunPersistentResourceEligibilityRequest request) {
        if (request == null || request.getRunId().isBlank()) {
            return response(RunPersistentResourceEligibility.RUN_PERSISTENT_RESOURCE_ELIGIBILITY_REFUSED);
        }
        String runId = request.getRunId().strip();
        try {
            AgentRun run = ownershipGateway.findOwnedRun(runId);
            if (run == null || !sameLane(run)
                    || run.getStatus() != AgentRunStatus.EXECUTING
                    || !frozenWorkspaceEnabled(run)
                    || runMapper.isDeletionStarted(runId)
                    || runMapper.findWorkspaceLifecycleState(runId) != null) {
                return response(RunPersistentResourceEligibility.RUN_PERSISTENT_RESOURCE_ELIGIBILITY_REFUSED);
            }
            return response(RunPersistentResourceEligibility.RUN_PERSISTENT_RESOURCE_ELIGIBILITY_ALLOWED);
        } catch (JsonProcessingException malformedExt) {
            log.warn("Run 持久资源资格回查读到损坏的冻结配置: runId={}", runId);
            return response(RunPersistentResourceEligibility
                    .RUN_PERSISTENT_RESOURCE_ELIGIBILITY_TEMPORARILY_UNAVAILABLE);
        } catch (RuntimeException failure) {
            // 身份服务、数据库或持久状态不可读时不猜测 Run 可以创建资源。
            log.warn("Run 持久资源资格回查暂不可用: runId={}", runId, failure);
            return response(RunPersistentResourceEligibility
                    .RUN_PERSISTENT_RESOURCE_ELIGIBILITY_TEMPORARILY_UNAVAILABLE);
        }
    }

    private static boolean frozenWorkspaceEnabled(AgentRun run) throws JsonProcessingException {
        String ext = run.getExt();
        if (ext == null || ext.isBlank()) {
            return false;
        }
        JsonNode root = EXT_MAPPER.readTree(ext);
        if (root == null || !root.isObject()) {
            return false;
        }
        JsonNode enabled = root.get("python_workspace_enabled");
        return enabled != null && enabled.isBoolean() && enabled.booleanValue();
    }

    private static boolean sameLane(AgentRun run) {
        String inboundLane = LaneContext.toOfficialDubboTag(LaneScopeGateway.currentLaneTag());
        String persistedLane = LaneContext.toOfficialDubboTag(run.getLaneTag());
        return Objects.equals(inboundLane, persistedLane);
    }

    private static CheckRunPersistentResourceEligibilityResponse response(
            RunPersistentResourceEligibility eligibility) {
        return CheckRunPersistentResourceEligibilityResponse.newBuilder()
                .setEligibility(eligibility)
                .build();
    }
}
