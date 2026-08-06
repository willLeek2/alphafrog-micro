package world.willfrog.agent.tools.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.artifact.RunRawRefStore;
import world.willfrog.agent.platform.artifact.ToolOutputReadResult;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.alphafrogmicro.externalinfo.idl.ExternalInfoDubboService;
import world.willfrog.alphafrogmicro.externalinfo.idl.RagSearchRequest;
import world.willfrog.alphafrogmicro.externalinfo.idl.RagSearchResponse;
import world.willfrog.alphafrogmicro.externalinfo.idl.RagSearchResultItem;

import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagToolsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void tearDown() {
        AgentContext.clear();
    }

    @Test
    void ragSearchShouldReturnStructuredRefsAndShortRawRefHints() throws Exception {
        ExternalInfoDubboService service = mock(ExternalInfoDubboService.class);
        when(service.ragSearch(any(RagSearchRequest.class))).thenReturn(RagSearchResponse.newBuilder()
                .addItems(RagSearchResultItem.newBuilder()
                        .setScore(0.91f)
                        .setDocType("announcement")
                        .setTsCode("600519.SH")
                        .setTitle("贵州茅台2024年年度报告")
                        .setDate("20250430")
                        .setChunkText("经营活动产生的现金流量净额".repeat(80))
                        .setOssUrl("oss://reports/600519-2024.md")
                        .setChunkIndex(12)
                        .build())
                .setTotal(1)
                .build());

        StubRunRawRefStore rawRefStore = new StubRunRawRefStore();
        RagTools tools = new RagTools(objectMapper, Optional.of(rawRefStore), Optional.empty());
        injectService(tools, service);
        AgentContext.setRunId("run-1");
        AgentContext.setUserId("user-1");

        Map<String, Object> response = objectMapper.readValue(
                tools.ragSearch("现金流", "announcement", "600519.SH", "", 5),
                new TypeReference<>() {}
        );

        assertEquals(Boolean.TRUE, response.get("ok"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) data.get("summary");
        assertEquals(1, summary.get("hit_count"));
        assertEquals(Boolean.TRUE, summary.get("budget_hit"));
        assertEquals("raw_ref_001", data.get("rawRef"));
        assertFalse(data.containsKey("raw_refs"), "full raw refs must not be visible to the agent");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> topRefs = (List<Map<String, Object>>) data.get("top_refs");
        Map<String, Object> ref = topRefs.get(0);
        assertEquals("rag_ref_001", ref.get("ref_id"));
        assertEquals("oss://reports/600519-2024.md#chunk=12", ref.get("source_key"));
        assertEquals(12, ref.get("chunk_index"));
        assertNotNull(ref.get("preview"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hints = (List<Map<String, Object>>) data.get("read_hints");
        assertEquals("keyword", hints.get(0).get("mode"));
        assertEquals("raw_ref_001", hints.get(0).get("rawRef"));
        assertEquals("range", hints.get(1).get("mode"));
        assertEquals("raw_ref_001", hints.get(1).get("rawRef"));
        assertTrue(rawRefStore.registeredContent.contains("raw_refs"));
    }

    @Test
    void loadDocumentShouldReturnPreviewAndShortRawRefForLongDocument() throws Exception {
        String body = "长文档内容".repeat(1300);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/doc", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            StubRunRawRefStore rawRefStore = new StubRunRawRefStore();
            RagTools tools = new RagTools(objectMapper, Optional.of(rawRefStore), Optional.empty());
            AgentContext.setRunId("run-doc");
            AgentContext.setUserId("user-doc");

            Map<String, Object> response = objectMapper.readValue(
                    tools.loadDocument("http://127.0.0.1:" + server.getAddress().getPort() + "/doc"),
                    new TypeReference<>() {}
            );

            assertEquals(Boolean.TRUE, response.get("ok"));
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) response.get("data");
            assertEquals(Boolean.TRUE, data.get("truncated"));
            assertEquals("raw_ref_001", data.get("rawRef"));
            assertFalse(data.containsKey("content"), "full document must be hidden after rawRef registration");
            assertNotNull(data.get("read_hints"));
            assertTrue(rawRefStore.registeredContent.contains(body));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void loadDocumentShouldRejectRawRefArgument() throws Exception {
        RagTools tools = new RagTools(objectMapper, Optional.empty(), Optional.empty());

        Map<String, Object> response = objectMapper.readValue(
                tools.loadDocument("raw_ref_001"),
                new TypeReference<>() {}
        );

        assertEquals(Boolean.FALSE, response.get("ok"));
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) response.get("error");
        assertEquals("INVALID_ARGUMENT", error.get("code"));
    }

    private void injectService(RagTools tools, ExternalInfoDubboService service) throws Exception {
        Field field = RagTools.class.getDeclaredField("externalInfoDubboService");
        field.setAccessible(true);
        field.set(tools, service);
    }

    private static class StubRunRawRefStore implements RunRawRefStore {
        private String registeredContent;

        @Override
        public String register(String runId, String userId, String displayName, String content, long ttlSeconds) {
            this.registeredContent = content;
            return "raw_ref_001";
        }

        @Override
        public String read(String runId, String shortId) {
            return registeredContent;
        }

        @Override
        public ToolOutputReadResult read(String runId, String shortId, int offset, int limit, String keyword) {
            return ToolOutputReadResult.builder()
                    .content("window")
                    .hasMore(false)
                    .nextOffset(6)
                    .totalLength(6)
                    .build();
        }

        @Override
        public boolean belongsToRun(String runId, String shortId) {
            return "raw_ref_001".equals(shortId);
        }
    }
}
