package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.dataanalysis.CompletedTodoRecord;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisEstimate;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.workflow.AgentRunDatasetSnapshot;

import java.util.List;

/**
 * 在旧 worker 退出前原子保存完整 Run checkpoint。
 *
 * <p>入参是 pipeline 捕获的不可变 {@link ToolJobCheckpointRequest}；返回 true
 * 才表示数据库已经接受这份上下文。任何身份漂移、版本冲突、快照损坏或字段缺失
 * 都返回 false，由 checkpoint failure recovery 建立持久化处置 owner。</p>
 */
@Service
public class ToolJobCheckpointService implements ToolJobCheckpointWriter {

    private static final Logger log = LoggerFactory.getLogger(ToolJobCheckpointService.class);

    private final AgentRunMapper agentRunMapper;
    private final ToolJobAnchorService anchorService;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public ToolJobCheckpointService(AgentRunMapper agentRunMapper,
                                     ToolJobAnchorService anchorService) {
        this.agentRunMapper = agentRunMapper;
        this.anchorService = anchorService;
    }

    @Override
    public boolean captureAndSave(ToolJobCheckpointRequest request) {
        // runId 是数据库行主键，也是所有后续 CAS 的第一重边界。
        String runId = request.getRunId();
        // 空 runId 无法建立 durable owner，直接拒绝。
        if (runId == null || runId.isBlank()) {
            log.warn("Checkpoint rejected: blank runId");
            return false;
        }

        // 重新读取最新 Run，不能使用 pipeline 早先持有的对象快照。
        AgentRun run = agentRunMapper.findById(runId);
        if (run == null) {
            log.warn("Checkpoint rejected: run not found id={}", runId);
            return false;
        }

        // anchor 在工具创建/附着阶段写入，包含后台任务不可变身份和当前版本。
        ToolJobAnchor anchor = ToolJobAnchor.fromJson(run.getToolJobAnchorJson());
        if (anchor == null) {
            log.warn("Checkpoint rejected: no anchor for run={}", runId);
            return false;
        }

        // 四元身份必须完全相等，旧 worker 不能给新一轮后台任务写 checkpoint。
        if (!validateIdentity(anchor, request)) {
            return false;
        }

        // requestVersion 是 pipeline 捕获时看到的版本，必须与数据库当前版本相等。
        // 不能在这里“刷新成最新版本”，否则延迟写者会借用新版本覆盖更近的 checkpoint。
        int requestVersion = request.getExpectedCheckpointVersion();
        int anchorVersion = anchor.getCheckpointVersion();
        if (requestVersion != anchorVersion) {
            log.warn("Checkpoint rejected: checkpointVersion mismatch request={} anchor={} for run={}",
                    requestVersion, anchorVersion, runId);
            return false;
        }

        // 对所有恢复必需字段做类型级校验，禁止从旧 anchor 静默继承缺失值。
        // dataset snapshot 服务于新 worker 恢复；estimate 服务于终态容量释放。
        if (!validateCheckpointFields(request, runId)) {
            return false;
        }

        // 把已完成 Todo 前缀序列化成 JSON；非空列表序列化失败必须拒绝。
        String todosJson = serializeTodos(request.getCompletedTodos());
        if (request.getCompletedTodos() != null && !request.getCompletedTodos().isEmpty()
                && todosJson == null) {
            log.error("Checkpoint rejected: failed to serialize completedTodos for run={}", runId);
            return false;
        }
        // 合法空列表规范化为 []，避免恢复端区分 null 与空前缀。
        if (todosJson == null) {
            todosJson = "[]";
        }

        // 先在内存 anchor 上组装完整白名单字段；真正原子性由下面 SQL CAS 提供。
        // 使用 requestVersion 而不是再次读取的“最新值”，保持写者版本冻结。
        anchor.setTodoId(request.getTodoId());
        // sequence 与 todoId 共同描述恢复注入位置。
        anchor.setSequence(request.getSequence());
        // completedTodosJson 保存无需重跑的计划前缀。
        anchor.setCompletedTodosJson(todosJson);
        // snapshot 正文和 digest 必须成对写入。
        anchor.setDatasetSnapshotJson(request.getDatasetSnapshotJson());
        anchor.setDatasetSnapshotDigest(request.getDatasetSnapshotDigest());
        // refs 兼容 Todo 输出中的数据引用恢复。
        anchor.setDatasetRefsJson(request.getDatasetRefsJson());
        // 延续 run 级工具调用预算。
        anchor.setToolCallsUsed(request.getToolCallsUsed());
        // 终态 envelope 需要原准入估算才能安全释放容量。
        anchor.setEstimateJson(request.getEstimateJson());
        // SQL 会在 SET 中原子自增；这里保留 expected 版本供 WHERE 使用。
        anchor.setCheckpointVersion(requestVersion);

        // SQL 只合并 checkpoint 白名单，保留并发写入的 reservation/terminal/finalizer 字段。
        // WHERE 同时绑定身份、taskId 和 expectedCheckpointVersion；任何竞争都会 rows=0。
        boolean ok = anchorService.checkpointUpdate(runId, anchor, run.getStatus(),
                request.getTodoId(), request.getSequence(),
                todosJson,
                request.getDatasetSnapshotJson(), request.getDatasetSnapshotDigest(),
                request.getDatasetRefsJson(), request.getToolCallsUsed(),
                request.getEstimateJson());
        // rows=0 表示本写者失去所有权，不能把内存上下文当成已持久化。
        if (!ok) {
            log.warn("Checkpoint CAS failed for run={} status={} op={} v={}",
                    runId, run.getStatus(), anchor.getOperationId(), anchor.getCheckpointVersion());
            return false;
        }
        // 到这里旧 worker 才具备安全退出并释放槽位的条件。
        log.info("Checkpoint persisted for run={} op={} todo={} todos={} tools={} v={}",
                runId, anchor.getOperationId(), request.getTodoId(),
                request.getCompletedTodos().size(), request.getToolCallsUsed(),
                anchor.getCheckpointVersion());
        return true;
    }

