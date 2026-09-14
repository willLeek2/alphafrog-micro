package world.willfrog.agentlangchain.workspace;

import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
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
        when(runMapper.listByStatusAndUpdatedAfterComposite(anyList(), any(OffsetDateTime.class), anyString(), anyInt()))
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

        verify(runMapper, never()).listByStatusAndUpdatedAfterComposite(anyList(), any(OffsetDateTime.class), anyString(), anyInt());
        verify(dumpScheduler, never()).enqueueDumpAsync(anyString(), anyBoolean());
    }

    @Test
    void scan_schedulerSubmissionFailureDoesNotAdvanceCursor() {
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        WorkspaceDumpScheduler dumpScheduler = mock(WorkspaceDumpScheduler.class);
        WorkspacePollingObserver observer = new WorkspacePollingObserver(runMapper, dumpScheduler);
        ReflectionTestUtils.setField(observer, "enabled", true);
        ReflectionTestUtils.setField(observer, "batchSize", 10);
        OffsetDateTime initialTime = OffsetDateTime.parse("2026-06-23T10:00:00Z");
        ReflectionTestUtils.setField(observer, "lastSeenTime", initialTime);
        ReflectionTestUtils.setField(observer, "lastSeenRunId", "");

        AgentRun failed = run("run-failed", AgentRunStatus.COMPLETED,
                initialTime.plusMinutes(1));
        AgentRun submitted = run("run-submitted", AgentRunStatus.COMPLETED,
                initialTime.plusMinutes(2));
        when(runMapper.listByStatusAndUpdatedAfterComposite(anyList(), any(OffsetDateTime.class), anyString(), anyInt()))
                .thenReturn(List.of(failed, submitted), List.of());
        doThrow(new IllegalStateException("executor rejected"))
                .when(dumpScheduler).enqueueDumpAsync("run-failed", false);

        observer.scan();
        observer.scan();

        // 两次 scan 应使用完全相同的游标参数（失败导致游标未推进）
        ArgumentCaptor<OffsetDateTime> cursorTimeCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<String> cursorRunIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(runMapper, times(2)).listByStatusAndUpdatedAfterComposite(anyList(), cursorTimeCaptor.capture(), cursorRunIdCaptor.capture(), anyInt());
        assertEquals(2, cursorTimeCaptor.getAllValues().size());
        assertEquals(cursorTimeCaptor.getAllValues().get(0), cursorTimeCaptor.getAllValues().get(1));
        assertEquals(cursorRunIdCaptor.getAllValues().get(0), cursorRunIdCaptor.getAllValues().get(1));
    }

    @Test
    void scan_advancesCursorToLastRowAfterSuccessfulBatch() {
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        WorkspaceDumpScheduler dumpScheduler = mock(WorkspaceDumpScheduler.class);
        WorkspacePollingObserver observer = new WorkspacePollingObserver(runMapper, dumpScheduler);
        ReflectionTestUtils.setField(observer, "enabled", true);
        ReflectionTestUtils.setField(observer, "batchSize", 10);
        OffsetDateTime cursorTime = OffsetDateTime.parse("2026-06-23T10:00:00Z");
        ReflectionTestUtils.setField(observer, "lastSeenTime", cursorTime);
        ReflectionTestUtils.setField(observer, "lastSeenRunId", "");

        AgentRun r1 = run("run-a", AgentRunStatus.COMPLETED, cursorTime.plusMinutes(1));
        AgentRun r2 = run("run-b", AgentRunStatus.COMPLETED, cursorTime.plusMinutes(2));
        when(runMapper.listByStatusAndUpdatedAfterComposite(anyList(), any(OffsetDateTime.class), anyString(), anyInt()))
                .thenReturn(List.of(r1, r2));

        observer.scan();

        OffsetDateTime newCursor = (OffsetDateTime) ReflectionTestUtils.getField(observer, "lastSeenTime");
        String newRunId = (String) ReflectionTestUtils.getField(observer, "lastSeenRunId");
        assertEquals(r2.getUpdatedAt(), newCursor);
        assertEquals("run-b", newRunId);
    }

    @Test
    void scan_sameSecondBatchContinuationUsesCompositeCursor() {
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        WorkspaceDumpScheduler dumpScheduler = mock(WorkspaceDumpScheduler.class);
        WorkspacePollingObserver observer = new WorkspacePollingObserver(runMapper, dumpScheduler);
        ReflectionTestUtils.setField(observer, "enabled", true);
        ReflectionTestUtils.setField(observer, "batchSize", 10);
        OffsetDateTime sameTime = OffsetDateTime.parse("2026-06-23T10:00:00Z");
        ReflectionTestUtils.setField(observer, "lastSeenTime", sameTime);
        ReflectionTestUtils.setField(observer, "lastSeenRunId", "");

        AgentRun r1 = run("run-01", AgentRunStatus.COMPLETED, sameTime);
        AgentRun r2 = run("run-02", AgentRunStatus.COMPLETED, sameTime);
        AgentRun r3 = run("run-03", AgentRunStatus.COMPLETED, sameTime);
        when(runMapper.listByStatusAndUpdatedAfterComposite(anyList(), any(OffsetDateTime.class), anyString(), anyInt()))
                .thenReturn(List.of(r1, r2), List.of(r3));

        observer.scan();
        observer.scan();

        ArgumentCaptor<OffsetDateTime> cursorTimeCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<String> cursorRunIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(runMapper, times(2)).listByStatusAndUpdatedAfterComposite(anyList(), cursorTimeCaptor.capture(), cursorRunIdCaptor.capture(), anyInt());

        // 第一次调用：用初始游标
        assertEquals(sameTime, cursorTimeCaptor.getAllValues().get(0));
        assertEquals("", cursorRunIdCaptor.getAllValues().get(0));
        // 第二次调用：用 r2 的游标（sameTime + run-02）
        assertEquals(sameTime, cursorTimeCaptor.getAllValues().get(1));
        assertEquals("run-02", cursorRunIdCaptor.getAllValues().get(1));
        verify(dumpScheduler).enqueueDumpAsync("run-03", false);
    }

    @Test
    void scan_batchOrderReversedSkipsIdsBeforeCursor() {
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        WorkspaceDumpScheduler dumpScheduler = mock(WorkspaceDumpScheduler.class);
        WorkspacePollingObserver observer = new WorkspacePollingObserver(runMapper, dumpScheduler);
        ReflectionTestUtils.setField(observer, "enabled", true);
        ReflectionTestUtils.setField(observer, "batchSize", 10);
        OffsetDateTime initTime = OffsetDateTime.parse("2026-06-23T10:00:00Z");
        ReflectionTestUtils.setField(observer, "lastSeenTime", initTime);
        ReflectionTestUtils.setField(observer, "lastSeenRunId", "run-zzz");

        when(runMapper.listByStatusAndUpdatedAfterComposite(anyList(), any(OffsetDateTime.class), anyString(), anyInt()))
                .thenReturn(List.of());

        observer.scan();

        OffsetDateTime after = (OffsetDateTime) ReflectionTestUtils.getField(observer, "lastSeenTime");
        String afterRunId = (String) ReflectionTestUtils.getField(observer, "lastSeenRunId");
        assertEquals(initTime, after);
        assertEquals("run-zzz", afterRunId);
        verify(dumpScheduler, never()).enqueueDumpAsync(anyString(), anyBoolean());
    }

    @Test
    void scan_resetCursorUsesInitialLookback() {
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        WorkspaceDumpScheduler dumpScheduler = mock(WorkspaceDumpScheduler.class);
        WorkspacePollingObserver observer = new WorkspacePollingObserver(runMapper, dumpScheduler);
        ReflectionTestUtils.setField(observer, "initialLookbackMinutes", 120);

        OffsetDateTime farFuture = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1);
        ReflectionTestUtils.setField(observer, "lastSeenTime", farFuture);
        ReflectionTestUtils.setField(observer, "lastSeenRunId", "some-id");

        observer.resetCursor();

        OffsetDateTime resetTime = (OffsetDateTime) ReflectionTestUtils.getField(observer, "lastSeenTime");
        String resetRunId = (String) ReflectionTestUtils.getField(observer, "lastSeenRunId");
        OffsetDateTime expectedFloor = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(120);
        long diffSeconds = Math.abs(resetTime.toEpochSecond() - expectedFloor.toEpochSecond());
        assert diffSeconds < 5 : "resetCursor should go back ~120min, got diff=" + diffSeconds + "s";
        assertEquals("", resetRunId);
    }

    @Test
    void scan_nullRunSkippedGracefully() {
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        WorkspaceDumpScheduler dumpScheduler = mock(WorkspaceDumpScheduler.class);
        WorkspacePollingObserver observer = new WorkspacePollingObserver(runMapper, dumpScheduler);
        ReflectionTestUtils.setField(observer, "enabled", true);
        ReflectionTestUtils.setField(observer, "batchSize", 10);
        OffsetDateTime initTime = OffsetDateTime.parse("2026-06-23T10:00:00Z");
        ReflectionTestUtils.setField(observer, "lastSeenTime", initTime);
        ReflectionTestUtils.setField(observer, "lastSeenRunId", "");

        AgentRun valid = run("run-valid", AgentRunStatus.COMPLETED);
        List<AgentRun> batch = new ArrayList<>();
        batch.add(null);
        batch.add(valid);
        batch.add(null); // 尾部 null
        when(runMapper.listByStatusAndUpdatedAfterComposite(anyList(), any(OffsetDateTime.class), anyString(), anyInt()))
                .thenReturn(batch);

        observer.scan();

        // valid run 应被正常提交
        verify(dumpScheduler, times(1)).enqueueDumpAsync("run-valid", false);
        // 游标应推进到最后一个有效条目（跳过尾部 null）
        OffsetDateTime newCursor = (OffsetDateTime) ReflectionTestUtils.getField(observer, "lastSeenTime");
        String newRunId = (String) ReflectionTestUtils.getField(observer, "lastSeenRunId");
        assertEquals(valid.getUpdatedAt(), newCursor);
        assertEquals("run-valid", newRunId);
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
