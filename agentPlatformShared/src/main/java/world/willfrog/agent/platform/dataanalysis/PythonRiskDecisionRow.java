package world.willfrog.agent.platform.dataanalysis;

/** 调用前审查的持久决定；评分审计不保存脚本正文。 */
public class PythonRiskDecisionRow {
    private String operationId;
    private String runId;
    private String decision;
    private Integer riskScore;
    private String configJson;

    public String getOperationId() { return operationId; }
    public void setOperationId(String operationId) { this.operationId = operationId; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }
    public Integer getRiskScore() { return riskScore; }
    public void setRiskScore(Integer riskScore) { this.riskScore = riskScore; }
    public String getConfigJson() { return configJson; }
    public void setConfigJson(String configJson) { this.configJson = configJson; }
}
