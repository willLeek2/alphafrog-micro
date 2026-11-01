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
import world.willfrog.alphafrogmicro.agent.idl.ResumeAgentRunRequest;
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
import world.willfrog.alphafrogmicro.frontend.service.agent.ExperimentResultMetricAssembler;
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
                new AgentTimelineMergeService(objectMapper), mock(ExperimentResultMetricAssembler.class));
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