    private boolean validateIdentity(ToolJobAnchor anchor, ToolJobCheckpointRequest request) {
        // operationId 绑定幂等外部操作，缺失或漂移都拒绝。
        String reqOpId = request.getOperationId();
        if (reqOpId == null || reqOpId.isBlank()) {
            log.error("Checkpoint rejected: missing operationId for run={}", request.getRunId());
            return false;
        }
        if (!reqOpId.equals(anchor.getOperationId())) {
            log.error("Checkpoint rejected: operationId mismatch anchor={} request={}",
                    anchor.getOperationId(), reqOpId);
            return false;
        }
        // toolCallId 绑定 Agent 逻辑调用。
        String reqTcId = request.getToolCallId();
        if (reqTcId == null || reqTcId.isBlank()) {
            log.error("Checkpoint rejected: missing toolCallId for run={}", request.getRunId());
            return false;
        }
        if (!reqTcId.equals(anchor.getToolCallId())) {
            log.error("Checkpoint rejected: toolCallId mismatch anchor={} request={}",
                    anchor.getToolCallId(), reqTcId);
            return false;
        }
        // attempt 从 1 开始；0 代表调用方没有正确捕获重试轮次。
        if (request.getAttempt() <= 0) {
            log.error("Checkpoint rejected: missing attempt for run={}", request.getRunId());
            return false;
        }
        if (request.getAttempt() != anchor.getAttempt()) {
            log.error("Checkpoint rejected: attempt mismatch anchor={} request={}",
                    anchor.getAttempt(), request.getAttempt());
            return false;
        }
        // taskId 绑定 Sandbox 实体后台任务，防止同操作错误附着另一任务。
        String reqTaskId = request.getTaskId();
        if (reqTaskId == null || reqTaskId.isBlank()) {
            log.error("Checkpoint rejected: missing taskId for run={}", request.getRunId());
            return false;
        }
        if (!reqTaskId.equals(anchor.getTaskId())) {
            log.error("Checkpoint rejected: taskId mismatch anchor={} request={}",
                    anchor.getTaskId(), reqTaskId);
            return false;
        }
        // 四元身份全部一致，才继续验证可变上下文。
        return true;
    }

    private String serializeTodos(List<CompletedTodoRecord> todos) {
        if (todos == null || todos.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(todos);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize completedTodos", e);
            return null;
        }
    }

