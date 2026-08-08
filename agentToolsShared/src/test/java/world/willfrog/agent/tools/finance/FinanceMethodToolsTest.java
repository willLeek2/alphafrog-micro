package world.willfrog.agent.tools.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import world.willfrog.agent.platform.context.AgentContext;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖 {@link FinanceMethodTools} 的主流程、别名兜底与技术失败降级。
 */
class FinanceMethodToolsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FinanceMethodSpecCatalog specCatalog = new FinanceMethodSpecCatalog(objectMapper);
    private final FinanceMethodResolverCatalog resolverCatalog = new FinanceMethodResolverCatalog(objectMapper);
    private final FinanceMethodResolutionValidator validator = new FinanceMethodResolutionValidator(specCatalog);
    private final FinanceMethodKnowledgeCatalog knowledgeCatalog = new FinanceMethodKnowledgeCatalog(objectMapper);
    private final FinanceMethodSuggestionRenderer renderer = new FinanceMethodSuggestionRenderer(
            specCatalog, knowledgeCatalog, objectMapper);

    @BeforeEach
    void setUp() {
        AgentContext.setRunId("run-1");
        AgentContext.setTodoContext("todo-1", 1);
        AgentContext.setToolCallId("resolver-call-1");
    }

    @AfterEach
    void tearDown() {
        AgentContext.clear();
    }

    @Test
    void shouldReturnSuggestionsFromModel() throws Exception {
        FinanceMethodSpec cagr = specCatalog.findByMethodId("finance.growth.cagr").orElseThrow();
        String modelJson = "{"
                + "\"status\":\"MATCHED\","
                + "\"candidates\":[{"
                + "  \"methodId\":\"" + cagr.getMethodId() + "\","
                + "  \"version\":\"" + cagr.getVersion() + "\","
                + "  \"specDigest\":\"" + cagr.getSpecDigest() + "\","
                + "  \"matchReason\":\"用户希望计算复合增长率\","
                + "  \"unresolvedTerms\":[],"
                + "  \"clarificationQuestions\":[]"
                + "}],"
                + "\"matchReason\":\"匹配成功\","
                + "\"unresolvedTerms\":[],"
                + "\"clarificationQuestions\":[]"
                + "}";

        FinanceMethodResolverClient client = mock(FinanceMethodResolverClient.class);
        when(client.resolve(any(), any(), any())).thenReturn(
                new FinanceMethodResolverClient.Ok(modelJson,
                        new FinanceMethodResolverClient.RouteInfo("openrouter", "https://api.openrouter.ai", "gpt-4o-mini")));
        FinanceMethodResolutionSink sink = mock(FinanceMethodResolutionSink.class);

        FinanceMethodTools tools = new FinanceMethodTools(
                specCatalog, resolverCatalog, validator, renderer, knowledgeCatalog, objectMapper);
        tools.setResolverClient(client);
        tools.setResolutionSink(sink);

        String result = tools.resolveFinanceMethods("这几年股票涨了多少，给个可比较的增长速度", null);
        JsonNode node = objectMapper.readTree(result);
        assertTrue(node.get("ok").asBoolean());
        assertEquals("resolver-call-1", node.get("data").get("resolverToolCallId").asText());
        assertEquals("MATCHED", node.get("data").get("status").asText());
        JsonNode suggestions = node.get("data").get("suggestions");
        assertEquals(1, suggestions.size());
        assertEquals(cagr.getDisplayName(), suggestions.get(0).get("displayName").asText());
        assertNotNull(suggestions.get(0).get("definition"));

        verify(sink).saveAll(any());
    }

    @Test
    void exactAliasFallbackShouldWorkWhenModelUnavailable() throws Exception {
        FinanceMethodResolverClient client = mock(FinanceMethodResolverClient.class);
        when(client.resolve(any(), any(), any()))
                .thenReturn(new FinanceMethodResolverClient.TechnicalError(
                        FinanceMethodResolverClient.ErrorKind.TIMEOUT, "timeout"));
        FinanceMethodResolutionSink sink = mock(FinanceMethodResolutionSink.class);

        FinanceMethodTools tools = new FinanceMethodTools(
                specCatalog, resolverCatalog, validator, renderer, knowledgeCatalog, objectMapper);
        tools.setResolverClient(client);
        tools.setResolutionSink(sink);

        String result = tools.resolveFinanceMethods("CAGR", null);
        JsonNode node = objectMapper.readTree(result);
        assertTrue(node.get("ok").asBoolean());
        assertEquals("MATCHED", node.get("data").get("status").asText());
        assertTrue(node.get("data").get("usedExactAliasFallback").asBoolean());
        verify(sink).saveAll(any());
    }

    @Test
    void longSentenceContainingAliasSubstringDoesNotFallback() throws Exception {
        FinanceMethodResolverClient client = mock(FinanceMethodResolverClient.class);
        when(client.resolve(any(), any(), any()))
                .thenReturn(new FinanceMethodResolverClient.TechnicalError(
                        FinanceMethodResolverClient.ErrorKind.TIMEOUT, "timeout"));

        FinanceMethodTools tools = new FinanceMethodTools(
                specCatalog, resolverCatalog, validator, renderer, knowledgeCatalog, objectMapper);
        tools.setResolverClient(client);

        String result = tools.resolveFinanceMethods("请帮我计算一下CAGR大概是多少", null);
        JsonNode node = objectMapper.readTree(result);
        assertFalse(node.get("ok").asBoolean());
        assertEquals("RESOLVER_UNAVAILABLE", node.get("error").get("code").asText());
    }

    @Test
    void exactAliasMultiMethodHitReturnsEmpty() throws Exception {
        FinanceMethodSpecCatalog mockCatalog = mock(FinanceMethodSpecCatalog.class);
        FinanceMethodResolutionValidator mockValidator = new FinanceMethodResolutionValidator(mockCatalog);
        FinanceMethodSuggestionRenderer mockRenderer = new FinanceMethodSuggestionRenderer(
                mockCatalog, knowledgeCatalog, objectMapper);

        FinanceMethodSpec specA = FinanceMethodSpec.builder()
                .methodId("finance.x.a")
                .version("1.0.0")
                .specDigest("sha256:a")
                .displayName("A")
                .resolverHints(FinanceMethodSpec.FinanceResolverHints.builder()
                        .aliases(List.of("overlap"))
                        .build())
                .outputs(List.of(FinanceMethodSpec.FinanceOutput.builder()
                        .name("out").unit("u").description("d").build()))
                .build();
        FinanceMethodSpec specB = FinanceMethodSpec.builder()
                .methodId("finance.x.b")
                .version("1.0.0")
                .specDigest("sha256:b")
                .displayName("B")
                .resolverHints(FinanceMethodSpec.FinanceResolverHints.builder()
                        .aliases(List.of("overlap"))
                        .build())
                .outputs(List.of(FinanceMethodSpec.FinanceOutput.builder()
                        .name("out").unit("u").description("d").build()))
                .build();

        when(mockCatalog.listAll()).thenReturn(List.of(specA, specB));
        when(mockCatalog.find("finance.x.a", "1.0.0", "sha256:a")).thenReturn(java.util.Optional.of(specA));
        when(mockCatalog.find("finance.x.b", "1.0.0", "sha256:b")).thenReturn(java.util.Optional.of(specB));

        FinanceMethodResolverClient client = mock(FinanceMethodResolverClient.class);
        when(client.resolve(any(), any(), any()))
                .thenReturn(new FinanceMethodResolverClient.TechnicalError(
                        FinanceMethodResolverClient.ErrorKind.TIMEOUT, "timeout"));

        FinanceMethodTools tools = new FinanceMethodTools(
                mockCatalog, resolverCatalog, mockValidator, mockRenderer, knowledgeCatalog, objectMapper);
        tools.setResolverClient(client);

        String result = tools.resolveFinanceMethods("overlap", null);
        JsonNode node = objectMapper.readTree(result);
        assertFalse(node.get("ok").asBoolean());
        assertEquals("RESOLVER_UNAVAILABLE", node.get("error").get("code").asText());
    }

    @Test
    void technicalFailureWithoutAliasReturnsError() throws Exception {
        FinanceMethodResolverClient client = mock(FinanceMethodResolverClient.class);
        when(client.resolve(any(), any(), any()))
                .thenReturn(new FinanceMethodResolverClient.TechnicalError(
                        FinanceMethodResolverClient.ErrorKind.CATALOG_BUDGET_EXCEEDED, "too large"));

        FinanceMethodTools tools = new FinanceMethodTools(
                specCatalog, resolverCatalog, validator, renderer, knowledgeCatalog, objectMapper);
        tools.setResolverClient(client);

        String result = tools.resolveFinanceMethods("完全无关的问题", null);
        JsonNode node = objectMapper.readTree(result);
        assertFalse(node.get("ok").asBoolean());
        assertEquals("RESOLVER_CATALOG_BUDGET_EXCEEDED", node.get("error").get("code").asText());
    }

    @Test
    void badModelOutputReturnsError() throws Exception {
        FinanceMethodResolverClient client = mock(FinanceMethodResolverClient.class);
        when(client.resolve(any(), any(), any())).thenReturn(new FinanceMethodResolverClient.Ok("not json", null));

        FinanceMethodTools tools = new FinanceMethodTools(
                specCatalog, resolverCatalog, validator, renderer, knowledgeCatalog, objectMapper);
        tools.setResolverClient(client);

        String result = tools.resolveFinanceMethods("问题", null);
        JsonNode node = objectMapper.readTree(result);
        assertFalse(node.get("ok").asBoolean());
        assertEquals("RESOLVER_BAD_MODEL_OUTPUT", node.get("error").get("code").asText());
    }

    @Test
    void sinkFailureReturnsToolError() throws Exception {
        FinanceMethodSpec cagr = specCatalog.findByMethodId("finance.growth.cagr").orElseThrow();
        String modelJson = "{"
                + "\"status\":\"MATCHED\","
                + "\"candidates\":[{"
                + "  \"methodId\":\"" + cagr.getMethodId() + "\","
                + "  \"version\":\"" + cagr.getVersion() + "\","
                + "  \"specDigest\":\"" + cagr.getSpecDigest() + "\","
                + "  \"matchReason\":\"ok\","
                + "  \"unresolvedTerms\":[],"
                + "  \"clarificationQuestions\":[]"
                + "}],"
                + "\"matchReason\":\"\","
                + "\"unresolvedTerms\":[],"
                + "\"clarificationQuestions\":[]"
                + "}";

        FinanceMethodResolverClient client = mock(FinanceMethodResolverClient.class);
        when(client.resolve(any(), any(), any())).thenReturn(new FinanceMethodResolverClient.Ok(modelJson, null));
        FinanceMethodResolutionSink sink = mock(FinanceMethodResolutionSink.class);
        doThrow(new FinanceMethodResolutionSinkException("db down")).when(sink).saveAll(any());

        FinanceMethodTools tools = new FinanceMethodTools(
                specCatalog, resolverCatalog, validator, renderer, knowledgeCatalog, objectMapper);
        tools.setResolverClient(client);
        tools.setResolutionSink(sink);

        String result = tools.resolveFinanceMethods("CAGR", null);
        JsonNode node = objectMapper.readTree(result);
        assertFalse(node.get("ok").asBoolean());
        assertEquals("RESOLVER_SNAPSHOT_SAVE_FAILED", node.get("error").get("code").asText());
    }

    @Test
    void blankRunIdWithSuggestionsReturnsError() throws Exception {
        AgentContext.clear();
        AgentContext.setToolCallId("resolver-call-1");

        FinanceMethodSpec cagr = specCatalog.findByMethodId("finance.growth.cagr").orElseThrow();
        String modelJson = "{"
                + "\"status\":\"MATCHED\","
                + "\"candidates\":[{"
                + "  \"methodId\":\"" + cagr.getMethodId() + "\","
                + "  \"version\":\"" + cagr.getVersion() + "\","
                + "  \"specDigest\":\"" + cagr.getSpecDigest() + "\","
                + "  \"matchReason\":\"ok\","
                + "  \"unresolvedTerms\":[],"
                + "  \"clarificationQuestions\":[]"
                + "}],"
                + "\"matchReason\":\"\","
                + "\"unresolvedTerms\":[],"
                + "\"clarificationQuestions\":[]"
                + "}";

        FinanceMethodResolverClient client = mock(FinanceMethodResolverClient.class);
        when(client.resolve(any(), any(), any())).thenReturn(new FinanceMethodResolverClient.Ok(modelJson, null));
        FinanceMethodResolutionSink sink = mock(FinanceMethodResolutionSink.class);

        FinanceMethodTools tools = new FinanceMethodTools(
                specCatalog, resolverCatalog, validator, renderer, knowledgeCatalog, objectMapper);
        tools.setResolverClient(client);
        tools.setResolutionSink(sink);

        String result = tools.resolveFinanceMethods("CAGR", null);
        JsonNode node = objectMapper.readTree(result);
        assertFalse(node.get("ok").asBoolean());
        assertEquals("RESOLVER_RUN_ID_MISSING", node.get("error").get("code").asText());
        verify(sink, never()).saveAll(any());
    }

    @Test
    void sinkMissingWithSuggestionsReturnsError() throws Exception {
        FinanceMethodSpec cagr = specCatalog.findByMethodId("finance.growth.cagr").orElseThrow();
        String modelJson = "{"
                + "\"status\":\"MATCHED\","
                + "\"candidates\":[{"
                + "  \"methodId\":\"" + cagr.getMethodId() + "\","
                + "  \"version\":\"" + cagr.getVersion() + "\","
                + "  \"specDigest\":\"" + cagr.getSpecDigest() + "\","
                + "  \"matchReason\":\"ok\","
                + "  \"unresolvedTerms\":[],"
                + "  \"clarificationQuestions\":[]"
                + "}],"
                + "\"matchReason\":\"\","
                + "\"unresolvedTerms\":[],"
                + "\"clarificationQuestions\":[]"
                + "}";

        FinanceMethodResolverClient client = mock(FinanceMethodResolverClient.class);
        when(client.resolve(any(), any(), any())).thenReturn(new FinanceMethodResolverClient.Ok(modelJson, null));

        FinanceMethodTools tools = new FinanceMethodTools(
                specCatalog, resolverCatalog, validator, renderer, knowledgeCatalog, objectMapper);
        tools.setResolverClient(client);
        // resolutionSink is null

        String result = tools.resolveFinanceMethods("CAGR", null);
        JsonNode node = objectMapper.readTree(result);
        assertFalse(node.get("ok").asBoolean());
        assertEquals("RESOLVER_SINK_NOT_CONFIGURED", node.get("error").get("code").asText());
    }

    @Test
    void noResolverClientReturnsUnavailable() throws Exception {
        FinanceMethodTools tools = new FinanceMethodTools(
                specCatalog, resolverCatalog, validator, renderer, knowledgeCatalog, objectMapper);
        // resolverClient is null
        String result = tools.resolveFinanceMethods("完全无关的问题", null);
        JsonNode node = objectMapper.readTree(result);
        assertFalse(node.get("ok").asBoolean());
        assertEquals("RESOLVER_UNAVAILABLE", node.get("error").get("code").asText());
    }

    @Test
    void modelRouteJsonComesFromOkRouteField() throws Exception {
        FinanceMethodSpec cagr = specCatalog.findByMethodId("finance.growth.cagr").orElseThrow();
        String modelJson = "{"
                + "\"status\":\"MATCHED\","
                + "\"candidates\":[{"
                + "  \"methodId\":\"" + cagr.getMethodId() + "\","
                + "  \"version\":\"" + cagr.getVersion() + "\","
                + "  \"specDigest\":\"" + cagr.getSpecDigest() + "\","
                + "  \"matchReason\":\"ok\","
                + "  \"unresolvedTerms\":[],"
                + "  \"clarificationQuestions\":[]"
                + "}],"
                + "\"matchReason\":\"\","
                + "\"unresolvedTerms\":[],"
                + "\"clarificationQuestions\":[]"
                + "}";
        FinanceMethodResolverClient.RouteInfo route =
                new FinanceMethodResolverClient.RouteInfo("openrouter", "https://api.openrouter.ai", "gpt-4o-mini");
        String expectedRouteJson = "{\"endpoint\":\"https://api.openrouter.ai\",\"model\":\"gpt-4o-mini\",\"provider\":\"openrouter\"}";

        FinanceMethodResolverClient client = mock(FinanceMethodResolverClient.class);
        when(client.resolve(any(), any(), any())).thenReturn(new FinanceMethodResolverClient.Ok(modelJson, route));
        FinanceMethodResolutionSink sink = mock(FinanceMethodResolutionSink.class);

        FinanceMethodTools tools = new FinanceMethodTools(
                specCatalog, resolverCatalog, validator, renderer, knowledgeCatalog, objectMapper);
        tools.setResolverClient(client);
        tools.setResolutionSink(sink);

        String result = tools.resolveFinanceMethods("CAGR", null);
        JsonNode node = objectMapper.readTree(result);
        assertTrue(node.get("ok").asBoolean());

        ArgumentCaptor<List<FinanceMethodResolutionSnapshot>> captor = ArgumentCaptor.forClass(List.class);
        verify(sink).saveAll(captor.capture());
        List<FinanceMethodResolutionSnapshot> snapshots = captor.getValue();
        assertEquals(1, snapshots.size());
        assertEquals(expectedRouteJson, snapshots.get(0).modelRouteJson());
    }

    @Test
    void routeInfoRejectsBlankProvider() {
        assertThrows(IllegalArgumentException.class,
                () -> new FinanceMethodResolverClient.RouteInfo("", "https://example.com", "model"));
        assertThrows(IllegalArgumentException.class,
                () -> new FinanceMethodResolverClient.RouteInfo("   ", "https://example.com", "model"));
    }

    @Test
    void routeInfoRejectsBlankEndpoint() {
        assertThrows(IllegalArgumentException.class,
                () -> new FinanceMethodResolverClient.RouteInfo("provider", "", "model"));
        assertThrows(IllegalArgumentException.class,
                () -> new FinanceMethodResolverClient.RouteInfo("provider", null, "model"));
    }

    @Test
    void routeInfoRejectsBlankModel() {
        assertThrows(IllegalArgumentException.class,
                () -> new FinanceMethodResolverClient.RouteInfo("provider", "https://example.com", ""));
        assertThrows(IllegalArgumentException.class,
                () -> new FinanceMethodResolverClient.RouteInfo("provider", "https://example.com", "  "));
    }

    @Test
    void modelRouteJsonDoesNotUseRawJsonContent() throws Exception {
        FinanceMethodSpec cagr = specCatalog.findByMethodId("finance.growth.cagr").orElseThrow();
        String modelJson = "{"
                + "\"status\":\"MATCHED\","
                + "\"candidates\":[{"
                + "  \"methodId\":\"" + cagr.getMethodId() + "\","
                + "  \"version\":\"" + cagr.getVersion() + "\","
                + "  \"specDigest\":\"" + cagr.getSpecDigest() + "\","
                + "  \"matchReason\":\"model raw output mentions provider=forged endpoint=forged model=forged\","
                + "  \"unresolvedTerms\":[],"
                + "  \"clarificationQuestions\":[]"
                + "}],"
                + "\"matchReason\":\"\","
                + "\"unresolvedTerms\":[],"
                + "\"clarificationQuestions\":[]"
                + "}";
        FinanceMethodResolverClient.RouteInfo route =
                new FinanceMethodResolverClient.RouteInfo("openrouter", "https://api.openrouter.ai", "gpt-4o-mini");
        String expectedRouteJson = "{\"endpoint\":\"https://api.openrouter.ai\",\"model\":\"gpt-4o-mini\",\"provider\":\"openrouter\"}";

        FinanceMethodResolverClient client = mock(FinanceMethodResolverClient.class);
        when(client.resolve(any(), any(), any())).thenReturn(new FinanceMethodResolverClient.Ok(modelJson, route));
        FinanceMethodResolutionSink sink = mock(FinanceMethodResolutionSink.class);

        FinanceMethodTools tools = new FinanceMethodTools(
                specCatalog, resolverCatalog, validator, renderer, knowledgeCatalog, objectMapper);
        tools.setResolverClient(client);
        tools.setResolutionSink(sink);

        tools.resolveFinanceMethods("CAGR", null);

        ArgumentCaptor<List<FinanceMethodResolutionSnapshot>> captor = ArgumentCaptor.forClass(List.class);
        verify(sink).saveAll(captor.capture());
        assertEquals(expectedRouteJson, captor.getValue().get(0).modelRouteJson());
    }

    @Test
    void exactAliasFallbackSnapshotRouteIsConstant() throws Exception {
        FinanceMethodResolverClient client = mock(FinanceMethodResolverClient.class);
        when(client.resolve(any(), any(), any()))
                .thenReturn(new FinanceMethodResolverClient.TechnicalError(
                        FinanceMethodResolverClient.ErrorKind.TIMEOUT, "timeout"));
        FinanceMethodResolutionSink sink = mock(FinanceMethodResolutionSink.class);

        FinanceMethodTools tools = new FinanceMethodTools(
                specCatalog, resolverCatalog, validator, renderer, knowledgeCatalog, objectMapper);
        tools.setResolverClient(client);
        tools.setResolutionSink(sink);

        tools.resolveFinanceMethods("CAGR", null);

        ArgumentCaptor<List<FinanceMethodResolutionSnapshot>> captor = ArgumentCaptor.forClass(List.class);
        verify(sink).saveAll(captor.capture());
        assertEquals("{\"route\":\"exact_alias_fallback\"}",
                captor.getValue().get(0).modelRouteJson());
    }

    @Test
    void targetEnvironmentCapturedInSnapshotsAndSuggestions() throws Exception {
        FinanceMethodSpec cagr = specCatalog.findByMethodId("finance.growth.cagr").orElseThrow();
        FinanceMethodSpec vol = specCatalog.findByMethodId("finance.risk.annualized_volatility").orElseThrow();
        String modelJson = "{"
                + "\"status\":\"AMBIGUOUS\","
                + "\"candidates\":[{"
                + "  \"methodId\":\"" + cagr.getMethodId() + "\","
                + "  \"version\":\"" + cagr.getVersion() + "\","
                + "  \"specDigest\":\"" + cagr.getSpecDigest() + "\","
                + "  \"matchReason\":\"可能指增长\","
                + "  \"unresolvedTerms\":[],"
                + "  \"clarificationQuestions\":[]"
                + "},{"
                + "  \"methodId\":\"" + vol.getMethodId() + "\","
                + "  \"version\":\"" + vol.getVersion() + "\","
                + "  \"specDigest\":\"" + vol.getSpecDigest() + "\","
                + "  \"matchReason\":\"可能指波动\","
                + "  \"unresolvedTerms\":[],"
                + "  \"clarificationQuestions\":[]"
                + "}],"
                + "\"matchReason\":\"\","
                + "\"unresolvedTerms\":[],"
                + "\"clarificationQuestions\":[]"
                + "}";

        FinanceMethodSuggestionRenderer.TargetEnvironment env =
                new FinanceMethodSuggestionRenderer.TargetEnvironment("env-1",
                        List.of(new FinanceMethodSuggestionRenderer.TargetEnvironment.PackageApi(
                                "alphafrog_finance", "1.0.3", "1.7")));
        FinanceTargetEnvironmentProvider provider = mock(FinanceTargetEnvironmentProvider.class);
        when(provider.currentTargetEnvironment()).thenReturn(Optional.of(env));

        FinanceMethodResolverClient client = mock(FinanceMethodResolverClient.class);
        when(client.resolve(any(), any(), any())).thenReturn(new FinanceMethodResolverClient.Ok(modelJson, null));
        FinanceMethodResolutionSink sink = mock(FinanceMethodResolutionSink.class);

        FinanceMethodTools tools = new FinanceMethodTools(
                specCatalog, resolverCatalog, validator, renderer, knowledgeCatalog, objectMapper);
        tools.setResolverClient(client);
        tools.setResolutionSink(sink);
        tools.setTargetEnvironmentProvider(provider);

        String result = tools.resolveFinanceMethods("CAGR", null);
        JsonNode node = objectMapper.readTree(result);
        assertTrue(node.get("ok").asBoolean());
        JsonNode suggestions = node.get("data").get("suggestions");
        assertEquals(2, suggestions.size());

        JsonNode cagrSuggestion = suggestions.get(0);
        assertTrue(cagrSuggestion.has("library"));
        assertEquals(true, cagrSuggestion.get("library").get("available").asBoolean());
        assertNotNull(cagrSuggestion.get("sample"));

        ArgumentCaptor<List<FinanceMethodResolutionSnapshot>> captor = ArgumentCaptor.forClass(List.class);
        verify(sink).saveAll(captor.capture());
        List<FinanceMethodResolutionSnapshot> snapshots = captor.getValue();
        assertEquals(2, snapshots.size());
        String expectedApiJson = "[{\"name\":\"alphafrog_finance\",\"version\":\"1.0.3\",\"apiVersion\":\"1.7\"}]";
        for (FinanceMethodResolutionSnapshot snapshot : snapshots) {
            assertEquals("env-1", snapshot.targetEnvironmentId());
            assertEquals(expectedApiJson, snapshot.targetPackageApiJson());
        }
    }

    @Test
    void missingTargetEnvironmentReturnsNullLibraryAndSnapshotFields() throws Exception {
        FinanceMethodSpec cagr = specCatalog.findByMethodId("finance.growth.cagr").orElseThrow();
        String modelJson = "{"
                + "\"status\":\"MATCHED\","
                + "\"candidates\":[{"
                + "  \"methodId\":\"" + cagr.getMethodId() + "\","
                + "  \"version\":\"" + cagr.getVersion() + "\","
                + "  \"specDigest\":\"" + cagr.getSpecDigest() + "\","
                + "  \"matchReason\":\"ok\","
                + "  \"unresolvedTerms\":[],"
                + "  \"clarificationQuestions\":[]"
                + "}],"
                + "\"matchReason\":\"\","
                + "\"unresolvedTerms\":[],"
                + "\"clarificationQuestions\":[]"
                + "}";

        FinanceTargetEnvironmentProvider provider = mock(FinanceTargetEnvironmentProvider.class);
        when(provider.currentTargetEnvironment()).thenReturn(Optional.empty());

        FinanceMethodResolverClient client = mock(FinanceMethodResolverClient.class);
        when(client.resolve(any(), any(), any())).thenReturn(new FinanceMethodResolverClient.Ok(modelJson, null));
        FinanceMethodResolutionSink sink = mock(FinanceMethodResolutionSink.class);

        FinanceMethodTools tools = new FinanceMethodTools(
                specCatalog, resolverCatalog, validator, renderer, knowledgeCatalog, objectMapper);
        tools.setResolverClient(client);
        tools.setResolutionSink(sink);
        tools.setTargetEnvironmentProvider(provider);

        String result = tools.resolveFinanceMethods("CAGR", null);
        JsonNode node = objectMapper.readTree(result);
        assertTrue(node.get("ok").asBoolean());
        JsonNode suggestion = node.get("data").get("suggestions").get(0);
        assertTrue(suggestion.get("library").isNull());
        assertTrue(suggestion.get("sample").isNull());

        ArgumentCaptor<List<FinanceMethodResolutionSnapshot>> captor = ArgumentCaptor.forClass(List.class);
        verify(sink).saveAll(captor.capture());
        FinanceMethodResolutionSnapshot snapshot = captor.getValue().get(0);
        assertNull(snapshot.targetEnvironmentId());
        assertNull(snapshot.targetPackageApiJson());
    }
}
