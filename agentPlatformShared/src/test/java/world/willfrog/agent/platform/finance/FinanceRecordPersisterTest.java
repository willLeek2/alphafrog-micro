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

    @Test
    void insertsBatchAndRowsInOnePersistenceCall() {
        FinanceRecordBatch batch = batch("digest-a");
        FinanceMetricRecord record = record("record-a", "raw-a");
        when(batchMapper.insertIgnore(batch)).thenReturn(1);
        when(recordMapper.insertIgnore(record)).thenReturn(1);

        assertThat(persister.persist(batch, List.of(record)))
                .isEqualTo(FinanceRecordPersister.PersistenceOutcome.INSERTED);
        verify(recordMapper).insertIgnore(record);
    }

    @Test
    void exactReplayReadsBackAndReturnsNoOp() {
        FinanceRecordBatch batch = batch("digest-a");
        FinanceMetricRecord record = record("record-a", "raw-a");
        when(batchMapper.insertIgnore(batch)).thenReturn(0);
        when(batchMapper.findByIdentity("run-1", "todo-1", "execute-1")).thenReturn(batch);
        when(recordMapper.listByBatch("run-1", "todo-1", "execute-1")).thenReturn(List.of(record));

        assertThat(persister.persist(batch, List.of(record)))
                .isEqualTo(FinanceRecordPersister.PersistenceOutcome.ALREADY_PRESENT_SAME);
        verify(recordMapper, never()).insertIgnore(any());
    }

    @Test
    void sameBatchIdentityWithDifferentContentFailsClosed() {
        FinanceRecordBatch requested = batch("digest-new");
        FinanceRecordBatch existing = batch("digest-old");
        when(batchMapper.insertIgnore(requested)).thenReturn(0);
        when(batchMapper.findByIdentity("run-1", "todo-1", "execute-1")).thenReturn(existing);

        assertThatThrownBy(() -> persister.persist(requested, List.of()))
                .isInstanceOf(FinanceRecordProcessingException.class)
                .extracting("code")
                .isEqualTo("FINANCE_RECORD_BATCH_IDENTITY_CONFLICT");
    }

    @Test
    void recordConflictMustBeReadBackAndCompared() {
        FinanceRecordBatch batch = batch("digest-a");
        FinanceMetricRecord requested = record("record-a", "raw-a");
        FinanceMetricRecord existing = record("record-other", "raw-a");
        when(batchMapper.insertIgnore(batch)).thenReturn(1);
        when(recordMapper.insertIgnore(requested)).thenReturn(0);
        when(recordMapper.findByIdentity("run-1", "todo-1", "execute-1", 0, "raw-a"))
                .thenReturn(existing);

        assertThatThrownBy(() -> persister.persist(batch, List.of(requested)))
                .isInstanceOf(FinanceRecordProcessingException.class)
                .extracting("code")
                .isEqualTo("FINANCE_RECORD_IDENTITY_CONFLICT");
    }

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