    private boolean validateCheckpointFields(ToolJobCheckpointRequest request, String runId) {
        // todoId 是恢复注入位置，不能为空。
        if (request.getTodoId() == null || request.getTodoId().isBlank()) {
            log.warn("Checkpoint rejected: missing todoId for run={}", runId);
            return false;
        }
        // null 表示调用方没有捕获列表；合法的“尚无已完成节点”必须传空列表。
        if (request.getCompletedTodos() == null) {
            log.warn("Checkpoint rejected: completedTodos is null for run={}", runId);
            return false;
        }
        // sequence 与 toolCallsUsed 都是单调非负上下文。
        if (request.getSequence() < 0) {
            log.warn("Checkpoint rejected: sequence={} < 0 for run={}", request.getSequence(), runId);
            return false;
        }
        if (request.getToolCallsUsed() < 0) {
            log.warn("Checkpoint rejected: toolCallsUsed={} < 0 for run={}", request.getToolCallsUsed(), runId);
            return false;
        }

        // 逐个按领域类型反序列化；只做 JSON 语法校验会放过 null、错误根类型和缺字段。
        if (!validateSnapshotType(request, runId)) return false;
        // refs 必须是字符串数组，恢复注册表不接受任意 JSON。
        if (!validateRefsType(request, runId)) return false;
        // estimate 必须能构造资源估算，终态 release 才有完整证明。
        if (!validateEstimateType(request, runId)) return false;

        return true;
    }

    private boolean validateSnapshotType(ToolJobCheckpointRequest request, String runId) {
        // 快照正文缺失时，新 worker 无法恢复 dataset 编号映射。
        String json = request.getDatasetSnapshotJson();
        if (json == null || json.isBlank()) {
            log.warn("Checkpoint rejected: missing datasetSnapshotJson for run={}", runId);
            return false;
        }
        // 用领域类型解析，确保结构与恢复 API 契约一致。
        AgentRunDatasetSnapshot snapshot;
        try {
            snapshot = objectMapper.readValue(json, AgentRunDatasetSnapshot.class);
        } catch (Exception e) {
            log.warn("Checkpoint rejected: datasetSnapshotJson not a valid AgentRunDatasetSnapshot for run={} err={}",
                    runId, e.getMessage());
            return false;
        }
        if (snapshot == null) {
            log.warn("Checkpoint rejected: datasetSnapshotJson deserialized to null for run={}", runId);
            return false;
        }
        // 从解析后的不可变字段重算 digest，而不是信任请求携带值。
        String digest = request.getDatasetSnapshotDigest();
        String computed = snapshot.immutableDigest();
        if (!computed.equals(digest)) {
            log.warn("Checkpoint rejected: datasetSnapshotDigest mismatch expected={} computed={} for run={}",
                    digest, computed, runId);
            return false;
        }
        // 正文与 digest 一致，恢复端可再次执行相同校验。
        return true;
    }

    private boolean validateRefsType(ToolJobCheckpointRequest request, String runId) {
        String json = request.getDatasetRefsJson();
        if (json == null || json.isBlank()) {
            log.warn("Checkpoint rejected: missing datasetRefsJson for run={}", runId);
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.isArray()) {
                log.warn("Checkpoint rejected: datasetRefsJson is not a JSON array for run={}", runId);
                return false;
            }
            for (JsonNode element : root) {
                if (element.isNull()) {
                    log.warn("Checkpoint rejected: datasetRefsJson contains null element for run={}", runId);
                    return false;
                }
                if (!element.isTextual()) {
                    log.warn("Checkpoint rejected: datasetRefsJson contains non-string element for run={}",
                            runId);
                    return false;
                }
            }
        } catch (Exception e) {
            log.warn("Checkpoint rejected: datasetRefsJson not valid JSON for run={} err={}",
                    runId, e.getMessage());
            return false;
        }
        return true;
    }

    private boolean validateEstimateType(ToolJobCheckpointRequest request, String runId) {
        String json = request.getEstimateJson();
        if (json == null || json.isBlank()) {
            log.warn("Checkpoint rejected: missing estimateJson for run={}", runId);
            return false;
        }
        try {
            DataAnalysisEstimate estimate = objectMapper.readValue(json, DataAnalysisEstimate.class);
            if (estimate == null) {
                log.warn("Checkpoint rejected: estimateJson deserialized to null for run={}", runId);
                return false;
            }
        } catch (Exception e) {
            log.warn("Checkpoint rejected: estimateJson not a valid DataAnalysisEstimate for run={} err={}",
                    runId, e.getMessage());
            return false;
        }
        return true;
    }
}
