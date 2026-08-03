package world.willfrog.agentlangchain.tooljob;

import world.willfrog.agent.platform.dataanalysis.CompletedTodoRecord;

import java.util.Collections;
import java.util.List;

/**
 * 从 durable anchor 解码出的单次恢复交接对象。
 *
 * <p>ResumeService 创建它，Launcher 把它交给 pipeline，LINEAR executor 再用它
 * 跳过 planner、还原已完成 Todo 和 dataset 快照，并把外部工具终态注入原挂起节点。
 * 对象会在结果被工作流接受后原地更新，以便消费确认把新进度写回 anchor。</p>
 */
public class ToolJobResumeContext {

    // 已消费挂起 Todo 且没有后续节点时，用哨兵表示恢复点已经推进到最终回答阶段。
    public static final String FINAL_TODO_ID = "__FINAL__";

    // runId 约束本次交接只能恢复对应的 Agent Run。
    private String runId;
    // todoId 是当前注入终态结果的位置；也可能是 FINAL_TODO_ID。
    private String todoId;
    // todoSequence 用于校验已完成列表没有越过挂起节点。
    private int todoSequence;
    // resumeToken 是本轮恢复的随机幂等令牌，旧 launcher 不能复用。
    private String resumeToken;
    // resumeLeaseVersion 每次重新 claim 都递增，与 token 共同构成 fencing 条件。
    private long resumeLeaseVersion;
    // resumeLauncherOwnerId 绑定取得数据库租约的 launcher 实例，终态写入还会复核该 owner。
    private String resumeLauncherOwnerId;
    // completedTodos 是挂起前已经落稳的工作流前缀，恢复后不会重复执行。
    private List<CompletedTodoRecord> completedTodos = Collections.emptyList();
    // datasetSnapshotJson 保存 run 级数据集编号到真实引用的映射。
    private String datasetSnapshotJson;
    // digest 用于确认恢复的快照内容与 checkpoint 时完全一致。
    private String datasetSnapshotDigest;
    // toolCallsUsed 延续预算计数，防止切换 worker 后重新从零计费。
    private int toolCallsUsed;
    // terminalSuccess 决定恢复节点按完成还是失败落事件。
    private boolean terminalSuccess;
    // preview 是可直接注入后续模型上下文的有界结果摘要。
    private String terminalResultPreview;
    // rawRef 指向完整结果，避免把大结果塞回 Run 上下文。
    private String terminalRawRef;
    // resultConsumed 表示终态结果已被当前工作流接受，后续恢复从下一节点继续。
    private boolean resultConsumed;

    public ToolJobResumeContext() {}

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }

    public String getTodoId() { return todoId; }
    public void setTodoId(String todoId) { this.todoId = todoId; }

    public int getTodoSequence() { return todoSequence; }
    public void setTodoSequence(int todoSequence) { this.todoSequence = todoSequence; }

    public String getResumeToken() { return resumeToken; }
    public void setResumeToken(String resumeToken) { this.resumeToken = resumeToken; }

    public long getResumeLeaseVersion() { return resumeLeaseVersion; }
    public void setResumeLeaseVersion(long resumeLeaseVersion) { this.resumeLeaseVersion = resumeLeaseVersion; }

    public String getResumeLauncherOwnerId() { return resumeLauncherOwnerId; }
    public void setResumeLauncherOwnerId(String resumeLauncherOwnerId) { this.resumeLauncherOwnerId = resumeLauncherOwnerId; }

    public List<CompletedTodoRecord> getCompletedTodos() { return completedTodos; }
    public void setCompletedTodos(List<CompletedTodoRecord> completedTodos) { this.completedTodos = completedTodos; }

    public String getDatasetSnapshotJson() { return datasetSnapshotJson; }
    public void setDatasetSnapshotJson(String datasetSnapshotJson) { this.datasetSnapshotJson = datasetSnapshotJson; }

    public String getDatasetSnapshotDigest() { return datasetSnapshotDigest; }
    public void setDatasetSnapshotDigest(String datasetSnapshotDigest) { this.datasetSnapshotDigest = datasetSnapshotDigest; }

    public int getToolCallsUsed() { return toolCallsUsed; }
    public void setToolCallsUsed(int toolCallsUsed) { this.toolCallsUsed = toolCallsUsed; }

    public boolean isTerminalSuccess() { return terminalSuccess; }
    public void setTerminalSuccess(boolean terminalSuccess) { this.terminalSuccess = terminalSuccess; }

    public String getTerminalResultPreview() { return terminalResultPreview; }
    public void setTerminalResultPreview(String terminalResultPreview) { this.terminalResultPreview = terminalResultPreview; }

    public String getTerminalRawRef() { return terminalRawRef; }
    public void setTerminalRawRef(String terminalRawRef) { this.terminalRawRef = terminalRawRef; }

    public boolean isResultConsumed() { return resultConsumed; }
    public void setResultConsumed(boolean resultConsumed) { this.resultConsumed = resultConsumed; }
}
