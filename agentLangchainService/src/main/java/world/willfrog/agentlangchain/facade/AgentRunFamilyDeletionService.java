package world.willfrog.agentlangchain.facade;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import world.willfrog.agent.platform.childrun.ChildRunIntentStore;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;

import java.util.ArrayList;
import java.util.List;

/** 把用户的一次删除扩展到已结清的整棵调用树，避免留下可见的孤立子 Run。 */
@Service
@RequiredArgsConstructor
public class AgentRunFamilyDeletionService {
    private static final ObjectMapper EXT_MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final AgentRunMapper runs;
    private final ChildRunIntentStore children;

    /** 全部 Run 先持久禁止新资源；只有明确冻结为关闭的 Run 可以跳过沙箱确认。 */
    @Transactional
    public DeletionPlan beginDeletion(String rootRunId, String userId) {
        List<AgentRun> family = lockDeletableFamily(rootRunId, userId);
        List<String> workspaceRunIds = new ArrayList<>();
        for (AgentRun run : family) {
            if (runs.markDeletionStarted(run.getId(), userId) != 1) {
                throw new IllegalStateException("无法持久标记待删除 Run：" + run.getId());
            }
            if (!workspaceExplicitlyDisabled(run)) {
                workspaceRunIds.add(run.getId());
            }
        }
        return new DeletionPlan(family.stream().map(AgentRun::getId).toList(), workspaceRunIds);
    }

    public record DeletionPlan(List<String> runIds, List<String> workspaceRunIds) {
        public DeletionPlan {
            runIds = List.copyOf(runIds);
            workspaceRunIds = List.copyOf(workspaceRunIds);
        }
    }

    private static boolean workspaceExplicitlyDisabled(AgentRun run) {
        String ext = run.getExt();
        if (ext == null || ext.isBlank()) {
            return false;
        }
        try {
            JsonNode root = EXT_MAPPER.readTree(ext);
            JsonNode enabled = root == null || !root.isObject()
                    ? null : root.get("python_workspace_enabled");
            return enabled != null && enabled.isBoolean() && !enabled.booleanValue();
        } catch (Exception malformedExt) {
            // 配置损坏时不能断言此 Run 从未创建过工作区，继续要求沙箱确认。
            return false;
        }
    }

    /** 需要工作区确认的 Run 已经封口、查盘和删盘后，才允许物理删除整棵树。 */
    @Transactional
    public List<String> finishDeletionAfterResourcesConfirmed(String rootRunId, String userId,
                                                               List<String> confirmedRunIds) {
        List<AgentRun> family = lockDeletableFamily(rootRunId, userId);
        List<String> expected = family.stream().map(AgentRun::getId).toList();
        if (confirmedRunIds == null || !confirmedRunIds.equals(expected)) {
            throw new IllegalStateException("整棵调用树的删除计划与资源确认结果不一致");
        }
        for (AgentRun run : family) {
            if (!runs.isDeletionStarted(run.getId())) {
                throw new IllegalStateException("Run 的持久删除标记缺失：" + run.getId());
            }
        }
        // 子意图可能引用更深一层的子 Run：先删关系，再删 Run 主记录。
        runs.deleteChildIntentsByRoot(rootRunId);
        List<String> deleted = new ArrayList<>(family.size());
        for (int i = 0; i < family.size() - 1; i++) {
            AgentRun child = family.get(i);
            if (runs.deleteMarkedByIdAndUser(child.getId(), userId) != 1) {
                throw new IllegalStateException("删除子 Run 时状态已变化：" + child.getId());
            }
            deleted.add(child.getId());
        }
        runs.deleteEmptyTreeCapacityByRoot(rootRunId);
        if (runs.deleteMarkedByIdAndUser(rootRunId, userId) != 1) {
            throw new IllegalStateException("删除父 Run 时状态已变化：" + rootRunId);
        }
        deleted.add(rootRunId);
        return List.copyOf(deleted);
    }

    private List<AgentRun> lockDeletableFamily(String rootRunId, String userId) {
        AgentRun root = runs.findByIdAndUserForUpdate(rootRunId, userId);
        if (root == null) {
            throw new IllegalArgumentException("run not found");
        }
        if (runs.isChildRun(rootRunId)) {
            throw new IllegalStateException("子代理是内部执行记录，请通过父 Run 管理");
        }
        if (running(root.getStatus())) {
            throw new IllegalStateException("run is running, cancel/pause first");
        }
        if (children.hasUnsettledDescendants(rootRunId)) {
            throw new IllegalStateException("子代理或外部任务仍在收尾，稍后再删除父 Run");
        }

        List<AgentRun> childRuns = runs.listChildRunsByRootForUpdate(rootRunId);
        if (!childRuns.isEmpty() && !terminal(root.getStatus())) {
            throw new IllegalStateException("父 Run 尚未结束，请先取消后再删除父子记录");
        }
        for (AgentRun child : childRuns) {
            if (!userId.equals(child.getUserId()) || !terminal(child.getStatus())) {
                throw new IllegalStateException("子 Run 身份或终态尚未确认，不能删除父子记录");
            }
        }
        List<AgentRun> family = new ArrayList<>(childRuns.size() + 1);
        family.addAll(childRuns);
        family.add(root);
        return List.copyOf(family);
    }

    private static boolean terminal(AgentRunStatus status) {
        return status == AgentRunStatus.COMPLETED || status == AgentRunStatus.PARTIAL
                || status == AgentRunStatus.FAILED || status == AgentRunStatus.CANCELED
                || status == AgentRunStatus.EXPIRED;
    }

    private static boolean running(AgentRunStatus status) {
        return status == AgentRunStatus.RECEIVED || status == AgentRunStatus.PLANNING
                || status == AgentRunStatus.EXECUTING || status == AgentRunStatus.SUMMARIZING
                || status == AgentRunStatus.CANCELING || status == AgentRunStatus.WAITING_TOOL_JOB;
    }
}
