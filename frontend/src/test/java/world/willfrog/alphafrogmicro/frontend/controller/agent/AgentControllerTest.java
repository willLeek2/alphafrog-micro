package world.willfrog.alphafrogmicro.frontend.controller.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.alphafrogmicro.agent.idl.AgentDubboService;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunEventMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunResultMessage;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunResultRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentRunEventsRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentRunEventsResponse;
import world.willfrog.alphafrogmicro.agent.idl.SendAgentMessageRequest;
import world.willfrog.alphafrogmicro.agent.idl.SendAgentMessageResponse;
import world.willfrog.alphafrogmicro.common.dto.ResponseCode;
import world.willfrog.alphafrogmicro.common.pojo.user.User;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentCallDetailResponse;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentMessageSendRequest;
import world.willfrog.alphafrogmicro.frontend.model.agent.AgentRunCreateRequest;
import world.willfrog.alphafrogmicro.frontend.model.agent.TimelineResponse;
import world.willfrog.alphafrogmicro.frontend.service.AuthService;
import world.willfrog.alphafrogmicro.frontend.service.agent.AgentCallDetailBlobReader;
import world.willfrog.alphafrogmicro.frontend.service.agent.AgentRunResultCacheService;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentControllerTest {

    private AgentDubboService agentDubboService;
    private AuthService authService;
    private AgentRunResultCacheService runResultCacheService;
    private AgentCallDetailBlobReader callDetailBlobReader;
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
        when(callDetailBlobReader.loadLlmCallDetail(any(), any())).thenReturn(Optional.empty());
        when(callDetailBlobReader.loadToolCallDetail(any(), any())).thenReturn(Optional.empty());
        controller = new AgentController(authService, new ObjectMapper(), runResultCacheService, callDetailBlobReader);
        ReflectionTestUtils.setField(controller, "agentDubboServiceLangchain", agentDubboService);
        ReflectionTestUtils.setField(controller, "agentDubboServiceLegacy", agentDubboService);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/agent/runs/run-1");
        ReflectionTestUtils.setField(controller, "request", request);

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
                        null, null, null, null, null, null, null));

        assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        assertEquals("/api/agent/runs/run-new/stream", response.getData().streamUrl());
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

        var response = controller.llmCallDetail(authentication, "run-1", "llm-1");

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

        var response = controller.llmCallDetail(authentication, "run-1", "missing");

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
}
