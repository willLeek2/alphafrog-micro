package world.willfrog.agentlangchain.execution;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import world.willfrog.agent.platform.dataanalysis.CompletedTodoRecord;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 冻结工作流的粗粒度执行检查点。
 *
 * <p>它只保存 LINEAR 已完成前缀和下一个 Todo 边界，不保存正在运行的线程、容器或
 * ToolJob 身份。服务退出时，当前 Todo 从开头重新执行；DAG 不读取旧节点进度。</p>
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkflowExecutionCheckpoint {

    public static final String CURRENT_VERSION = "v1";
    public static final String LINEAR = "LINEAR";
    public static final String DAG = "DAG";
    public static final String FINAL_TODO_ID = "__FINAL_ANSWER__";

    private String version = CURRENT_VERSION;
    private String workflow = LINEAR;
    private List<CompletedTodoRecord> completedTodos = new ArrayList<>();
    private String nextTodoId;
    private int toolCallsUsed;
    /** checkpoint 输出中实际出现的 Run-scoped rawRef，启动恢复前逐个校验。 */
    private List<String> rawRefs = new ArrayList<>();
    /**
     * 当前 LINEAR Todo，或本轮整个 DAG 中，已经真正开始执行过的工具。
     * LINEAR 每完成一个 Todo 后清空；DAG 保留到 Run 终态。它用于判断崩溃重放会不会重复副作用。
     */
    private List<String> startedTools = new ArrayList<>();
    private Instant updatedAt;
}
