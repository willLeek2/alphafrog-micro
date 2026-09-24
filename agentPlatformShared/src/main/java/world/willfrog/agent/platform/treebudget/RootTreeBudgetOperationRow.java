package world.willfrog.agent.platform.treebudget;

/** 数据库中的操作身份与状态。 */
public class RootTreeBudgetOperationRow {
    private String operationId;
    private String rootRunId;
    private String kind;
    private String state;

    public String getOperationId() { return operationId; }
    public void setOperationId(String operationId) { this.operationId = operationId; }
    public String getRootRunId() { return rootRunId; }
    public void setRootRunId(String rootRunId) { this.rootRunId = rootRunId; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
}
