package world.willfrog.agentlangchain.facade;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.entity.AgentRunEvent;
import world.willfrog.agent.platform.event.AgentRunFinalizationService;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.service.AgentArtifactService;
import world.willfrog.agent.platform.service.AgentCreditService;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.platform.service.AgentMessageService;
import world.willfrog.agent.platform.service.AgentModelCatalogService;
import world.willfrog.agent.platform.service.AgentRunObservabilityService;
import world.willfrog.agent.platform.service.AgentRunCostService;
import world.willfrog.agent.platform.service.AgentRunCreditQueryService;
import world.willfrog.agent.platform.service.AgentRunCreditSettlementService;
import world.willfrog.agent.platform.service.AgentRunStateStore;
import world.willfrog.agent.platform.service.SnapshotPartService;
import world.willfrog.agentlangchain.tools.LangchainToolCatalogService;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisObservabilityContractFixtures;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisObservabilityQuery;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisObservabilityReadMode;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisObservabilitySnapshot;
import world.willfrog.alphafrogmicro.agent.idl.AgentArtifactMessage;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentDiagnosticReadCapabilitiesRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunResultRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunStatusRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentMessagesRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentRunEventsRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentRunsRequest;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LangchainRunReadServiceTest {

    private final AgentRunMapper runMapper = mock(AgentRunMapper.class);
    private final AgentRunEventService eventService = mock(AgentRunEventService.class);
    private final AgentRunStateStore stateStore = mock(AgentRunStateStore.class);
    private final AgentRunObservabilityService observabilityService = mock(AgentRunObservabilityService.class);
    private final AgentCreditService creditService = mock(AgentCreditService.class);
    private final AgentRunCostService runCostService = mock(AgentRunCostService.class);
    private final AgentRunCreditQueryService runCreditQueryService = mock(AgentRunCreditQueryService.class);
    private final AgentRunCreditSettlementService creditSettlementService = mock(AgentRunCreditSettlementService.class);
    private final AgentModelCatalogService modelCatalogService = mock(AgentModelCatalogService.class);
    private final AgentMessageService messageService = mock(AgentMessageService.class);
    private final SnapshotPartService snapshotPartService = mock(SnapshotPartService.class);
    private final LangchainToolCatalogService toolCatalogService = mock(LangchainToolCatalogService.class);
    private final AgentArtifactService artifactService = mock(AgentArtifactService.class);
    private final DataAnalysisObservabilityQuery dataAnalysisQuery = mock(DataAnalysisObservabilityQuery.class);
    private final DataAnalysisReadResponseSerializer dataAnalysisSerializer = new DataAnalysisReadResponseSerializer(new ObjectMapper());
    private final AgentRunFinalizationService finalizationService = mock(AgentRunFinalizationService.class);

    private final LangchainRunReadService service = new LangchainRunReadService(
            runMapper,
            eventService,
            stateStore,
            observabilityService,
            creditService,
            runCostService,
            runCreditQueryService,
            creditSettlementService,
            modelCatalogService,
            messageService,
            snapshotPartService,
            toolCatalogService,
            artifactService,
            new ObjectMapper(),
            dataAnalysisQuery,
            dataAnalysisSerializer,
            finalizationService);

    @BeforeEach
    void stubDataAnalysisQuery() {
        when(dataAnalysisQuery.findByRunId(anyString(), any())).thenReturn(Optional.empty());
    }

    @Test
    void getRunAllowsExistingUserScopedRun() {
        AgentRun run = run("{\"run_provider\":\"legacy\"}");
        when(runMapper.findByIdAndUser("r1", "u1")).thenReturn(run);

        var message = service.getRun(GetAgentRunRequest.newBuilder()
                .setUserId("u1")
                .setId("r1")
                .build());

        assertEquals("r1", message.getId());
    }

    @Test
    void diagnosticReadCapabilitiesAreAdminOnlyAndNeverTouchRunStorage() {
        var capabilities = service.getDiagnosticReadCapabilities(
                GetAgentDiagnosticReadCapabilitiesRequest.newBuilder()
                        .setUserId("admin-user")
                        .setIsAdmin(true)
                        .build());

        assertTrue(capabilities.getAdminCrossUserRead());
        assertTrue(capabilities.getNoTouchRunLifecycle());
        assertTrue(capabilities.getArtifactSkipLazyRegistration());
        assertThrows(IllegalArgumentException.class, () -> service.getDiagnosticReadCapabilities(
                GetAgentDiagnosticReadCapabilitiesRequest.newBuilder()
                        .setUserId("ordinary-user")
                        .setIsAdmin(false)
                        .build()));
        verifyNoInteractions(runMapper, eventService, stateStore, artifactService);
    }

    @Test
    void adminReadEndpointsUseRunIdLookupAcrossUserOwnership() {
        AgentRun run = run("{\"run_provider\":\"langchain\"}");
        when(runMapper.findById("r1")).thenReturn(run);
        when(eventService.shouldMarkExpired(run)).thenReturn(true);
        when(eventService.listByRunIdAfterSeqFromDatabase("r1", 0, 201)).thenReturn(List.of());
        when(stateStore.loadPlan("r1")).thenReturn(Optional.empty());
        when(eventService.listByRunIdFromDatabase("r1")).thenReturn(List.of());
        when(observabilityService.loadObservabilitySummaryJson(eq("r1"), any())).thenReturn("{}");
        when(messageService.listMessagesWithPagination("r1", 50, 0)).thenReturn(List.of());

        service.getRun(GetAgentRunRequest.newBuilder()
                .setUserId("admin-user").setId("r1").setIsAdmin(true).build());
        service.listEvents(ListAgentRunEventsRequest.newBuilder()
                .setUserId("admin-user").setId("r1").setIsAdmin(true).build());
        service.getStatus(GetAgentRunStatusRequest.newBuilder()
                .setUserId("admin-user").setId("r1").setIsAdmin(true).build());
        service.listMessages(ListAgentMessagesRequest.newBuilder()
                .setUserId("admin-user").setRunId("r1").setIncludeInitial(true).setIsAdmin(true).build());

        verify(runMapper, times(4)).findById("r1");
        verify(runMapper, never()).findByIdAndUser("r1", "admin-user");
        verify(eventService, never()).shouldMarkExpired(run);
        verify(runMapper, never()).updateStatus(anyString(), anyString(), any());
        verify(eventService, never()).append(eq("r1"), eq("u1"), eq("RUN_EXPIRED"), anyMap());
        verify(eventService).listByRunIdAfterSeqFromDatabase("r1", 0, 201);
        verify(eventService).findLatestByRunIdFromDatabase("r1");
        verify(eventService).listByRunIdFromDatabase("r1");
        verify(eventService).findMaxSeqFromDatabase("r1");
        verify(eventService, never()).listByRunIdAfterSeq(anyString(), anyInt(), anyInt());
        verify(eventService, never()).findLatestByRunId(anyString());
        verify(eventService, never()).listByRunId(anyString());
        verify(eventService, never()).findMaxSeq(anyString());
    }

    @Test
    void adminLatestEventsUseDatabaseWithoutTouchingRedisProjection() {
        AgentRun run = run("{\"run_provider\":\"langchain\"}");
        when(runMapper.findById("r1")).thenReturn(run);
        when(eventService.listLatestByRunIdFromDatabase("r1", 10))
                .thenReturn(List.of(event(9, "TOOL_CALL_STARTED"), event(10, "TOOL_CALL_FINISHED")));

        var response = service.listEvents(ListAgentRunEventsRequest.newBuilder()
                .setUserId("admin-user")
                .setId("r1")
                .setLimit(10)
                .setLatest(true)
                .setIsAdmin(true)
                .build());

        assertEquals(2, response.getItemsCount());
        assertEquals(10, response.getNextAfterSeq());
        verify(eventService).listLatestByRunIdFromDatabase("r1", 10);
        verify(eventService, never()).listLatestByRunId(anyString(), anyInt());
    }

    @Test
    void nonAdminReadEndpointsCannotBypassRunOwnership() {
        assertThrows(IllegalArgumentException.class, () -> service.getRun(GetAgentRunRequest.newBuilder()
                .setUserId("other-user").setId("r1").build()));
        assertThrows(IllegalArgumentException.class, () -> service.listEvents(ListAgentRunEventsRequest.newBuilder()
                .setUserId("other-user").setId("r1").build()));
        assertThrows(IllegalArgumentException.class, () -> service.getStatus(GetAgentRunStatusRequest.newBuilder()
                .setUserId("other-user").setId("r1").build()));
        assertThrows(IllegalArgumentException.class, () -> service.listMessages(ListAgentMessagesRequest.newBuilder()
                .setUserId("other-user").setRunId("r1").setIncludeInitial(true).build()));

        verify(runMapper, times(4)).findByIdAndUser("r1", "other-user");
        verify(runMapper, never()).findById("r1");
    }

    @Test
    void getRunPublishesExpiredOnlyAfterTerminalStateIsPersisted() {
        AgentRun active = run("{\"run_provider\":\"langchain\"}");
        active.setStatus(AgentRunStatus.CANCELED);
        AgentRun expired = run("{\"run_provider\":\"langchain\"}");
        expired.setStatus(AgentRunStatus.EXPIRED);
        when(runMapper.findByIdAndUser("r1", "u1")).thenReturn(active, expired);
        when(eventService.shouldMarkExpired(active)).thenReturn(true);
        when(runMapper.updateStatus("r1", "u1", AgentRunStatus.EXPIRED)).thenReturn(1);

        var message = service.getRun(GetAgentRunRequest.newBuilder()
                .setUserId("u1")
                .setId("r1")
                .build());

        assertEquals("EXPIRED", message.getStatus());
        InOrder order = inOrder(runMapper, eventService, stateStore, finalizationService);
        order.verify(runMapper).updateStatus("r1", "u1", AgentRunStatus.EXPIRED);
        order.verify(eventService).append(eq("r1"), eq("u1"), eq("RUN_EXPIRED"), anyMap());
        order.verify(stateStore).markRunStatus("r1", "EXPIRED");
        order.verify(finalizationService).publishFinalizedEvent("r1", "u1", "EXPIRED");
    }

    @Test
    void getRunPublisherFailureDoesNotRollbackPersistedExpiredState() {
        AgentRun active = run("{\"run_provider\":\"langchain\"}");
        active.setStatus(AgentRunStatus.CANCELED);
        AgentRun expired = run("{\"run_provider\":\"langchain\"}");
        expired.setStatus(AgentRunStatus.EXPIRED);
        when(runMapper.findByIdAndUser("r1", "u1")).thenReturn(active, expired);
        when(eventService.shouldMarkExpired(active)).thenReturn(true);
        when(runMapper.updateStatus("r1", "u1", AgentRunStatus.EXPIRED)).thenReturn(1);
        doThrow(new RuntimeException("listener unavailable"))
                .when(finalizationService).publishFinalizedEvent("r1", "u1", "EXPIRED");

        var message = service.getRun(GetAgentRunRequest.newBuilder()
                .setUserId("u1")
                .setId("r1")
                .build());

        assertEquals("EXPIRED", message.getStatus());
        verify(runMapper).updateStatus("r1", "u1", AgentRunStatus.EXPIRED);
        verify(finalizationService).publishFinalizedEvent("r1", "u1", "EXPIRED");
    }

    @Test
    void getRunExpiredPersistenceMismatchDoesNotPublish() {
        AgentRun active = run("{\"run_provider\":\"langchain\"}");
        active.setStatus(AgentRunStatus.CANCELED);
        when(runMapper.findByIdAndUser("r1", "u1")).thenReturn(active);
        when(eventService.shouldMarkExpired(active)).thenReturn(true);
        when(runMapper.updateStatus("r1", "u1", AgentRunStatus.EXPIRED)).thenReturn(0);

        var message = service.getRun(GetAgentRunRequest.newBuilder()
                .setUserId("u1")
                .setId("r1")
                .build());

        assertEquals("CANCELED", message.getStatus());
        verify(eventService, never()).append(anyString(), anyString(), anyString(), anyMap());
        verify(finalizationService, never()).publishFinalizedEvent(anyString(), anyString(), anyString());
    }

    @Test
    void getRunAlreadyExpiredDoesNotPublishAgain() {
        AgentRun expired = run("{\"run_provider\":\"langchain\"}");
        expired.setStatus(AgentRunStatus.EXPIRED);
        when(runMapper.findByIdAndUser("r1", "u1")).thenReturn(expired);
        when(eventService.shouldMarkExpired(expired)).thenReturn(false);

        var message = service.getRun(GetAgentRunRequest.newBuilder()
                .setUserId("u1")
                .setId("r1")
                .build());

        assertEquals("EXPIRED", message.getStatus());
        verify(finalizationService, never()).publishFinalizedEvent(anyString(), anyString(), anyString());
    }

    @Test
    void listEventsPaginatesLikeLegacy() {
        AgentRun run = run("{\"run_provider\":\"langchain\"}");
        when(runMapper.findByIdAndUser("r1", "u1")).thenReturn(run);
        AgentRunEvent event1 = event(3, "PLAN_READY");
        AgentRunEvent event2 = event(4, "TODO_STARTED");
        when(eventService.listByRunIdAfterSeq("r1", 2, 2)).thenReturn(List.of(event1, event2));

        var response = service.listEvents(ListAgentRunEventsRequest.newBuilder()
                .setUserId("u1")
                .setId("r1")
                .setAfterSeq(2)
                .setLimit(1)
                .build());

        assertEquals(1, response.getItemsCount());
        assertEquals("PLAN_READY", response.getItems(0).getEventType());
        assertEquals(3, response.getNextAfterSeq());
        assertTrue(response.getHasMore());
    }

    @Test
    void listEventsLatestReturnsRecentEventsWithoutHasMore() {
        AgentRun run = run("{\"run_provider\":\"langchain\"}");
        when(runMapper.findByIdAndUser("r1", "u1")).thenReturn(run);
        AgentRunEvent event9 = event(9, "TOOL_CALL_STARTED");
        AgentRunEvent event10 = event(10, "TOOL_CALL_FINISHED");
        when(eventService.listLatestByRunId("r1", 10)).thenReturn(List.of(event9, event10));

        var response = service.listEvents(ListAgentRunEventsRequest.newBuilder()
                .setUserId("u1")
                .setId("r1")
                .setLimit(10)
                .setLatest(true)
                .build());

        assertEquals(2, response.getItemsCount());
        assertEquals(9, response.getItems(0).getSeq());
        assertEquals(10, response.getNextAfterSeq());
        assertFalse(response.getHasMore());
    }

    @Test
    void getResultExtractsAnswerAndCredits() {
        AgentRun run = run("{\"run_provider\":\"langchain\"}");
        run.setSnapshotJson("{\"answer_markdown\":\"done\"}");
        when(runMapper.findByIdAndUser("r1", "u1")).thenReturn(run);
        when(observabilityService.loadObservabilityJson("r1", run.getSnapshotJson())).thenReturn("{\"summary\":{}}");
        when(eventService.listByRunId("r1")).thenReturn(List.of());
        when(creditService.calculateRunTotalCredits(eq(run), anyList(), eq("{\"summary\":{}}"))).thenReturn(7);

        var result = service.getResult(GetAgentRunResultRequest.newBuilder()
                .setUserId("u1")
                .setId("r1")
                .build());

        assertEquals("done", result.getAnswerMarkdown());
        assertEquals(7, result.getTotalCreditsConsumed());
    }

    @Test
    void getStatusReturnsLightweightObservabilitySummaryOnly() {
        AgentRun run = run("{\"run_provider\":\"langchain\"}");
        run.setPlanJson("{\"items\":[]}");
        when(runMapper.findByIdAndUser("r1", "u1")).thenReturn(run);
        when(stateStore.loadPlan("r1")).thenReturn(java.util.Optional.empty());
        when(stateStore.buildProgressJson("r1", run.getPlanJson())).thenReturn("{\"progress\":true}");
        when(observabilityService.loadObservabilitySummaryJson("r1", run.getSnapshotJson())).thenReturn("{\"summary\":true}");
        when(observabilityService.isFullObservabilityAvailable("r1", run.getSnapshotJson())).thenReturn(true);
        when(eventService.listByRunId("r1")).thenReturn(List.of());
        when(eventService.findMaxSeq("r1")).thenReturn(4);

        var status = service.getStatus(GetAgentRunStatusRequest.newBuilder()
                .setUserId("u1")
                .setId("r1")
                .build());

        assertEquals("", status.getObservabilityJson());
        assertEquals("{\"summary\":true}", status.getObservabilitySummaryJson());
        assertTrue(status.getObservabilityFullAvailable());
        verify(observabilityService, never()).loadObservabilityJson(anyString(), any());
    }

    @Test
    void getRunCostProjectsAndPersistsFromFullObservability() {
        AgentRun run = run("{\"run_provider\":\"langchain\"}");
        when(runMapper.findByIdAndUser("r1", "u1")).thenReturn(run);
        when(observabilityService.loadObservabilityJson("r1", run.getSnapshotJson())).thenReturn("{\"diagnostics\":{}}");
        var cost = world.willfrog.alphafrogmicro.agent.idl.AgentRunCostMessage.newBuilder()
                .setId("r1")
                .setTotalCost(0.012)
                .setHasTotalCost(true)
                .setPersisted(true)
                .build();
        when(runCostService.buildAndPersist(run, "{\"diagnostics\":{}}")).thenReturn(cost);

        var response = service.getRunCost(world.willfrog.alphafrogmicro.agent.idl.GetAgentRunCostRequest.newBuilder()
                .setUserId("u1")
                .setId("r1")
                .build());

        assertEquals(0.012, response.getTotalCost());
        assertTrue(response.getPersisted());
        verify(runCostService).buildAndPersist(run, "{\"diagnostics\":{}}");
    }

    @Test
    void statusMergesDataAnalysisSummaryOnly() {
        AgentRun run = run("{\"run_provider\":\"legacy\"}");
        when(runMapper.findByIdAndUser("r1", "u1")).thenReturn(run);
        when(stateStore.loadPlan("r1")).thenReturn(Optional.empty());
        when(eventService.findLatestByRunId("r1")).thenReturn(event(1, "EXECUTION_STARTED"));
        when(observabilityService.loadObservabilitySummaryJson(eq("r1"), any()))
                .thenReturn("{\"existing\":true}");
        when(eventService.listByRunId("r1")).thenReturn(List.of());
        when(eventService.findMaxSeq("r1")).thenReturn(5);
        DataAnalysisObservabilitySnapshot snapshot = DataAnalysisObservabilityContractFixtures.canonicalV1();
        when(dataAnalysisQuery.findSummaryByRunId(
                "r1", DataAnalysisObservabilityReadMode.TERMINAL_DB_ONLY))
                .thenReturn(Optional.of(snapshot.summary()));

        var status = service.getStatus(GetAgentRunStatusRequest.newBuilder()
                .setUserId("u1").setId("r1").build());

        String obsJson = status.getObservabilitySummaryJson();
        assertTrue(obsJson.contains("data_analysis_observability"));
        assertTrue(obsJson.contains("\"summary\""));
        assertTrue(!obsJson.contains("\"calls\""));
        assertTrue(obsJson.contains("\"existing\":true"));
        verify(dataAnalysisQuery).findSummaryByRunId(
                "r1", DataAnalysisObservabilityReadMode.TERMINAL_DB_ONLY);
        verify(dataAnalysisQuery, never()).findByRunId(anyString(), any());
    }

    @Test
    void resultMergesDataAnalysisFullSnapshot() {
        AgentRun run = run("{\"run_provider\":\"legacy\"}");
        when(runMapper.findByIdAndUser("r1", "u1")).thenReturn(run);
        when(observabilityService.loadObservabilityJson(eq("r1"), any()))
                .thenReturn("{\"existing\":true}");
        when(eventService.listByRunId("r1")).thenReturn(List.of());
        DataAnalysisObservabilitySnapshot snapshot = DataAnalysisObservabilityContractFixtures.canonicalV1();
        when(dataAnalysisQuery.findByRunId(
                "r1", DataAnalysisObservabilityReadMode.TERMINAL_DB_ONLY))
                .thenReturn(Optional.of(snapshot));

        var result = service.getResult(GetAgentRunResultRequest.newBuilder()
                .setUserId("u1").setId("r1").build());

        String obsJson = result.getObservabilityJson();
        assertTrue(obsJson.contains("data_analysis_observability"));
        assertTrue(obsJson.contains("\"summary\""));
        assertTrue(obsJson.contains("\"calls\""));
        assertTrue(obsJson.contains("\"call-a\""));
        assertTrue(obsJson.contains("\"existing\":true"));
    }

    @Test
    void dataAnalysisQueryEmptyPreservesExistingJson() {
        AgentRun run = run("{\"run_provider\":\"legacy\"}");
        when(runMapper.findByIdAndUser("r1", "u1")).thenReturn(run);
        when(stateStore.loadPlan("r1")).thenReturn(Optional.empty());
        when(eventService.findLatestByRunId("r1")).thenReturn(event(1, "EXECUTION_STARTED"));
        when(observabilityService.loadObservabilitySummaryJson(eq("r1"), any()))
                .thenReturn("{\"existing\":true}");
        when(eventService.listByRunId("r1")).thenReturn(List.of());
        when(eventService.findMaxSeq("r1")).thenReturn(5);

        var status = service.getStatus(GetAgentRunStatusRequest.newBuilder()
                .setUserId("u1").setId("r1").build());

        String obsJson = status.getObservabilitySummaryJson();
        assertTrue(obsJson.contains("\"existing\":true"));
        assertTrue(!obsJson.contains("data_analysis_observability"));
    }

    @Test
    void queryExceptionPreservesExistingJson() {
        AgentRun run = run("{\"run_provider\":\"legacy\"}");
        when(runMapper.findByIdAndUser("r1", "u1")).thenReturn(run);
        when(stateStore.loadPlan("r1")).thenReturn(Optional.empty());
        when(eventService.findLatestByRunId("r1")).thenReturn(event(1, "EXECUTION_STARTED"));
        when(observabilityService.loadObservabilitySummaryJson(eq("r1"), any()))
                .thenReturn("{\"existing\":true}");
        when(eventService.listByRunId("r1")).thenReturn(List.of());
        when(eventService.findMaxSeq("r1")).thenReturn(5);
        when(dataAnalysisQuery.findSummaryByRunId(
                "r1", DataAnalysisObservabilityReadMode.TERMINAL_DB_ONLY))
                .thenThrow(new RuntimeException("模拟查询失败"));

        var status = service.getStatus(GetAgentRunStatusRequest.newBuilder()
                .setUserId("u1").setId("r1").build());

        String obsJson = status.getObservabilitySummaryJson();
        assertEquals("{\"existing\":true}", obsJson);
    }

    @Test
    void invalidExistingJsonPreservedAsIs() {
        AgentRun run = run("{\"run_provider\":\"legacy\"}");
        when(runMapper.findByIdAndUser("r1", "u1")).thenReturn(run);
        when(stateStore.loadPlan("r1")).thenReturn(Optional.empty());
        when(eventService.findLatestByRunId("r1")).thenReturn(event(1, "EXECUTION_STARTED"));
        // 非法 JSON（非对象）
        when(observabilityService.loadObservabilitySummaryJson(eq("r1"), any()))
                .thenReturn("\"not-an-object\"");
        when(eventService.listByRunId("r1")).thenReturn(List.of());
        when(eventService.findMaxSeq("r1")).thenReturn(5);
        DataAnalysisObservabilitySnapshot snapshot = DataAnalysisObservabilityContractFixtures.canonicalV1();
        when(dataAnalysisQuery.findSummaryByRunId(
                "r1", DataAnalysisObservabilityReadMode.TERMINAL_DB_ONLY))
                .thenReturn(Optional.of(snapshot.summary()));

        var status = service.getStatus(GetAgentRunStatusRequest.newBuilder()
                .setUserId("u1").setId("r1").build());

        String obsJson = status.getObservabilitySummaryJson();
        assertEquals("\"not-an-object\"", obsJson);
    }

    @Test
    void emptyObjectJsonWithWhitespaceMergesCorrectly() {
        AgentRun run = run("{\"run_provider\":\"legacy\"}");
        when(runMapper.findByIdAndUser("r1", "u1")).thenReturn(run);
        when(stateStore.loadPlan("r1")).thenReturn(Optional.empty());
        when(eventService.findLatestByRunId("r1")).thenReturn(event(1, "EXECUTION_STARTED"));
        // 合法空对象带空白，必须以 isObject() 接受
        when(observabilityService.loadObservabilitySummaryJson(eq("r1"), any()))
                .thenReturn("{ }");
        when(eventService.listByRunId("r1")).thenReturn(List.of());
        when(eventService.findMaxSeq("r1")).thenReturn(5);
        var snapshot = DataAnalysisObservabilityContractFixtures.canonicalV1();
        when(dataAnalysisQuery.findSummaryByRunId(
                "r1", DataAnalysisObservabilityReadMode.TERMINAL_DB_ONLY))
                .thenReturn(Optional.of(snapshot.summary()));

        var status = service.getStatus(GetAgentRunStatusRequest.newBuilder()
                .setUserId("u1").setId("r1").build());

        String obsJson = status.getObservabilitySummaryJson();
        assertTrue(obsJson.contains("data_analysis_observability"));
    }

    @Test
    void runningStatusUsesCacheFirstSummaryModeAndNeverLoadsFullSnapshot() {
        AgentRun run = run("{\"run_provider\":\"legacy\"}");
        run.setStatus(AgentRunStatus.EXECUTING);
        when(runMapper.findByIdAndUser("r1", "u1")).thenReturn(run);
        when(stateStore.loadPlan("r1")).thenReturn(Optional.empty());
        when(observabilityService.loadObservabilitySummaryJson(eq("r1"), any())).thenReturn("{}");
        when(eventService.listByRunId("r1")).thenReturn(List.of());
        DataAnalysisObservabilitySnapshot snapshot = DataAnalysisObservabilityContractFixtures.canonicalV1();
        when(dataAnalysisQuery.findSummaryByRunId(
                "r1", DataAnalysisObservabilityReadMode.RUNNING_CACHE_FIRST))
                .thenReturn(Optional.of(snapshot.summary()));

        service.getStatus(GetAgentRunStatusRequest.newBuilder()
                .setUserId("u1").setId("r1").build());

        verify(dataAnalysisQuery).findSummaryByRunId(
                "r1", DataAnalysisObservabilityReadMode.RUNNING_CACHE_FIRST);
        verify(dataAnalysisQuery, never()).findByRunId(anyString(), any());
    }

    @Test
    void adminRunningStatusUsesNoTouchDataAnalysisMode() {
        AgentRun run = run("{\"run_provider\":\"legacy\"}");
        run.setStatus(AgentRunStatus.EXECUTING);
        when(runMapper.findById("r1")).thenReturn(run);
        when(stateStore.loadPlan("r1")).thenReturn(Optional.empty());
        when(observabilityService.loadObservabilitySummaryJson(eq("r1"), any())).thenReturn("{}");
        when(eventService.listByRunIdFromDatabase("r1")).thenReturn(List.of());
        when(dataAnalysisQuery.findSummaryByRunId(
                "r1", DataAnalysisObservabilityReadMode.DIAGNOSTIC_DB_ONLY))
                .thenReturn(Optional.empty());

        service.getStatus(GetAgentRunStatusRequest.newBuilder()
                .setUserId("admin-user").setId("r1").setIsAdmin(true).build());

        verify(dataAnalysisQuery).findSummaryByRunId(
                "r1", DataAnalysisObservabilityReadMode.DIAGNOSTIC_DB_ONLY);
        verify(dataAnalysisQuery, never()).findByRunId(anyString(), any());
        verify(eventService).findLatestByRunIdFromDatabase("r1");
        verify(eventService).listByRunIdFromDatabase("r1");
        verify(eventService).findMaxSeqFromDatabase("r1");
        verify(eventService, never()).findLatestByRunId(anyString());
        verify(eventService, never()).listByRunId(anyString());
        verify(eventService, never()).findMaxSeq(anyString());
    }

    @Test
    void adminRunningResultUsesNoTouchDataAnalysisMode() {
        AgentRun run = run("{\"run_provider\":\"legacy\"}");
        run.setStatus(AgentRunStatus.EXECUTING);
        when(runMapper.findById("r1")).thenReturn(run);
        when(observabilityService.loadObservabilityJson(eq("r1"), any())).thenReturn("{}");
        when(eventService.listByRunIdFromDatabase("r1")).thenReturn(List.of());
        when(dataAnalysisQuery.findByRunId(
                "r1", DataAnalysisObservabilityReadMode.DIAGNOSTIC_DB_ONLY))
                .thenReturn(Optional.empty());

        service.getResult(GetAgentRunResultRequest.newBuilder()
                .setUserId("admin-user").setId("r1").setIsAdmin(true).build());

        verify(dataAnalysisQuery).findByRunId(
                "r1", DataAnalysisObservabilityReadMode.DIAGNOSTIC_DB_ONLY);
        verify(eventService).listByRunIdFromDatabase("r1");
        verify(eventService, never()).listByRunId(anyString());
    }

    private AgentRun run(String ext) {
        AgentRun run = new AgentRun();
        run.setId("r1");
        run.setUserId("u1");
        run.setStatus(AgentRunStatus.COMPLETED);
        run.setExt(ext);
        run.setStartedAt(OffsetDateTime.now());
        return run;
    }

    private AgentRunEvent event(int seq, String type) {
        AgentRunEvent event = new AgentRunEvent();
        event.setId((long) seq);
        event.setRunId("r1");
        event.setSeq(seq);
        event.setEventType(type);
        event.setPayloadJson("{}");
        event.setCreatedAt(OffsetDateTime.now());
        return event;
    }

    @Test
    void listRuns_shouldSkipArtifactsWhenGateOff() {
        // 260814 scheduler-03 review fix：generate_artifacts 未开启（ext 缺失）
        // 时 hasArtifacts 恒为 false，且绝不调用 artifactService（不触发惰性注册）。
        AgentRun gated = run(null);
        when(runMapper.listByUser(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(gated));
        when(runMapper.countByUser(any(), any(), any())).thenReturn(1);

        var response = service.listRuns(ListAgentRunsRequest.newBuilder()
                .setUserId("u1")
                .build());

        assertEquals(1, response.getItemsCount());
        assertFalse(response.getItems(0).getHasArtifacts());
        verify(artifactService, never()).listArtifacts(any(), anyBoolean());
    }

    @Test
    void listRuns_shouldDeferToArtifactsWhenGateOn() {
        // 开关开启时：空产物 → false；非空产物 → true。
        AgentRun empty = run("{\"generate_artifacts\": true}");
        AgentRun withArtifact = run("{\"generate_artifacts\": true}");
        when(runMapper.listByUser(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(empty, withArtifact));
        when(runMapper.countByUser(any(), any(), any())).thenReturn(2);
        when(artifactService.listArtifacts(empty, false)).thenReturn(List.of());
        when(artifactService.listArtifacts(withArtifact, false)).thenReturn(List.of(
                AgentArtifactMessage.newBuilder().setArtifactId("a1").build()));

        var response = service.listRuns(ListAgentRunsRequest.newBuilder()
                .setUserId("u1")
                .build());

        assertEquals(2, response.getItemsCount());
        assertFalse(response.getItems(0).getHasArtifacts());
        assertTrue(response.getItems(1).getHasArtifacts());
    }
}
