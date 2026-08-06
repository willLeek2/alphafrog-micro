package world.willfrog.agentlangchain.workspace;

import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkspacePollingObserverTest {

    @Test
    void scan_enabledDumpsTerminalRuns() throws Exception {
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        WorkspaceDumpScheduler dumpScheduler = mock(WorkspaceDumpScheduler.class);
        WorkspacePollingObserver observer = new WorkspacePollingObserver(runMapper, dumpScheduler);
        ReflectionTestUtils.setField(observer, "enabled", true);
        ReflectionTestUtils.setField(observer, "batchSize", 10);

        AgentRun completed = run("run-completed", AgentRunStatus.COMPLETED);
        AgentRun expired = run("run-expired", AgentRunStatus.EXPIRED);
        when(runMapper.listByStatusAndUpdatedAfter(anyList(), any(OffsetDateTime.class), anyInt()))
                .thenReturn(List.of(completed, expired));

        observer.scan();

        verify(dumpScheduler).enqueueDumpAsync("run-completed", false);
        verify(dumpScheduler).enqueueDumpAsync("run-expired", true);
    }

    @Test
    void scan_disabledDoesNotQuery() throws Exception {
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        WorkspaceDumpScheduler dumpScheduler = mock(WorkspaceDumpScheduler.class);
        WorkspacePollingObserver observer = new WorkspacePollingObserver(runMapper, dumpScheduler);
        ReflectionTestUtils.setField(observer, "enabled", false);

        observer.scan();

        verify(runMapper, never()).listByStatusAndUpdatedAfter(anyList(), any(OffsetDateTime.class), anyInt());
        verify(dumpScheduler, never()).enqueueDumpAsync(anyString(), anyBoolean());
    }

    @Test
    @SuppressWarnings("unchecked")
    void scan_schedulerSubmissionFailureDoesNotAdvanceWatermark() {
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        WorkspaceDumpScheduler dumpScheduler = mock(WorkspaceDumpScheduler.class);
        WorkspacePollingObserver observer = new WorkspacePollingObserver(runMapper, dumpScheduler);
        ReflectionTestUtils.setField(observer, "enabled", true);
        ReflectionTestUtils.setField(observer, "batchSize", 10);
        OffsetDateTime initialWatermark = OffsetDateTime.parse("2026-06-23T10:00:00Z");
        ((AtomicReference<OffsetDateTime>) ReflectionTestUtils.getField(observer, "lastSeenAt"))
                .set(initialWatermark);

        AgentRun failed = run("run-failed", AgentRunStatus.COMPLETED,
                initialWatermark.plusMinutes(1));
        AgentRun submitted = run("run-submitted", AgentRunStatus.COMPLETED,
                initialWatermark.plusMinutes(2));
        when(runMapper.listByStatusAndUpdatedAfter(anyList(), any(OffsetDateTime.class), anyInt()))
                .thenReturn(List.of(failed, submitted), List.of());
        doThrow(new IllegalStateException("executor rejected"))
                .when(dumpScheduler).enqueueDumpAsync("run-failed", false);

        observer.scan();
        observer.scan();

        ArgumentCaptor<OffsetDateTime> fromTimeCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(runMapper, times(2)).listByStatusAndUpdatedAfter(anyList(), fromTimeCaptor.capture(), anyInt());
        assertEquals(List.of(initialWatermark, initialWatermark), fromTimeCaptor.getAllValues());
        verify(dumpScheduler).enqueueDumpAsync("run-submitted", false);
    }

    private static AgentRun run(String id, AgentRunStatus status) {
        return run(id, status, OffsetDateTime.now());
    }

    private static AgentRun run(String id, AgentRunStatus status, OffsetDateTime updatedAt) {
        AgentRun run = new AgentRun();
        run.setId(id);
        run.setStatus(status);
        run.setUpdatedAt(updatedAt);
        return run;
    }
}
