package world.willfrog.agent.platform.finance;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/** Persisted audit row for one executePython finance-record batch. */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class FinanceRecordBatch {
    private Long id;
    private String runId;
    private String todoId;
    private String executePythonToolCallId;
    private String entryPoint;
    private String terminalStatus;
    private Integer exitCode;
    private Integer recordCount;
    private Long recordBytes;
    private String recordDigest;
    private Boolean recordSetComplete;
    private String dropReason;
    private Boolean schemaValid;
    private Boolean renderable;
    private String actualEnvironmentJson;
    private String validationErrorJson;
    private String batchContentDigest;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
