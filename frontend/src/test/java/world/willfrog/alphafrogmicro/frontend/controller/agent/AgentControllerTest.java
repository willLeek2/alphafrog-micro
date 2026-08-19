package world.willfrog.alphafrogmicro.frontend.controller.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.GetMapping;
import world.willfrog.alphafrogmicro.agent.idl.AgentDubboService;
import world.willfrog.alphafrogmicro.agent.idl.AgentArtifactMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunEventMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunResultMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunStatusMessage;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentDiagnosticReadCapabilitiesRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentDiagnosticReadCapabilitiesResponse;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunResultRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunStatusRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentArtifactsRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentArtifactsResponse;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentMessagesRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentMessagesResponse;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentRunEventsRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentRunEventsResponse;
import world.willfrog.alphafrogmicro.agent.idl.SendAgentMessageRequest;
import world.willfrog.alphafrogmicro.agent.idl.SendAgentMessageResponse;
import world.willfrog.alphafrogmicro.common.dto.ResponseCode;
import world.willfrog.alphafrogmicro.common.pojo.user.User;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentCallDetailResponse;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentMessageSendRequest;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentRunCreateRequest;
import world.willfrog.alphafrogmicro.frontend.model.agent.TraceDetailResponse;
import world.willfrog.alphafrogmicro.frontend.model.agent.TimelineResponse;
import world.willfrog.alphafrogmicro.frontend.service.agent.AgentRawTraceDetailMapper;
import world.willfrog.alphafrogmicro.frontend.service.AuthService;
import world.willfrog.alphafrogmicro.frontend.service.agent.AgentCallDetailBlobReader;
import world.willfrog.alphafrogmicro.frontend.service.agent.AgentExternalObservabilityMapper;
import world.willfrog.alphafrogmicro.frontend.service.agent.AgentRunResultCacheService;
import world.willfrog.alphafrogmicro.frontend.service.agent.AgentAuthSupport;
import world.willfrog.alphafrogmicro.frontend.service.agent.AgentCreditGateway;
import world.willfrog.alphafrogmicro.frontend.service.agent.AgentTracePartsService;
import world.willfrog.alphafrogmicro.frontend.service.agent.AgentTimelineMergeService;

