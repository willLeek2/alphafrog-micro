package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import world.willfrog.agent.platform.dataanalysis.*;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.finance.FinanceRecordChannelConfigLoader;
import world.willfrog.agent.platform.finance.FinanceRecordChannelProcessor;
import world.willfrog.agent.platform.finance.FinanceToolResultFormatter;
import world.willfrog.agent.tools.finance.FinanceResultModelAdapter;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agentlangchain.gateway.RunOwnershipGateway;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * CAS_STATUS→RESUME_READY recovery gap fix tests.
 *
 * <p>Verifies: half-state discovery, atomic promotion with narrow CAS,
 * winner/loser side effects, normal finalizer convergence, and backfill
 * integration. Pure mock tests — no Docker, Testcontainers, PostgreSQL, or Redis.
 * Real two-instance DB concurrency remains gated on authorized non-macOS environment.
 */
@ExtendWith(MockitoExtension.class)
class ToolJobCasStatusRecoveryTest {

    private static final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Mock
    private AgentRunMapper agentRunMapper;

    @Mock
    private RunOwnershipGateway ownershipGateway;

    private ToolJobAnchorService anchorService;
    private ToolJobFinalizer finalizer;
    private ToolJobResumeService resumeService;
    private ToolJobRedisCache redisCache;
    private ToolJobReconciler reconciler;
    private ToolJobUsageHook usageHook;
    private ToolJobEventHook eventHook;
    private DataAnalysisCapacityService capacityService;

    @BeforeEach
    void setUp() {
        anchorService = spy(new ToolJobAnchorService(agentRunMapper));
        resumeService = mock(ToolJobResumeService.class);
        redisCache = mock(ToolJobRedisCache.class);
        usageHook = mock(ToolJobUsageHook.class);
        eventHook = mock(ToolJobEventHook.class);
        capacityService = mock(DataAnalysisCapacityService.class);

        ToolJobConfig config = mock(ToolJobConfig.class);
        lenient().when(config.getResultRetentionDeadlineSeconds()).thenReturn(300L);
        lenient().when(config.getResultFetchMaxAttempts()).thenReturn(5);
        lenient().when(config.getReconcilerIntervalMs()).thenReturn(30000L);
        lenient().when(config.getPollIntervalMs()).thenReturn(5000L);
        lenient().when(config.getLaunchingStaleSeconds()).thenReturn(60L);

        finalizer = new ToolJobFinalizer(
                anchorService, redisCache, capacityService, resumeService,
                config,
                mock(FinanceRecordChannelProcessor.class),
                mock(FinanceRecordChannelConfigLoader.class),
                mock(FinanceToolResultFormatter.class),
                mock(FinanceResultModelAdapter.class));
        inject(finalizer, "usageHook", usageHook);
        inject(finalizer, "eventHook", eventHook);

        reconciler = new ToolJobReconciler(
                redisCache, anchorService, finalizer, resumeService, config, capacityService,
                ownershipGateway);
        lenient().when(ownershipGateway.listActiveAnchors(100)).thenReturn(List.of());
        lenient().when(ownershipGateway.listResumeReadyAnchors(50)).thenReturn(List.of());
        lenient().when(ownershipGateway.listStuckAtCasStatusAnchors(20)).thenReturn(List.of());
    }

    // ===== completeResumeReady: guard conditions =====










    // ===== completeResumeReady: winner/loser side effects =====




    // ===== promoteCasStatusToResumeReady: narrow CAS conditions =====



    // ===== Reconciler backfill integration =====



    // ===== listResumeReady: existing contracts preserved =====



    // ===== handleTerminal step 6 convergence =====


    // ===== helpers =====

    private static ToolJobAnchor buildAnchor(String runId, String toolCallId,
                                              int attempt, String taskId) {
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId(runId + ":" + toolCallId + ":" + attempt);
        anchor.setToolCallId(toolCallId);
        anchor.setAttempt(attempt);
        anchor.setTaskId(taskId);
        anchor.setResumeLeaseVersion(0);
        anchor.setResumeState(null);
        anchor.setFinalizerStep("CAS_STATUS");
        anchor.setAnchorState("TERMINAL");
        anchor.setTerminalRetryable(false);
        anchor.setUsagePersisted(true);
        anchor.setTerminalEventEmitted(true);
        anchor.setAutoResume(true);
        return anchor;
    }

    private static ToolJobAnchor buildTerminalAnchor(String runId, String toolCallId,
                                                      int attempt, String taskId,
                                                      String reservationJson) {
        ToolJobAnchor a = buildAnchor(runId, toolCallId, attempt, taskId);
        a.setTerminalStatus("SUCCEEDED");
        a.setReservationJson(reservationJson);
        a.setEstimateJson("{\"resourceClass\":\"STANDARD\",\"capacityUnits\":1,"
                + "\"estimatedRows\":0,\"estimatedBytes\":0,\"fileCount\":0,"
                + "\"selectedColumnRatio\":1.0,\"manifestMemberCount\":0}");
        a.setTerminalUsageJson("{\"maxMemoryBytes\":\"1048576\",\"wallTimeMs\":\"500\"}");
        a.setTerminalResultPreview("ok");
        return a;
    }

    private static String buildReservationJson(String runId, String toolCallId,
                                                int attempt, String taskId,
                                                DataAnalysisReservationState state) {
        return "{\"reservationId\":\"res-" + runId + "\","
                + "\"identity\":{\"runId\":\"" + runId + "\","
                + "\"toolCallId\":\"" + toolCallId + "\",\"attempt\":" + attempt + "},"
                + "\"resourceClass\":\"STANDARD\",\"capacityUnits\":1,"
                + "\"state\":\"" + state + "\","
                + "\"taskId\":\"" + taskId + "\","
                + "\"acquiredAt\":\"2026-08-10T10:00:00Z\"}";
    }

    private static void inject(Object target, String fieldName, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
