package world.willfrog.agent.platform.finance;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.mapper.FinanceMetricRecordMapper;
import world.willfrog.agent.platform.mapper.FinanceRecordBatchMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FinanceRecordPersisterTest {

    private final FinanceRecordBatchMapper batchMapper = mock(FinanceRecordBatchMapper.class);
    private final FinanceMetricRecordMapper recordMapper = mock(FinanceMetricRecordMapper.class);
    private final FinanceRecordPersister persister = new FinanceRecordPersister(batchMapper, recordMapper);





    private static FinanceRecordBatch batch(String contentDigest) {
        return FinanceRecordBatch.builder()
                .runId("run-1").todoId("todo-1").executePythonToolCallId("execute-1")
                .entryPoint("sync").terminalStatus("SUCCEEDED").exitCode(0)
                .recordCount(1).recordBytes(10L).recordDigest("batch-raw")
                .recordSetComplete(true).dropReason("").schemaValid(true).renderable(true)
                .actualEnvironmentJson("{}").validationErrorJson("[]")
                .batchContentDigest(contentDigest).build();
    }

    private static FinanceMetricRecord record(String recordId, String rawDigest) {
        return FinanceMetricRecord.builder()
                .recordId(recordId).runId("run-1").todoId("todo-1")
                .executePythonToolCallId("execute-1").recordIndex(0).rawDigest(rawDigest)
                .rawPayload("{}").parametersJson("{}").inputRefsJson("[]").checksJson("{}")
                .declaredEvidence("CUSTOM_UNVERIFIED")
                .effectiveInternalEvidence("CUSTOM_UNVERIFIED")
                .renderable(false).validationErrorJson("[]").build();
    }
}
