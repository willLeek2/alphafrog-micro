package world.willfrog.agent.platform.experiment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.util.JsonFormat;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import world.willfrog.agent.platform.childrun.ChildRunIntentStore;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.alphafrogmicro.common.racing.RacingCallException;
import world.willfrog.racing.idl.RuntimeCallContext;
import world.willfrog.racing.idl.TargetIdentity;

/** 根绑定只从持久记录读取；提示词准备使用专属字段更新，不能整体替换运行上下文。 */
@Component
public final class ExperimentRunBindingStore {
    public static final String CONTEXT_FIELD = "experiment_context";
    private final AgentRunMapper runs;
    private final ChildRunIntentStore children;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;

    public ExperimentRunBindingStore(AgentRunMapper runs, ChildRunIntentStore children,
            ObjectMapper json, PlatformTransactionManager transactionManager) {
        this.runs = runs;
        this.children = children;
        this.json = json;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public RuntimeCallContext saved(String runId) {
        return read(runId).context();
    }

    /** 创建根记录时仅保存服务生成的值，不从用户 context_json 中提升身份。 */
    public JsonNode toExtValue(RuntimeCallContext context) {
        try {
            return json.readTree(JsonFormat.printer().preservingProtoFieldNames()
                    .omittingInsignificantWhitespace().print(context));
        } catch (Exception ex) {
            throw failure("INVALID_ARGUMENT", "实验运行上下文无法序列化");
        }
    }

    /** 成功返回前提交专属更新并重读；未知写入结果只确认原结果，不重新选择。 */
    public void confirmPromptSelection(RuntimeCallContext confirmedContext, Map<String, Object> selectionMap) {
        if (confirmedContext == null || !confirmedContext.hasRoot()
                || selectionMap == null || selectionMap.isEmpty()) {
            throw failure("INVALID_ARGUMENT", "提示词选择缺少根归属或明确内容");
        }
        JsonNode selection = json.valueToTree(selectionMap);
        RuntimeException writeFailure = null;
        try {
            transaction.executeWithoutResult(status -> {
                Binding saved = read(confirmedContext.getRoot().getRootId());
                requireSameBinding(saved.context(), confirmedContext);
                runs.confirmPromptSelection(saved.root().getId(), saved.root().getUserId(),
                        saved.node().toString(), selection.toString());
            });
        } catch (RuntimeException ex) {
            writeFailure = ex;
        }
        // 独立事务在此之前已经退出，即使写入事务失败，新查询也不会沿用失败事务。
        try {
            Binding actual = transaction.execute(status -> read(confirmedContext.getRoot().getRootId()));
            if (actual == null) throw failure("PERSISTENCE_UNCONFIRMED", "根记录重读结果为空");
            requireSameBinding(actual.context(), confirmedContext);
            if (selection.equals(actual.selection())) return;
            if (actual.selection() != null) throw failure("REVISION_CONFLICT", "根运行已经保存另一份提示词选择");
        } catch (RacingCallException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw failure("PERSISTENCE_UNCONFIRMED", "无法确认根运行提示词选择的保存结果");
        }
        if (writeFailure instanceof RacingCallException known) throw known;
        throw failure("PERSISTENCE_UNCONFIRMED", "根运行提示词选择尚未确认保存");
    }

    public JsonNode savedPromptSelection(String runId) {
        return read(runId).selection();
    }

    private Binding read(String runId) {
        String rootId = children.rootRunIdOf(runId)
                .orElseThrow(() -> failure("NOT_FOUND", "没有找到已确认的根运行关系"));
        AgentRun root = runs.findById(rootId);
        if (root == null) throw failure("NOT_FOUND", "根运行记录不存在");
        try {
            JsonNode ext = json.readTree(root.getExt());
            JsonNode node = ext == null ? null : ext.get(CONTEXT_FIELD);
            if (node == null || !node.isObject()) throw failure("SCOPE_UNAVAILABLE", "根运行缺少持久实验上下文");
            RuntimeCallContext.Builder builder = RuntimeCallContext.newBuilder();
            JsonFormat.parser().merge(node.toString(), builder);
            RuntimeCallContext context = builder.build();
            if (!context.hasRoot() || !context.hasTarget() || !rootId.equals(context.getRoot().getRootId())
                    || !"alphafrog".equals(context.getRoot().getOwnerNamespace())
                    || !"agent_run".equals(context.getRoot().getOwnerKind())) {
                throw failure("SCOPE_MISMATCH", "持久实验上下文与根运行归属不一致");
            }
            return new Binding(root, context, node, ext.get("prompt_selection"));
        } catch (RacingCallException ex) {
            throw ex;
        } catch (Exception ex) {
            throw failure("SCOPE_UNAVAILABLE", "根运行的持久实验上下文无法读取");
        }
    }

    static void requireSameBinding(RuntimeCallContext saved, RuntimeCallContext current) {
        TargetIdentity left = saved.getTarget();
        TargetIdentity right = current.getTarget();
        if (!saved.getRoot().equals(current.getRoot())
                || !left.getEnvironment().equals(right.getEnvironment())
                || !left.getTargetScopeInstanceId().equals(right.getTargetScopeInstanceId())
                || !left.getTargetRouteTag().equals(right.getTargetRouteTag())
                || !left.getTargetDeploymentId().equals(right.getTargetDeploymentId())) {
            throw failure("SCOPE_MISMATCH", "提示词准备上下文与持久根绑定不一致");
        }
    }

    private static RacingCallException failure(String code, String message) {
        return RacingCallException.failure(code, message);
    }

    private record Binding(AgentRun root, RuntimeCallContext context, JsonNode node, JsonNode selection) { }
}
