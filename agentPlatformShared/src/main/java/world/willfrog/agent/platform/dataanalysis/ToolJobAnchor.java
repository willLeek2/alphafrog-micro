package world.willfrog.agent.platform.dataanalysis;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;

/**
 * Durable external tool job state persisted in alphafrog_agent_run.tool_job_anchor_json.
 * This is the source of truth; Redis cache/index can be rebuilt from this column.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ToolJobAnchor {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules();

    private int schemaVersion = 1;
    private int checkpointVersion; // incremented on each atomic checkpoint merge
    private String operationId;
    private String taskId;
    private String toolCallId;
    private int attempt;
    private String todoId;
    private int sequence;
    private String runDisposition;
    private boolean autoResume = true;
    private String resumeState; // READY, LAUNCHING, CONSUMED
    private String resumeToken; // UUID for idempotent launch dedupe
    private long resumeLeaseVersion; // incremented on each new claim
    private Instant resumeClaimedAt;

    // durable estimate from admission (reused in terminal envelope)

    // resume context (§9.10): saved when tool job goes pending
    private String completedTodosJson;   // completed todo id list
    private String datasetRefsJson;      // dataset references
    private int toolCallsUsed;           // tool call count at suspend point

    // reservation snapshot
    private String reservationJson;
    private String estimateJson; // durable estimate from admission (reused in terminal envelope)

    // dataset snapshot
    private String datasetSnapshotJson;
    private String datasetSnapshotDigest;

    // terminal envelope
    private String terminalStatus;
    private String terminalResultPreview;
    private String terminalRawRef;
    private String terminalErrorCode;
    private String terminalUsageJson;
    private Instant terminalAt;

    // result fetch
    private String resultFetchState; // PENDING, LOST
    private int resultFetchAttempts;
    private Instant terminalConfirmedAt;
    private String sandboxTerminalStatus;

    // finalizer progress
    private String finalizerStep;
    private String finalizerError;

    // post-terminal flags
    private boolean usagePersisted;
    private boolean terminalEventEmitted;
    private boolean resultConsumed;

    // timing
    private Instant nextPollAt;
    private Instant timeoutAt;

    public ToolJobAnchor() {}

    public static ToolJobAnchor fromJson(String json) {
        try {
            return MAPPER.readValue(json, ToolJobAnchor.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to parse ToolJobAnchor", e);
        }
    }

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize ToolJobAnchor", e);
        }
    }

    // ---- getters / setters ----

    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int schemaVersion) { this.schemaVersion = schemaVersion; }

    public int getCheckpointVersion() { return checkpointVersion; }
    public void setCheckpointVersion(int checkpointVersion) { this.checkpointVersion = checkpointVersion; }

    public String getOperationId() { return operationId; }
    public void setOperationId(String operationId) { this.operationId = operationId; }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }

    public String getToolCallId() { return toolCallId; }
    public void setToolCallId(String toolCallId) { this.toolCallId = toolCallId; }

    public int getAttempt() { return attempt; }
    public void setAttempt(int attempt) { this.attempt = attempt; }

    public String getTodoId() { return todoId; }
    public void setTodoId(String todoId) { this.todoId = todoId; }

    public int getSequence() { return sequence; }
    public void setSequence(int sequence) { this.sequence = sequence; }

    public String getRunDisposition() { return runDisposition; }
    public void setRunDisposition(String runDisposition) { this.runDisposition = runDisposition; }

    public boolean isAutoResume() { return autoResume; }
    public void setAutoResume(boolean autoResume) { this.autoResume = autoResume; }

    public String getResumeState() { return resumeState; }
    public void setResumeState(String resumeState) { this.resumeState = resumeState; }

    public String getResumeToken() { return resumeToken; }
    public void setResumeToken(String resumeToken) { this.resumeToken = resumeToken; }

    public long getResumeLeaseVersion() { return resumeLeaseVersion; }
    public void setResumeLeaseVersion(long resumeLeaseVersion) { this.resumeLeaseVersion = resumeLeaseVersion; }

    public Instant getResumeClaimedAt() { return resumeClaimedAt; }
    public void setResumeClaimedAt(Instant resumeClaimedAt) { this.resumeClaimedAt = resumeClaimedAt; }

    public String getEstimateJson() { return estimateJson; }
    public void setEstimateJson(String estimateJson) { this.estimateJson = estimateJson; }

    public String getCompletedTodosJson() { return completedTodosJson; }
    public void setCompletedTodosJson(String completedTodosJson) { this.completedTodosJson = completedTodosJson; }

    public String getDatasetRefsJson() { return datasetRefsJson; }
    public void setDatasetRefsJson(String datasetRefsJson) { this.datasetRefsJson = datasetRefsJson; }

    public int getToolCallsUsed() { return toolCallsUsed; }
    public void setToolCallsUsed(int toolCallsUsed) { this.toolCallsUsed = toolCallsUsed; }

    public String getReservationJson() { return reservationJson; }
    public void setReservationJson(String reservationJson) { this.reservationJson = reservationJson; }

    public String getDatasetSnapshotJson() { return datasetSnapshotJson; }
    public void setDatasetSnapshotJson(String datasetSnapshotJson) { this.datasetSnapshotJson = datasetSnapshotJson; }

    public String getDatasetSnapshotDigest() { return datasetSnapshotDigest; }
    public void setDatasetSnapshotDigest(String datasetSnapshotDigest) { this.datasetSnapshotDigest = datasetSnapshotDigest; }

    public String getTerminalStatus() { return terminalStatus; }
    public void setTerminalStatus(String terminalStatus) { this.terminalStatus = terminalStatus; }

    public String getTerminalResultPreview() { return terminalResultPreview; }
    public void setTerminalResultPreview(String terminalResultPreview) { this.terminalResultPreview = terminalResultPreview; }

    public String getTerminalRawRef() { return terminalRawRef; }
    public void setTerminalRawRef(String terminalRawRef) { this.terminalRawRef = terminalRawRef; }

    public String getTerminalErrorCode() { return terminalErrorCode; }
    public void setTerminalErrorCode(String terminalErrorCode) { this.terminalErrorCode = terminalErrorCode; }

    public String getTerminalUsageJson() { return terminalUsageJson; }
    public void setTerminalUsageJson(String terminalUsageJson) { this.terminalUsageJson = terminalUsageJson; }

    public Instant getTerminalAt() { return terminalAt; }
    public void setTerminalAt(Instant terminalAt) { this.terminalAt = terminalAt; }

    public String getResultFetchState() { return resultFetchState; }
    public void setResultFetchState(String resultFetchState) { this.resultFetchState = resultFetchState; }

    public int getResultFetchAttempts() { return resultFetchAttempts; }
    public void setResultFetchAttempts(int resultFetchAttempts) { this.resultFetchAttempts = resultFetchAttempts; }

    public Instant getTerminalConfirmedAt() { return terminalConfirmedAt; }
    public void setTerminalConfirmedAt(Instant terminalConfirmedAt) { this.terminalConfirmedAt = terminalConfirmedAt; }

    public String getSandboxTerminalStatus() { return sandboxTerminalStatus; }
    public void setSandboxTerminalStatus(String sandboxTerminalStatus) { this.sandboxTerminalStatus = sandboxTerminalStatus; }

    public String getFinalizerStep() { return finalizerStep; }
    public void setFinalizerStep(String finalizerStep) { this.finalizerStep = finalizerStep; }

    public String getFinalizerError() { return finalizerError; }
    public void setFinalizerError(String finalizerError) { this.finalizerError = finalizerError; }

    public boolean isUsagePersisted() { return usagePersisted; }
    public void setUsagePersisted(boolean usagePersisted) { this.usagePersisted = usagePersisted; }

    public boolean isTerminalEventEmitted() { return terminalEventEmitted; }
    public void setTerminalEventEmitted(boolean terminalEventEmitted) { this.terminalEventEmitted = terminalEventEmitted; }

    public boolean isResultConsumed() { return resultConsumed; }
    public void setResultConsumed(boolean resultConsumed) { this.resultConsumed = resultConsumed; }

    public Instant getNextPollAt() { return nextPollAt; }
    public void setNextPollAt(Instant nextPollAt) { this.nextPollAt = nextPollAt; }

    public Instant getTimeoutAt() { return timeoutAt; }
    public void setTimeoutAt(Instant timeoutAt) { this.timeoutAt = timeoutAt; }
}
