package world.willfrog.agent.tools.rag;

import static world.willfrog.agent.tools.support.ToolPromptTestFixture.configure;
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




    private void injectService(RagTools tools, ExternalInfoDubboService service) throws Exception {
        Field field = RagTools.class.getDeclaredField("externalInfoDubboService");
        field.setAccessible(true);
        field.set(tools, service);
    }

    private static class StubRunRawRefStore implements RunRawRefStore {
        private String registeredContent;

        @Override
        public String registerPrompt(String runId, String userId, String displayName,
                world.willfrog.alphafrogmicro.common.racing.prompt.PromptText content, long ttlSeconds) {
            return register(runId, userId, displayName, content.text(), ttlSeconds);
        }

        @Override
        public String register(String runId, String userId, String displayName, String content, long ttlSeconds) {
            this.registeredContent = content;
            return "raw_ref_001";
        }

        @Override
        public String read(String runId, String userId, String shortId) {
            return registeredContent;
        }

        @Override
        public ToolOutputReadResult read(String runId, String userId, String shortId,
                                         int offset, int limit, String keyword) {
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