import java.math.BigDecimal;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentControllerTest {

    private AgentDubboService agentDubboService;
    private AuthService authService;
    private AgentRunResultCacheService runResultCacheService;
    private AgentCallDetailBlobReader callDetailBlobReader;
    private AgentCreditGateway creditGateway;
    private AgentController controller;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        agentDubboService = mock(AgentDubboService.class);
        authService = mock(AuthService.class);
        runResultCacheService = new AgentRunResultCacheService();
        ReflectionTestUtils.setField(runResultCacheService, "agentDubboService", agentDubboService);
        ReflectionTestUtils.setField(runResultCacheService, "cacheTtlSeconds", 30L);
        callDetailBlobReader = mock(AgentCallDetailBlobReader.class);
        creditGateway = mock(AgentCreditGateway.class);
        when(callDetailBlobReader.loadLlmCallDetail(any(), any())).thenReturn(Optional.empty());
        when(callDetailBlobReader.loadToolCallDetail(any(), any())).thenReturn(Optional.empty());
        when(callDetailBlobReader.loadLlmCallRawContent(any(), any())).thenReturn(Optional.empty());
        when(callDetailBlobReader.loadLlmCallRawMeta(any(), any())).thenReturn(Optional.empty());
        ObjectMapper objectMapper = new ObjectMapper();
        controller = new AgentController(new AgentAuthSupport(authService), creditGateway,
                objectMapper, runResultCacheService, callDetailBlobReader, new AgentTracePartsService(),
                new AgentTimelineMergeService(objectMapper));
        ReflectionTestUtils.setField(controller, "agentDubboServiceLangchain", agentDubboService);

        authentication = mock(Authentication.class);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("admin");

        User admin = new User();
        admin.setUserId(1127L);
        admin.setUserType(1127);
        when(authService.getUserByUsername("admin")).thenReturn(admin);
        when(agentDubboService.sendMessage(any(SendAgentMessageRequest.class))).thenReturn(
                SendAgentMessageResponse.newBuilder()
                        .setMessageId(1L)
                        .setSeq(2)
                        .setStatus("accepted")
                        .setRunStatus("RECEIVED")
                        .build()
        );
    }

    @Test
    void create_shouldReturnStreamUrl() {
        when(authService.isUserActive(any(User.class))).thenReturn(true);
        when(agentDubboService.createRun(any())).thenReturn(
                AgentRunMessage.newBuilder()
                        .setId("run-new")
                        .setStatus("RUN_RECEIVED")
                        .build()
        );

        var response = controller.create(authentication,
                new AgentRunCreateRequest("hello", null, null, null,
                        null, null, null, null, null, null, null, null));

        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        assertEquals("/api/agent/runs/run-new/stream", response.getData().streamUrl());
        verify(creditGateway, never()).hasPositiveRemainingCredit(any());
    }

    @Test
    void create_shouldPropagateExplicitArtifactRequestToAgentService() {
        when(authService.isUserActive(any(User.class))).thenReturn(true);
        when(agentDubboService.createRun(any())).thenReturn(
                AgentRunMessage.newBuilder().setId("run-artifact").setStatus("RUN_RECEIVED").build());

        controller.create(authentication,
                new AgentRunCreateRequest("hello", null, null, null,
                        null, null, null, null, null, null, true, null));

        ArgumentCaptor<world.willfrog.alphafrogmicro.agent.idl.CreateAgentRunRequest> captor =
                ArgumentCaptor.forClass(world.willfrog.alphafrogmicro.agent.idl.CreateAgentRunRequest.class);
        verify(agentDubboService).createRun(captor.capture());
        assertTrue(captor.getValue().getGenerateArtifacts());
    }

    @Test
    void create_disabledAdminIsRejectedBeforeCreditExemption() {
        when(authService.isUserActive(any(User.class))).thenReturn(false);

        var response = controller.create(authentication,
                new AgentRunCreateRequest("hello", null, null, null,
                        null, null, null, null, null, null, null, null));

        assertEquals(ResponseCode.FORBIDDEN.getCode(), response.getCode());
        verify(creditGateway, never()).hasPositiveRemainingCredit(any());
        verify(agentDubboService, never()).createRun(any());
    }

    @Test
    void create_nonAdminAdmissionUsesDubboRemainingCreditsWhenLocalCreditIsZero() {
        User nonAdmin = new User();
        nonAdmin.setUserId(99L);
        nonAdmin.setUserType(1);
        nonAdmin.setCredit(BigDecimal.ZERO);
        when(authService.getUserByUsername("admin")).thenReturn(nonAdmin);
        when(authService.isUserActive(nonAdmin)).thenReturn(true);
        when(creditGateway.hasPositiveRemainingCredit("99")).thenReturn(true);
        when(agentDubboService.createRun(any())).thenReturn(
                AgentRunMessage.newBuilder().setId("run-authoritative-credit").setStatus("RUN_RECEIVED").build());

        var response = controller.create(authentication,
                new AgentRunCreateRequest("hello", null, null, null,
                        null, null, null, null, null, null, null, null));

        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        verify(agentDubboService).createRun(any());
    }

    @Test
    void create_nonAdminAdmissionRejectsDubboZeroWhenLocalCreditIsPositive() {
        User nonAdmin = new User();
        nonAdmin.setUserId(99L);
        nonAdmin.setUserType(1);
        nonAdmin.setCredit(BigDecimal.valueOf(100));
        when(authService.getUserByUsername("admin")).thenReturn(nonAdmin);
        when(authService.isUserActive(nonAdmin)).thenReturn(true);
        when(creditGateway.hasPositiveRemainingCredit("99")).thenReturn(false);

        var response = controller.create(authentication,
                new AgentRunCreateRequest("hello", null, null, null,
                        null, null, null, null, null, null, null, null));

        assertEquals(ResponseCode.FORBIDDEN.getCode(), response.getCode());
        verify(agentDubboService, never()).createRun(any());
    }

    @Test
    void result_roleChangeUsesFreshAuthContextAndSeparateResultCacheEntry() {
        User currentAdmin = new User();
        currentAdmin.setUserId(99L);
        currentAdmin.setUserType(1127);
        User demotedUser = new User();
        demotedUser.setUserId(99L);
        demotedUser.setUserType(1);
        when(authService.getUserByUsername("admin")).thenReturn(currentAdmin, demotedUser);
        when(agentDubboService.getResult(any(GetAgentRunResultRequest.class))).thenReturn(
                AgentRunResultMessage.newBuilder().setId("run-1").setStatus("COMPLETED").build());

        controller.result(authentication, "run-1");
        controller.result(authentication, "run-1");

        ArgumentCaptor<GetAgentRunResultRequest> captor = ArgumentCaptor.forClass(GetAgentRunResultRequest.class);
        verify(agentDubboService, org.mockito.Mockito.times(2)).getResult(captor.capture());
        assertTrue(captor.getAllValues().get(0).getIsAdmin());
        assertFalse(captor.getAllValues().get(1).getIsAdmin());
    }

    @Test
    void adminReadRoutesPropagateTrustedAdminFlagToDubboRequests() {
        when(agentDubboService.getRun(any(GetAgentRunRequest.class))).thenReturn(
                AgentRunMessage.newBuilder().setId("run-1").setStatus("COMPLETED").build());
        when(agentDubboService.listEvents(any(ListAgentRunEventsRequest.class))).thenReturn(
                ListAgentRunEventsResponse.newBuilder().build());
        when(agentDubboService.getStatus(any(GetAgentRunStatusRequest.class))).thenReturn(
                AgentRunStatusMessage.newBuilder().setId("run-1").setStatus("COMPLETED").build());
        when(agentDubboService.getResult(any(GetAgentRunResultRequest.class))).thenReturn(
                AgentRunResultMessage.newBuilder().setId("run-1").build());
        when(agentDubboService.listMessages(any(ListAgentMessagesRequest.class))).thenReturn(
                ListAgentMessagesResponse.newBuilder().build());

        controller.get(authentication, "run-1");
        controller.events(authentication, "run-1", 0, 20);
        controller.timeline(authentication, "run-1", 0, 20);
        controller.status(authentication, "run-1");
        controller.listMessages(authentication, "run-1", 50, 0, true);

        ArgumentCaptor<GetAgentRunRequest> runRequest = ArgumentCaptor.forClass(GetAgentRunRequest.class);
        verify(agentDubboService).getRun(runRequest.capture());
        assertTrue(runRequest.getValue().getIsAdmin());

        ArgumentCaptor<ListAgentRunEventsRequest> eventsRequest =
                ArgumentCaptor.forClass(ListAgentRunEventsRequest.class);
        verify(agentDubboService, org.mockito.Mockito.times(3)).listEvents(eventsRequest.capture());
        assertTrue(eventsRequest.getAllValues().stream().allMatch(ListAgentRunEventsRequest::getIsAdmin));

        ArgumentCaptor<GetAgentRunStatusRequest> statusRequest =
                ArgumentCaptor.forClass(GetAgentRunStatusRequest.class);
        verify(agentDubboService).getStatus(statusRequest.capture());
        assertTrue(statusRequest.getValue().getIsAdmin());

        ArgumentCaptor<ListAgentMessagesRequest> messagesRequest =
                ArgumentCaptor.forClass(ListAgentMessagesRequest.class);
        verify(agentDubboService).listMessages(messagesRequest.capture());
        assertTrue(messagesRequest.getValue().getIsAdmin());
    }

    @Test
    void diagnosticReadCapabilitiesRequireAdminAndComeFromProvider() {
        when(agentDubboService.getDiagnosticReadCapabilities(
                any(GetAgentDiagnosticReadCapabilitiesRequest.class))).thenReturn(
                GetAgentDiagnosticReadCapabilitiesResponse.newBuilder()
                        .setAdminCrossUserRead(true)
                        .setNoTouchRunLifecycle(true)
                        .setArtifactSkipLazyRegistration(true)
                        .build());

        var response = new AgentObservabilityController(controller)
                .diagnosticReadCapabilities(authentication);

        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        assertEquals(Boolean.TRUE, response.getData().get("adminCrossUserRead"));
        assertEquals(Boolean.TRUE, response.getData().get("noTouchRunLifecycle"));
        assertEquals(Boolean.TRUE, response.getData().get("artifactSkipLazyRegistration"));
        ArgumentCaptor<GetAgentDiagnosticReadCapabilitiesRequest> request =
                ArgumentCaptor.forClass(GetAgentDiagnosticReadCapabilitiesRequest.class);
        verify(agentDubboService).getDiagnosticReadCapabilities(request.capture());
        assertEquals("1127", request.getValue().getUserId());
        assertTrue(request.getValue().getIsAdmin());

        setNonAdmin();
        var forbidden = new AgentObservabilityController(controller)
                .diagnosticReadCapabilities(authentication);
        assertEquals(ResponseCode.FORBIDDEN.getCode(), forbidden.getCode());
        verify(agentDubboService).getDiagnosticReadCapabilities(any());
    }

    @Test
    void nonAdminReadRoutesNeverEscalateDubboRequests() {
        setNonAdmin();
        when(agentDubboService.getRun(any(GetAgentRunRequest.class))).thenReturn(
                AgentRunMessage.newBuilder().setId("run-1").setStatus("COMPLETED").build());
        when(agentDubboService.listEvents(any(ListAgentRunEventsRequest.class))).thenReturn(
                ListAgentRunEventsResponse.newBuilder().build());
        when(agentDubboService.getStatus(any(GetAgentRunStatusRequest.class))).thenReturn(
                AgentRunStatusMessage.newBuilder().setId("run-1").setStatus("COMPLETED").build());
        when(agentDubboService.getResult(any(GetAgentRunResultRequest.class))).thenReturn(
                AgentRunResultMessage.newBuilder().setId("run-1").build());
        when(agentDubboService.listMessages(any(ListAgentMessagesRequest.class))).thenReturn(
                ListAgentMessagesResponse.newBuilder().build());

        controller.get(authentication, "run-1");
        controller.events(authentication, "run-1", 0, 20);
        controller.timeline(authentication, "run-1", 0, 20);
        controller.status(authentication, "run-1");
        controller.listMessages(authentication, "run-1", 50, 0, true);

        ArgumentCaptor<GetAgentRunRequest> runRequest = ArgumentCaptor.forClass(GetAgentRunRequest.class);
        verify(agentDubboService).getRun(runRequest.capture());
        assertFalse(runRequest.getValue().getIsAdmin());

        ArgumentCaptor<ListAgentRunEventsRequest> eventsRequest =
                ArgumentCaptor.forClass(ListAgentRunEventsRequest.class);
        verify(agentDubboService, org.mockito.Mockito.times(3)).listEvents(eventsRequest.capture());
        assertTrue(eventsRequest.getAllValues().stream().noneMatch(ListAgentRunEventsRequest::getIsAdmin));

        ArgumentCaptor<GetAgentRunStatusRequest> statusRequest =
                ArgumentCaptor.forClass(GetAgentRunStatusRequest.class);
        verify(agentDubboService).getStatus(statusRequest.capture());
        assertFalse(statusRequest.getValue().getIsAdmin());

        ArgumentCaptor<ListAgentMessagesRequest> messagesRequest =
                ArgumentCaptor.forClass(ListAgentMessagesRequest.class);
        verify(agentDubboService).listMessages(messagesRequest.capture());
        assertFalse(messagesRequest.getValue().getIsAdmin());
    }

    @Test
    void sendMessage_shouldAllowContextOverrideOnlyWhenDebugModeTrue() {
        controller.sendMessage(authentication, "run-1",
                new AgentMessageSendRequest("hello", "{\"x\":1}", true, false));

        ArgumentCaptor<SendAgentMessageRequest> captor = ArgumentCaptor.forClass(SendAgentMessageRequest.class);
        verify(agentDubboService).sendMessage(captor.capture());
        assertEquals("{\"x\":1}", captor.getValue().getContextOverride());
        assertEquals(false, captor.getValue().getStream());
    }

    @Test
    void sendMessage_shouldNotTreatStreamAsDebugMode() {
        var response = controller.sendMessage(authentication, "run-1",
                new AgentMessageSendRequest("hello", "{\"x\":1}", false, true));

        ArgumentCaptor<SendAgentMessageRequest> captor = ArgumentCaptor.forClass(SendAgentMessageRequest.class);
        verify(agentDubboService).sendMessage(captor.capture());
        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        assertEquals("", captor.getValue().getContextOverride());
        assertEquals(true, captor.getValue().getStream());
    }

    @Test
    void observabilityFull_shouldRejectOversizedJsonBeforeParsing() {
        String oversized = "{\"padding\":\"" + "x".repeat(5 * 1024 * 1024 + 1) + "\"}";
        when(agentDubboService.getResult(any(GetAgentRunResultRequest.class))).thenReturn(
                AgentRunResultMessage.newBuilder().setObservabilityJson(oversized).build()
        );

        var response = controller.observabilityFull(authentication, "run-1");

        assertEquals(ResponseCode.BUSINESS_ERROR.getCode(), response.getCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void observabilityFull_shouldIncludeDatasetArtifactIndex() {
        when(agentDubboService.getResult(any(GetAgentRunResultRequest.class))).thenReturn(
                AgentRunResultMessage.newBuilder().setObservabilityJson("{\"diagnostics\":{}}").build()
        );
        when(agentDubboService.listArtifacts(any())).thenReturn(
                ListAgentArtifactsResponse.newBuilder()
                        .addItems(AgentArtifactMessage.newBuilder()
                                .setArtifactId("artifact-1")
                                .setType("dataset_json")
                                .setName("ds1.json")
                                .setContentType("application/json")
                                .setUrl("/api/agent/runs/run-1/artifacts/artifact-1/download")
                                .setMetaJson("{\"dataset_id\":\"ds1\",\"file_name\":\"ds1.json\"}")
                                .build())
                        .build()
        );

        var response = controller.observabilityFull(authentication, "run-1");

        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        Map<String, Object> data = (Map<String, Object>) response.getData();
        assertTrue(data.containsKey("artifacts"));
        assertTrue(data.containsKey("dataset_artifacts"));
        assertTrue(data.toString().contains("ds1.json"));
        assertTrue(data.toString().contains("/api/agent/runs/run-1/artifacts/artifact-1/parts"));

        ArgumentCaptor<GetAgentRunResultRequest> resultRequest =
                ArgumentCaptor.forClass(GetAgentRunResultRequest.class);
        verify(agentDubboService).getResult(resultRequest.capture());
        assertTrue(resultRequest.getValue().getIsAdmin());
        ArgumentCaptor<ListAgentArtifactsRequest> artifactsRequest =
                ArgumentCaptor.forClass(ListAgentArtifactsRequest.class);
        verify(agentDubboService).listArtifacts(artifactsRequest.capture());
        assertTrue(artifactsRequest.getValue().getIsAdmin());
        assertTrue(artifactsRequest.getValue().getSkipLazyRegistration());
        assertEquals("1127", artifactsRequest.getValue().getUserId());
        assertEquals("run-1", artifactsRequest.getValue().getId());
    }

    @Test
    void artifactDiagnosticRouteCanSkipLazyRegistration() {
        when(agentDubboService.listArtifacts(any())).thenReturn(
                ListAgentArtifactsResponse.newBuilder().build());

        var response = new AgentArtifactController(controller)
                .artifacts(authentication, "run-1", true);

        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        ArgumentCaptor<ListAgentArtifactsRequest> request =
                ArgumentCaptor.forClass(ListAgentArtifactsRequest.class);
        verify(agentDubboService).listArtifacts(request.capture());
        assertTrue(request.getValue().getIsAdmin());
        assertTrue(request.getValue().getSkipLazyRegistration());
    }

    @Test
    void timeline_shouldMergeEventItemsAndTraceLiteItems() {
        when(agentDubboService.listEvents(any(ListAgentRunEventsRequest.class))).thenReturn(
                ListAgentRunEventsResponse.newBuilder()
                        .addItems(AgentRunEventMessage.newBuilder()
                                .setSeq(1)
                                .setRunId("run-1")
                                .setEventType("TODO_STARTED")
                                .setPayloadJson("{\"todo_id\":\"todo_1\"}")
                                .setCreatedAt("2026-05-07T10:00:00Z")
                                .build())
                        .setNextAfterSeq(1)
                        .setHasMore(false)
                        .build()
        );
        String observability = """
                {"summary":{},"diagnostics":{"llmTraces":[
                  {"traceId":"llm-1","time":"2026-05-07T10:00:00Z","phase":"execution","durationMs":42,
                   "model":"cheap-judge","endpoint":"openrouter","hasError":false,"inputTokens":10,"outputTokens":5}
                ],"toolTraces":[]}}
                """;
        when(agentDubboService.getResult(any(GetAgentRunResultRequest.class))).thenReturn(
                AgentRunResultMessage.newBuilder().setObservabilityJson(observability).build()
        );

        var response = controller.timeline(authentication, "run-1", 0, 100);

        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        TimelineResponse timeline = response.getData();
        assertEquals(2, timeline.items().size());
        assertTrue(timeline.items().stream().anyMatch(item ->
                "trace".equals(item.source()) && "llm-1".equals(item.traceId())));
    }

    @Test
    @SuppressWarnings("unchecked")
    void timeline_nonAdminScrubsEveryTraceStringBeforeReturningIt() {
        User nonAdmin = new User();
        nonAdmin.setUserId(99L);
        nonAdmin.setUserType(1);
        when(authService.getUserByUsername("admin")).thenReturn(nonAdmin);
        when(agentDubboService.listEvents(any(ListAgentRunEventsRequest.class))).thenReturn(
                ListAgentRunEventsResponse.newBuilder()
                        .addItems(AgentRunEventMessage.newBuilder()
                                .setSeq(1)
                                .setRunId("run-1")
                                .setEventType("TODO_STARTED")
                                .setPayloadJson("{\"todo_id\":\"todo_1\"}")
                                .setCreatedAt("2026-05-07T10:00:00Z")
                                .build())
                        .setNextAfterSeq(1)
                        .setHasMore(false)
                        .build());
        String observability = """
                {"summary":{},"diagnostics":{"llmTraces":[
                  {"traceId":"llm-1","time":"2026-05-07T10:00:00Z",
                   "phase":"body={\\"password\\":\\"phase secret!\\"}",
                   "todoId":"Cookie: session=todo-secret; refresh=other-secret",
                   "durationMs":42,"model":"X-Api-Key: model secret",
                   "endpoint":"https://example.test/?api_key=timeline-secret",
                   "hasError":false,"inputTokens":10,"outputTokens":5}
                ],"toolTraces":[]}}
                """;
        when(agentDubboService.getResult(any(GetAgentRunResultRequest.class))).thenReturn(
                AgentRunResultMessage.newBuilder().setObservabilityJson(observability).build());

        TimelineResponse timeline = controller.timeline(authentication, "run-1", 0, 100).getData();

        TimelineResponse.TimelineItem trace = timeline.items().stream()
                .filter(item -> "trace".equals(item.source()))
                .findFirst()
                .orElseThrow();
        Map<String, Object> detail = (Map<String, Object>) trace.detail();
        assertEquals("https://example.test/?api_key=" + AgentExternalObservabilityMapper.REDACTION_TEXT,
                detail.get("endpoint"));
        assertTrue(timeline.toString().contains(AgentExternalObservabilityMapper.REDACTION_TEXT));
        assertFalse(timeline.toString().contains("timeline-secret"));
        assertFalse(timeline.toString().contains("phase secret"));
        assertFalse(timeline.toString().contains("todo-secret"));
        assertFalse(timeline.toString().contains("other-secret"));
        assertFalse(timeline.toString().contains("model secret"));
    }

    @Test
    void timeline_shouldLimitTraceLiteItemsByOverallLimit() {
        when(agentDubboService.listEvents(any(ListAgentRunEventsRequest.class))).thenReturn(
                ListAgentRunEventsResponse.newBuilder()
                        .addItems(AgentRunEventMessage.newBuilder()
                                .setSeq(1)
                                .setRunId("run-1")
                                .setEventType("TODO_STARTED")
                                .setPayloadJson("{\"todo_id\":\"todo_1\"}")
                                .setCreatedAt("2026-05-07T10:00:00Z")
                                .build())
                        .setNextAfterSeq(1)
                        .setHasMore(false)
                        .build()
        );
        String observability = """
                {"summary":{},"diagnostics":{"llmTraces":[
                  {"traceId":"llm-1","time":"2026-05-07T10:00:00Z","phase":"execution","durationMs":1},
                  {"traceId":"llm-2","time":"2026-05-07T10:00:00Z","phase":"execution","durationMs":2},
                  {"traceId":"llm-3","time":"2026-05-07T10:00:00Z","phase":"execution","durationMs":3}
                ],"toolTraces":[
                  {"traceId":"tool-1","time":"2026-05-07T10:00:00Z","phase":"execution","durationMs":4}
                ]}}
                """;
        when(agentDubboService.getResult(any(GetAgentRunResultRequest.class))).thenReturn(
                AgentRunResultMessage.newBuilder().setObservabilityJson(observability).build()
        );

        var response = controller.timeline(authentication, "run-1", 0, 2);

        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        TimelineResponse timeline = response.getData();
        assertEquals(2, timeline.items().size());
        assertEquals(1, timeline.items().stream().filter(item -> "trace".equals(item.source())).count());
    }

    @Test
    void llmCallDetail_shouldReturnSafeDetail() {
        String observability = """
                {"summary":{},"diagnostics":{"llmTraces":[
                  {"traceId":"llm-1","time":"2026-05-07T10:00:00Z","phase":"execution","stage":"execute",
                   "durationMs":42,"model":"qwen-plus","hasError":false,"inputTokens":10,"outputTokens":5,
                   "outputText":"full-secret","reasoningText":"reason-secret","httpRequest":{"x":1}}
                ],"toolTraces":[]}}
                """;
        when(agentDubboService.getResult(any(GetAgentRunResultRequest.class))).thenReturn(
                AgentRunResultMessage.newBuilder().setObservabilityJson(observability).build()
        );

        var response = controller.llmCallDetail(authentication, "run-1", "llm-1", false);

        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        AgentCallDetailResponse detail = response.getData();
        assertEquals("llm", detail.getType());
        assertEquals(AgentCallDetailResponse.KIND_AVAILABLE, detail.getDetailKind());
        assertEquals("llm-1", detail.getId());
        assertNotNull(detail.getLlm());
        assertFalse(detail.getSummary().contains("full-secret"));
    }

    @Test
    void llmCallDetail_shouldReturnUnavailableWhenMissing() {
        when(agentDubboService.getResult(any(GetAgentRunResultRequest.class))).thenReturn(
                AgentRunResultMessage.newBuilder()
                        .setObservabilityJson("{\"summary\":{},\"diagnostics\":{\"llmTraces\":[],\"toolTraces\":[]}}")
                        .build()
        );

        var response = controller.llmCallDetail(authentication, "run-1", "missing", false);

        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        assertEquals(AgentCallDetailResponse.KIND_UNAVAILABLE, response.getData().getDetailKind());
    }

    @Test
    void toolCallDetail_shouldReturnUnavailableWhenTraceIdMismatch() {
        String observability = """
                {"summary":{},"diagnostics":{"llmTraces":[],"toolTraces":[
                  {"traceId":"internal-trace-only","toolName":"searchAssetInfo","success":true,"output":"{}"}
                ]}}
                """;
        when(agentDubboService.getResult(any(GetAgentRunResultRequest.class))).thenReturn(
                AgentRunResultMessage.newBuilder().setObservabilityJson(observability).build()
        );

        var response = controller.toolCallDetail(authentication, "run-1", "sse-tool-call-id");

        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        assertEquals(AgentCallDetailResponse.KIND_UNAVAILABLE, response.getData().getDetailKind());
    }

    @Test
    void traceDetail_fullTrue_shouldReturnSanitizedInlineLlmRawHttp() throws Exception {
        String observability = """
                {"summary":{},"diagnostics":{"llmTraces":[
                  {"traceId":"llm-1","time":"2026-05-07T10:00:00Z","phase":"execution",
                   "durationMs":42,"model":"qwen-plus","hasError":false,"detailBlobStored":true}
                ],"toolTraces":[]}}
                """;
        String raw = """
                {
                  "type":"llm_raw_http",
                  "runId":"run-1",
                  "traceId":"llm-1",
                  "httpRequest":{
                    "url":"https://openrouter.ai/api/v1/chat/completions?api_key=sk-openrouter-secret",
                    "method":"POST",
                    "headers":{"Authorization":"Bearer sk-openrouter-secret","X-Api-Key":"sk-another-secret"},
                    "body":"{\\"model\\":\\"qwen\\",\\"api_key\\":\\"sk-body-secret\\"}"
                  },
                  "httpResponse":{
                    "statusCode":200,
                    "headers":{"set-cookie":"sid=secret-session"},
                    "body":"{\\"id\\":\\"ok\\",\\"token\\":\\"sk-response-secret\\"}"
                  }
                }
                """;
        when(agentDubboService.getResult(any(GetAgentRunResultRequest.class))).thenReturn(
                AgentRunResultMessage.newBuilder().setId("run-1").setObservabilityJson(observability).build()
        );
        when(callDetailBlobReader.loadLlmCallRawContent("run-1", "llm-1")).thenReturn(Optional.of(raw));
        when(callDetailBlobReader.loadLlmCallRawMeta("run-1", "llm-1"))
                .thenReturn(Optional.of("{\"createdAtMillis\":1000,\"expiresAtMillis\":9999999999999}"));

        var response = controller.traceDetail(authentication, "run-1", "llm-1", true, 0);

        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        TraceDetailResponse detail = response.getData();
        assertEquals(null, detail.getHttpRequest());
        assertEquals(null, detail.getHttpResponse());
        assertEquals(null, detail.getCurlCommand());
        assertEquals(null, detail.getInputMessages());
        assertEquals(null, detail.getOutputText());
        assertEquals(null, detail.getReasoningText());
        assertNotNull(detail.getFullDetail());
        assertEquals(null, detail.getFullDetailParts());
        String requestJson = new String(Base64.getDecoder().decode(
                String.valueOf(detail.getFullDetail().get("httpRequestBase64"))), StandardCharsets.UTF_8);
        String responseJson = new String(Base64.getDecoder().decode(
                String.valueOf(detail.getFullDetail().get("httpResponseBase64"))), StandardCharsets.UTF_8);
        assertFalse(requestJson.contains("sk-openrouter-secret"));
        assertFalse(requestJson.contains("sk-body-secret"));
        assertFalse(responseJson.contains("sk-response-secret"));
        assertTrue(requestJson.contains(AgentRawTraceDetailMapper.REDACTION_TEXT));
        assertTrue(responseJson.contains(AgentRawTraceDetailMapper.REDACTION_TEXT));
    }

    @Test
    void traceDetail_fullFalse_shouldKeepSnapshotShapeAndSkipRawRedis() {
        String observability = """
                {"summary":{},"diagnostics":{"llmTraces":[
                  {"traceId":"llm-1","time":"2026-05-07T10:00:00Z","phase":"execution",
                   "durationMs":42,"model":"qwen-plus","hasError":false,"detailBlobStored":true,
                   "inputMessages":[{"role":"user","content":"raw-input"}],"outputText":"raw-output",
                   "reasoningText":"raw-reasoning","httpRequest":{"secret":"raw-http"},
                   "httpResponse":{"body":"raw-response"},"curlCommand":"curl raw"}
                ],"toolTraces":[]}}
                """;
        when(agentDubboService.getResult(any(GetAgentRunResultRequest.class))).thenReturn(
                AgentRunResultMessage.newBuilder().setId("run-1").setObservabilityJson(observability).build()
        );

        var response = controller.traceDetail(authentication, "run-1", "llm-1", false, 0);

        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        TraceDetailResponse detail = response.getData();
        assertEquals("llm", detail.getType());
        assertEquals("llm-1", detail.getTraceId());
        assertEquals(null, detail.getFullDetail());
        assertEquals(null, detail.getFullDetailParts());
        assertEquals(null, detail.getInputMessages());
        assertEquals(null, detail.getOutputText());
        assertEquals(null, detail.getReasoningText());
        assertEquals(null, detail.getHttpRequest());
        assertEquals(null, detail.getHttpResponse());
        assertEquals(null, detail.getCurlCommand());
        verify(callDetailBlobReader, never()).loadLlmCallRawContent(any(), any());
        verify(callDetailBlobReader, never()).loadLlmCallRawMeta(any(), any());
    }

    @Test
    void traceDetail_fullTrue_shouldDistinguishRawMissingAndExpired() {
        String observability = """
                {"summary":{},"diagnostics":{"llmTraces":[
                  {"traceId":"llm-missing","time":"2026-05-07T10:00:00Z","phase":"execution","detailBlobStored":true},
                  {"traceId":"llm-expired","time":"2026-05-07T10:00:00Z","phase":"execution","detailBlobStored":true}
                ],"toolTraces":[]}}
                """;
        when(agentDubboService.getResult(any(GetAgentRunResultRequest.class))).thenReturn(
                AgentRunResultMessage.newBuilder().setId("run-1").setObservabilityJson(observability).build()
        );
        when(callDetailBlobReader.loadLlmCallRawMeta("run-1", "llm-expired"))
                .thenReturn(Optional.of("{\"createdAtMillis\":1000,\"expiresAtMillis\":2000}"));

        var missing = controller.traceDetail(authentication, "run-1", "llm-missing", true, 0);
        var expired = controller.traceDetail(authentication, "run-1", "llm-expired", true, 0);

        assertEquals(ResponseCode.DATA_NOT_FOUND.getCode(), missing.getCode());
        assertEquals("RAW_TRACE_NOT_FOUND", missing.getMessage());
        assertEquals(ResponseCode.DATA_EXPIRED.getCode(), expired.getCode());
        assertEquals("RAW_TRACE_EXPIRED", expired.getMessage());
    }

    @Test
    void traceDetail_fullTrue_shouldReturnSanitizedInlineToolDetail() throws Exception {
        String observability = """
                {"summary":{},"diagnostics":{"llmTraces":[],"toolTraces":[
                  {"traceId":"tool-1","time":"2026-05-07T10:00:00Z","phase":"execution",
                   "toolName":"searchAssetInfo","success":true,"detailBlobStored":true}
                ]}}
                """;
        String detailJson = """
                {
                  "type":"tool",
                  "traceId":"tool-1",
                  "params":{"query":"AI ETF","api_key":"sk-tool-param-secret"},
                  "output":"{\\"result\\":\\"ok\\",\\"token\\":\\"sk-tool-output-secret\\"}"
                }
                """;
        when(agentDubboService.getResult(any(GetAgentRunResultRequest.class))).thenReturn(
                AgentRunResultMessage.newBuilder().setId("run-1").setObservabilityJson(observability).build()
        );
        when(callDetailBlobReader.loadToolCallDetail("run-1", "tool-1")).thenReturn(Optional.of(detailJson));

        var response = controller.traceDetail(authentication, "run-1", "tool-1", true, 0);

        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        TraceDetailResponse trace = response.getData();
        assertEquals("tool", trace.getType());
        assertEquals(null, trace.getParams());
        assertEquals(null, trace.getOutput());
        assertEquals(null, trace.getCacheKey());
        assertEquals(null, trace.getDecisionExcerpt());
        assertNotNull(trace.getFullDetail());
        String paramsJson = new String(Base64.getDecoder().decode(
                String.valueOf(trace.getFullDetail().get("paramsBase64"))), StandardCharsets.UTF_8);
        String outputJson = new String(Base64.getDecoder().decode(
                String.valueOf(trace.getFullDetail().get("outputBase64"))), StandardCharsets.UTF_8);
        assertFalse(paramsJson.contains("sk-tool-param-secret"));
        assertFalse(outputJson.contains("sk-tool-output-secret"));
        assertTrue(paramsJson.contains(AgentRawTraceDetailMapper.REDACTION_TEXT));
        assertTrue(outputJson.contains(AgentRawTraceDetailMapper.REDACTION_TEXT));
    }

    @Test
    void traceDetail_fullTrue_shouldReturnGzipPartsForLargePayloadAndPartsReassemble() throws Exception {
        String observability = """
                {"summary":{},"diagnostics":{"llmTraces":[
                  {"traceId":"llm-large","time":"2026-05-07T10:00:00Z","phase":"execution",
                   "durationMs":42,"model":"qwen-plus","hasError":false,"detailBlobStored":true}
                ],"toolTraces":[]}}
                """;
        String largeText = "x".repeat(257 * 1024);
        String raw = """
                {
                  "type":"llm_raw_http",
                  "runId":"run-1",
                  "traceId":"llm-large",
                  "httpRequest":{"url":"https://api.openai.com/v1/responses","headers":{"Authorization":"Bearer sk-large-secret"},"body":"%s"},
                  "httpResponse":{"statusCode":200,"body":"ok"}
                }
                """.formatted(largeText);
        String meta = "{\"createdAtMillis\":1000,\"expiresAtMillis\":9999999999999}";
        when(agentDubboService.getResult(any(GetAgentRunResultRequest.class))).thenReturn(
                AgentRunResultMessage.newBuilder().setId("run-1").setObservabilityJson(observability).build()
        );
        when(callDetailBlobReader.loadLlmCallRawContent("run-1", "llm-large")).thenReturn(Optional.of(raw));
        when(callDetailBlobReader.loadLlmCallRawMeta("run-1", "llm-large")).thenReturn(Optional.of(meta));

        var response = controller.traceDetail(authentication, "run-1", "llm-large", true, 65536);
        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        assertEquals(null, response.getData().getFullDetail());
        TraceDetailResponse.FullDetailParts parts = response.getData().getFullDetailParts();
        assertNotNull(parts);
        assertTrue(parts.totalParts() > 0);
        assertTrue(parts.partsUrl().contains("maxPartSize=65536"));

        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        for (int i = 0; i < parts.totalParts(); i++) {
            var partResponse = controller.traceFullPart(authentication, "run-1", "llm-large", i, 65536);
            assertEquals(200, partResponse.getStatusCode().value());
            compressed.write(partResponse.getBody());
        }
        byte[] actualFullDetailBytes = gunzip(compressed.toByteArray());
        byte[] expectedFullDetailBytes = AgentRawTraceDetailMapper.buildLlmPayload(
                new ObjectMapper(),
                "run-1",
                "llm-large",
                raw,
                AgentRawTraceDetailMapper.parseMeta(new ObjectMapper(), meta)).fullDetailBytes();
        assertEquals(new String(expectedFullDetailBytes, StandardCharsets.UTF_8),
                new String(actualFullDetailBytes, StandardCharsets.UTF_8));
        assertFalse(new String(actualFullDetailBytes, StandardCharsets.UTF_8).contains("sk-large-secret"));
    }

    @Test
    void llmCallDetail_adminWithThinking_returnsReasoningContent() {
        // admin user, blob has reasoningText → includeThinking=true 应映射到 reasoningContent
        String observability = """
                {"summary":{},"diagnostics":{"llmTraces":[
                  {"traceId":"llm-1","time":"2026-05-07T10:00:00Z","phase":"execution","stage":"execute",
                   "durationMs":42,"model":"qwen-plus","hasError":false,"inputTokens":10,"outputTokens":5,
                   "detailBlobStored":true}
                ],"toolTraces":[]}}
                """;
        when(agentDubboService.getResult(any(GetAgentRunResultRequest.class))).thenReturn(
                AgentRunResultMessage.newBuilder().setObservabilityJson(observability).build()
        );
        when(callDetailBlobReader.loadLlmCallDetail("run-1", "llm-1"))
                .thenReturn(Optional.of("{\"type\":\"llm\",\"traceId\":\"llm-1\",\"reasoningText\":\"admin-reasoning-text\"}"));

        var response = controller.llmCallDetail(authentication, "run-1", "llm-1", true);

        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        AgentCallDetailResponse detail = response.getData();
        assertEquals(AgentCallDetailResponse.KIND_AVAILABLE, detail.getDetailKind());
        assertNotNull(detail.getLlm());
        assertEquals("admin-reasoning-text", detail.getLlm().getReasoningContent());
        assertEquals(null, detail.getReasoningUnavailable());
    }

    @Test
    void llmCallDetail_nonAdminWithThinking_doesNotReturnReasoningContent() {
        // 非 admin 用户即使传 includeThinking=true 也应被服务端降级为 false（不抛错）
        User nonAdmin = new User();
        nonAdmin.setUserId(99L);
        nonAdmin.setUserType(1); // 1 = 普通用户
        when(authService.getUserByUsername("admin")).thenReturn(nonAdmin);

        String observability = """
                {"summary":{},"diagnostics":{"llmTraces":[
                  {"traceId":"llm-1","time":"2026-05-07T10:00:00Z","phase":"execution","stage":"execute",
                   "durationMs":42,"model":"qwen-plus","hasError":false,"inputTokens":10,"outputTokens":5,
                   "detailBlobStored":true}
                ],"toolTraces":[]}}
                """;
        when(agentDubboService.getResult(any(GetAgentRunResultRequest.class))).thenReturn(
                AgentRunResultMessage.newBuilder().setObservabilityJson(observability).build()
        );
        when(callDetailBlobReader.loadLlmCallDetail("run-1", "llm-1"))
                .thenReturn(Optional.of("{\"type\":\"llm\",\"traceId\":\"llm-1\",\"reasoningText\":\"leak-should-not-appear\"}"));

        var response = controller.llmCallDetail(authentication, "run-1", "llm-1", true);

        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        AgentCallDetailResponse detail = response.getData();
        // 非 admin 强制走 includeThinking=false 分支：blob 仍存在所以 source 是 redis，但 thinking 字段一律不出
        assertEquals(AgentCallDetailResponse.SOURCE_CALL_DETAIL_REDIS, detail.getSource());
        assertNotNull(detail.getLlm());
        assertEquals(null, detail.getLlm().getReasoningContent());
        assertEquals(null, detail.getReasoningUnavailable());
    }

    @Test
    void llmCallDetail_adminWithThinkingButBlobMissing_returnsAvailableWithHint() {
        // admin + includeThinking=true + blob 缺：detailKind 仍为 AVAILABLE，reasoningContent=null，reasoningUnavailable=true
        String observability = """
                {"summary":{},"diagnostics":{"llmTraces":[
                  {"traceId":"llm-1","time":"2026-05-07T10:00:00Z","phase":"execution","stage":"execute",
                   "durationMs":42,"model":"qwen-plus","hasError":false,"inputTokens":10,"outputTokens":5,
                   "detailBlobStored":true}
                ],"toolTraces":[]}}
                """;
        when(agentDubboService.getResult(any(GetAgentRunResultRequest.class))).thenReturn(
                AgentRunResultMessage.newBuilder().setObservabilityJson(observability).build()
        );
        when(callDetailBlobReader.loadLlmCallDetail("run-1", "llm-1")).thenReturn(Optional.empty());

        var response = controller.llmCallDetail(authentication, "run-1", "llm-1", true);

        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        AgentCallDetailResponse detail = response.getData();
        assertEquals(AgentCallDetailResponse.KIND_AVAILABLE, detail.getDetailKind());
        assertEquals(Boolean.TRUE, detail.getReasoningUnavailable());
        if (detail.getLlm() != null) {
            assertEquals(null, detail.getLlm().getReasoningContent());
        }
    }

    @Test
    void nonAdminObservabilityAndRawPartsAreForbiddenBeforeLoadingStorage() {
        setNonAdmin();

        var observability = controller.observabilityFull(authentication, "run-1");
        var snapshotParts = controller.snapshotParts(authentication, "run-1", 0);
        var snapshotPart = controller.snapshotPart(authentication, "run-1", 0, 0);
        var traceParts = controller.traceFullParts(authentication, "run-1", "llm-1", 0);
        var tracePart = controller.traceFullPart(authentication, "run-1", "llm-1", 0, 0);

        assertEquals(ResponseCode.FORBIDDEN.getCode(), observability.getCode());
        assertEquals(ResponseCode.FORBIDDEN.getCode(), snapshotParts.getCode());
        assertEquals(403, snapshotPart.getStatusCode().value());
        assertEquals(ResponseCode.FORBIDDEN.getCode(), traceParts.getCode());
        assertEquals(403, tracePart.getStatusCode().value());
        verify(agentDubboService, never()).getResult(any(GetAgentRunResultRequest.class));
        verify(agentDubboService, never()).getSnapshotPartsMeta(any());
        verify(agentDubboService, never()).getSnapshotPart(any());
        verify(callDetailBlobReader, never()).loadLlmCallRawContent(any(), any());
        verify(callDetailBlobReader, never()).loadToolCallDetail(any(), any());
    }

    @Test
    void nonAdminTraceDetailFullFalseIsSafeAndFullTrueIsForbidden() throws Exception {
        setNonAdmin();
        String observability = """
                {"summary":{},"diagnostics":{"llmTraces":[{
                  "traceId":"llm-1","phase":"execution","model":"qwen-plus","hasError":true,
                  "error":"api_key=plain-secret","inputMessages":[{"content":"raw-input"}],
                  "outputText":"raw-output","reasoningText":"raw-reasoning","attempts":[{"raw":true}],
                  "httpRequest":{"Authorization":"Bearer plain-secret"},"httpResponse":{"body":"raw"},
                  "curlCommand":"curl --header secret"}],"toolTraces":[]}}
                """;
        when(agentDubboService.getResult(any(GetAgentRunResultRequest.class))).thenReturn(
                AgentRunResultMessage.newBuilder().setObservabilityJson(observability).build());

        var safe = controller.traceDetail(authentication, "run-1", "llm-1", false, 0);
        var forbidden = controller.traceDetail(authentication, "run-1", "llm-1", true, 0);

        assertEquals(ResponseCode.SUCCESS.getCode(), safe.getCode());
        String json = new ObjectMapper().writeValueAsString(safe.getData());
        assertFalse(json.contains("raw-input"));
        assertFalse(json.contains("raw-output"));
        assertFalse(json.contains("raw-reasoning"));
        assertFalse(json.contains("plain-secret"));
        assertFalse(json.contains("httpRequest"));
        assertFalse(json.contains("curlCommand"));
        assertEquals(ResponseCode.FORBIDDEN.getCode(), forbidden.getCode());
        verify(callDetailBlobReader, never()).loadLlmCallRawContent(any(), any());
    }

    @Test
    void traceListUsesOnlySafePreviewFields() {
        setNonAdmin();
        String observability = """
                {"summary":{"llmCalls":1,"toolCalls":1},"diagnostics":{
                  "llmTraces":[{"traceId":"llm-1","outputText":"legacy-raw-llm","responsePreview":"safe-llm"}],
                  "toolTraces":[{"traceId":"tool-1","output":"legacy-raw-tool","outputPreview":"safe-tool"}]}}
                """;
        when(agentDubboService.getResult(any(GetAgentRunResultRequest.class))).thenReturn(
                AgentRunResultMessage.newBuilder().setObservabilityJson(observability).build());

        var response = controller.traces(authentication, "run-1", "", "", 0, 100);

        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        assertEquals("safe-llm", response.getData().spans().stream()
                .filter(span -> "llm".equals(span.getType())).findFirst().orElseThrow().getOutputSummary());
        assertEquals("safe-tool", response.getData().spans().stream()
                .filter(span -> "tool".equals(span.getType())).findFirst().orElseThrow().getOutputSummary());
        assertFalse(response.getData().toString().contains("legacy-raw"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void nonAdminResultAndRunSnapshotStripNestedObservabilityAndRawCompletedOutputs() {
        setNonAdmin();
        String snapshot = """
                {"answer":"ok","status":"COMPLETED","observability":{"diagnostics":{"raw":"leak"}},
                 "completed_items":[{"todoId":"todo-1","sequence":1,"summary":"done",
                 "output":"raw-tool-output","modelOutput":"raw-model-output"}],"internal":"hidden"}
                """;
        when(agentDubboService.getResult(any(GetAgentRunResultRequest.class))).thenReturn(
                AgentRunResultMessage.newBuilder().setId("run-1").setStatus("COMPLETED")
                        .setPayloadJson(snapshot).setStructuredAnswerJson("{\"final\":\"safe\"}").build());
        when(agentDubboService.getRun(any())).thenReturn(
                AgentRunMessage.newBuilder().setId("run-1").setStatus("COMPLETED")
                        .setPlanJson("{\"steps\":[]}").setSnapshotJson(snapshot)
                        .setLastError("api_key=run-error-secret")
                        .setExt("{\"api_key\":\"ext-secret\",\"execution_mode\":\"AUTO\"}").build());

        var result = controller.result(authentication, "run-1");
        var run = controller.get(authentication, "run-1");

        Map<String, Object> resultPayload = (Map<String, Object>) result.getBody().getData().payload();
        Map<String, Object> runSnapshot = (Map<String, Object>) run.getData().snapshot();
        String combined = resultPayload.toString() + runSnapshot;
        assertTrue(combined.contains("answer=ok"));
        assertFalse(combined.contains("observability"));
        assertFalse(combined.contains("raw-tool-output"));
        assertFalse(combined.contains("raw-model-output"));
        assertFalse(combined.contains("internal"));
        assertFalse(run.getData().lastError().contains("run-error-secret"));
        assertFalse(run.getData().ext().contains("ext-secret"));
        assertTrue(run.getData().ext().contains("execution_mode"));
    }

    @Test
    void nonAdminArtifactMetadataIsStrictlyParsedAndScrubbed() {
        setNonAdmin();
        when(agentDubboService.listArtifacts(any())).thenReturn(
                ListAgentArtifactsResponse.newBuilder()
                        .addItems(AgentArtifactMessage.newBuilder()
                                .setArtifactId("artifact-1")
                                .setMetaJson("{\"httpRequest\":{\"Authorization\":\"Bearer artifact-secret\"},\"label\":\"safe\"}")
                                .build())
                        .addItems(AgentArtifactMessage.newBuilder()
                                .setArtifactId("artifact-2")
                                .setMetaJson("{broken artifact-raw-secret")
                                .build())
                        .build());

        var response = controller.artifacts(authentication, "run-1");

        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        assertFalse(response.getData().get(0).metaJson().contains("artifact-secret"));
        assertTrue(response.getData().get(0).metaJson().contains("safe"));
        assertEquals(null, response.getData().get(1).metaJson());
    }

    @Test
    void nonAdminStatusNeverReturnsFullObservabilityOrMalformedRawPayload() {
        setNonAdmin();
        when(agentDubboService.getStatus(any())).thenReturn(
                AgentRunStatusMessage.newBuilder()
                        .setId("run-1")
                        .setStatus("EXECUTING")
                        .setLastEventPayloadJson("{broken raw-secret")
                        .setObservabilityJson("{\"diagnostics\":{\"raw\":\"leak\"}}")
                        .setObservabilitySummaryJson("{\"llmCalls\":1}")
                        .build());

        var response = controller.status(authentication, "run-1");

        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        assertEquals(null, response.getData().observability());
        assertEquals(null, response.getData().lastEventPayload());
        assertNotNull(response.getData().observabilitySummary());
    }

    @Test
    @SuppressWarnings("unchecked")
    void nonAdminStatusPlanOmitsOrdinaryPlannerReasoningAndToolParameters() {
        setNonAdmin();
        when(agentDubboService.getStatus(any())).thenReturn(
                AgentRunStatusMessage.newBuilder()
                        .setId("run-1")
                        .setStatus("EXECUTING")
                        .setPlanJson(unsafePlanJson())
                        .build());

        var response = controller.status(authentication, "run-1");

        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        assertSafeNonAdminPlan((Map<String, Object>) response.getData().plan());
    }

    @Test
    void statusExposesActualLatestSeqInsteadOfEventCountAndNormalizesAlias() {
        when(agentDubboService.getStatus(any())).thenReturn(
                AgentRunStatusMessage.newBuilder().setId("run-1").setStatus(" timed_out ")
                        .setEventCount(2).build());
        when(agentDubboService.listEvents(any(ListAgentRunEventsRequest.class))).thenReturn(
                ListAgentRunEventsResponse.newBuilder()
                        .addItems(AgentRunEventMessage.newBuilder().setRunId("run-1").setSeq(7).build())
                        .build());

        var response = controller.status(authentication, "run-1");

        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        assertEquals("EXPIRED", response.getData().status());
        assertEquals(2, response.getData().eventCount());
        assertEquals(7, response.getData().lastSeq());
    }

    @Test
    @SuppressWarnings("unchecked")
    void nonAdminRunDetailPlanOmitsOrdinaryPlannerReasoningAndToolParameters() {
        setNonAdmin();
        when(agentDubboService.getRun(any())).thenReturn(
                AgentRunMessage.newBuilder()
                        .setId("run-1")
                        .setStatus("EXECUTING")
                        .setPlanJson(unsafePlanJson())
                        .build());

        var response = controller.get(authentication, "run-1");

        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        assertSafeNonAdminPlan((Map<String, Object>) response.getData().plan());
    }

    @Test
    void malformedEventPayloadNeverEchoesRawInput() {
        setNonAdmin();
        when(agentDubboService.listEvents(any(ListAgentRunEventsRequest.class))).thenReturn(
                ListAgentRunEventsResponse.newBuilder()
                        .addItems(AgentRunEventMessage.newBuilder()
                                .setRunId("run-1").setSeq(1).setEventType("TOOL_CALL_FINISHED")
                                .setPayloadJson("{broken raw-secret").build())
                        .build());

        var response = controller.events(authentication, "run-1", 0, 20);

        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        var event = response.getData().items().get(0);
        assertEquals(1, event.schemaVersion());
        assertEquals("agent.event", event.type());
        assertEquals(true, event.durable());
        assertEquals("INVALID_JSON", event.payload().get("value"));
        assertFalse(event.payload().toString().contains("raw-secret"));
    }

    @Test
    void securitySensitiveRoutesRemainRegisteredAtCompatiblePaths() {
        assertGetRoute(AgentObservabilityController.class, "diagnosticReadCapabilities",
                "/api/agent/diagnostics/read-capabilities");
        assertGetRoute(AgentArtifactController.class, "snapshotParts", "/api/agent/runs/{runId}/snapshot/parts");
        assertGetRoute(AgentArtifactController.class, "snapshotPart", "/api/agent/runs/{runId}/snapshot/parts/{partIndex}");
        assertGetRoute(AgentObservabilityController.class, "events", "/api/agent/runs/{runId}/events");
        assertGetRoute(AgentObservabilityController.class, "timeline", "/api/agent/runs/{runId}/timeline");
        assertGetRoute(AgentController.class, "result", "/api/agent/runs/{runId}/result");
        assertGetRoute(AgentObservabilityController.class, "observabilityFull", "/api/agent/runs/{runId}/observability/full");
        assertGetRoute(AgentObservabilityController.class, "traces", "/api/agent/runs/{runId}/traces");
        assertGetRoute(AgentObservabilityController.class, "traceDetail", "/api/agent/runs/{runId}/traces/{traceId}");
        assertGetRoute(AgentObservabilityController.class, "traceFullParts", "/api/agent/runs/{runId}/traces/{traceId}/full/parts");
        assertGetRoute(AgentObservabilityController.class, "traceFullPart", "/api/agent/runs/{runId}/traces/{traceId}/full/parts/{partIndex}");
    }

    private void setNonAdmin() {
        User nonAdmin = new User();
        nonAdmin.setUserId(99L);
        nonAdmin.setUserType(1);
        when(authService.getUserByUsername("admin")).thenReturn(nonAdmin);
    }

    private String unsafePlanJson() {
        return """
                {
                  "analysis":"full planner reasoning",
                  "executionMode":"DAG",
                  "strategy":{"reasoning":"nested strategy reasoning"},
                  "extractedEntities":["沪深300",{"reasoning":"nested entity reasoning"}],
                  "items":[{
                    "id":"todo-1","sequence":1,"type":"TOOL_CALL","toolName":"searchIndex",
                    "description":"查询指数公开信息",
                    "dependsOn":["todo-0",{"params":{"keyword":"nested dependency query"}}],
                    "parallelizable":true,
                    "params":{"keyword":"private business query"},
                    "reasoning":"full todo reasoning"
                  }]
                }
                """;
    }

    @SuppressWarnings("unchecked")
    private void assertSafeNonAdminPlan(Map<String, Object> plan) {
        assertNotNull(plan);
        assertEquals("DAG", plan.get("executionMode"));
        assertFalse(plan.containsKey("strategy"));
        assertEquals(java.util.List.of("沪深300"), plan.get("extractedEntities"));
        assertFalse(plan.containsKey("analysis"));
        Map<String, Object> item = (Map<String, Object>) ((java.util.List<?>) plan.get("items")).get(0);
        assertEquals("查询指数公开信息", item.get("description"));
        assertEquals("searchIndex", item.get("toolName"));
        assertEquals(java.util.List.of("todo-0"), item.get("dependsOn"));
        assertFalse(item.containsKey("params"));
        assertFalse(item.containsKey("reasoning"));
        assertFalse(plan.toString().contains("private business query"));
        assertFalse(plan.toString().contains("nested dependency query"));
        assertFalse(plan.toString().contains("nested strategy reasoning"));
        assertFalse(plan.toString().contains("nested entity reasoning"));
        assertFalse(plan.toString().contains("full planner reasoning"));
        assertFalse(plan.toString().contains("full todo reasoning"));
    }

    private static void assertGetRoute(Class<?> controllerType, String methodName, String expectedPath) {
        var method = java.util.Arrays.stream(controllerType.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
        GetMapping mapping = method.getAnnotation(GetMapping.class);
        assertNotNull(mapping);
        assertTrue(java.util.Arrays.asList(mapping.value()).contains(expectedPath));
    }

    private static byte[] gunzip(byte[] compressed) throws Exception {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            gzip.transferTo(out);
            return out.toByteArray();
        }
    }
}
