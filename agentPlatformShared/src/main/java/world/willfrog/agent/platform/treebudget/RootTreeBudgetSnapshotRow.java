package world.willfrog.agent.platform.treebudget;

/** 数据库中的根树额度快照。 */
public class RootTreeBudgetSnapshotRow {
    private long llmCalls;
    private long toolCalls;
    private long activeNodes;
    private long externalWaits;

    public long getLlmCalls() { return llmCalls; }
    public void setLlmCalls(long llmCalls) { this.llmCalls = llmCalls; }
    public long getToolCalls() { return toolCalls; }
    public void setToolCalls(long toolCalls) { this.toolCalls = toolCalls; }
    public long getActiveNodes() { return activeNodes; }
    public void setActiveNodes(long activeNodes) { this.activeNodes = activeNodes; }
    public long getExternalWaits() { return externalWaits; }
    public void setExternalWaits(long externalWaits) { this.externalWaits = externalWaits; }
}
