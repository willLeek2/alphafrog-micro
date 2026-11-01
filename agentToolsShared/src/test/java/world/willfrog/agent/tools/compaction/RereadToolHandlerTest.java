package world.willfrog.agent.tools.compaction;

import static world.willfrog.agent.tools.support.ToolPromptTestFixture.configure;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.artifact.RawPayloadLocator;
import world.willfrog.agent.platform.artifact.RunRawRefStore;
import world.willfrog.agent.platform.artifact.ToolOutputReadResult;
import world.willfrog.agent.platform.artifact.ToolOutputRefService;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RereadToolHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void tearDown() {
        AgentContext.clear();
    }














    private static class StubToolOutputRefService implements ToolOutputRefService {
        @Override
        public world.willfrog.agent.platform.artifact.PersistentArtifactRegistration registerRawOutput(
                String logicalId, String displayName, String content) {
            throw new UnsupportedOperationException();
        }

        @Override
        public world.willfrog.agent.platform.artifact.PersistentArtifactRegistration rebindFromLocator(
                String logicalId, String displayName, RawPayloadLocator locator) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RawPayloadLocator locatorFor(String rawRef) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ToolOutputReadResult read(String rawRef, int offset, int limit, String keyword) {
            // 模拟主代码全部入口改严格之后：无归属上下文的 rawRef 一律被服务层拒绝。
            if ("raw-ref-contextless".equals(rawRef)) {
                throw new IllegalArgumentException("rawRef does not belong to current run/user");
            }
            return ToolOutputReadResult.builder()
                    .content("content")
                    .contentPrompt(world.willfrog.alphafrogmicro.common.racing.prompt.PromptText.runtimeData("content"))
                    .hasMore(false)
                    .nextOffset(7)
                    .totalLength(7)
                    .build();
        }

        // D22-5.1.3 显式上下文 overload：本 stub 只服务 legacy reread 路径，新入口同语义兜底。
        @Override
        public world.willfrog.agent.platform.artifact.PersistentArtifactRegistration registerRawOutput(
                String runId, String userId, String logicalId, String displayName, String content) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RawPayloadLocator locatorFor(String runId, String userId, String rawRef) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ToolOutputReadResult read(String runId, String userId, String rawRef,
                                         int offset, int limit, String keyword) {
            return read(rawRef, offset, limit, keyword);
        }
    }

    private static class StubRunRawRefStore implements RunRawRefStore {
        private static final String OWNER_RUN = "run-1";
        private static final String OWNER_USER = "user-1";

        @Override
        public String register(String runId, String userId, String displayName, String content, long ttlSeconds) {
            return "raw_ref_001";
        }

        @Override
        public String read(String runId, String userId, String shortId) {
            requireOwner(runId, userId);
            return "short-content";
        }

        @Override
        public ToolOutputReadResult read(String runId, String userId, String shortId,
                                         int offset, int limit, String keyword) {
            requireOwner(runId, userId);
            return ToolOutputReadResult.builder()
                    .content("short-content")
                    .contentPrompt(world.willfrog.alphafrogmicro.common.racing.prompt.PromptText.runtimeData("short-content"))
                    .hasMore(false)
                    .nextOffset(13)
                    .totalLength(13)
                    .build();
        }

        @Override
        public boolean belongsToRun(String runId, String shortId) {
            return true;
        }

        // 第三轮 MUST-FIX ②：stub 模拟生产 readContentStrict 的合同——runId 与 userId
        // 任一空白或与属主不一致即 fail-closed 抛异常，不存在只凭 runId 放行的读取。
        private static void requireOwner(String runId, String userId) {
            if (runId == null || runId.isBlank() || userId == null || userId.isBlank()
                    || !OWNER_RUN.equals(runId) || !OWNER_USER.equals(userId)) {
                throw new IllegalArgumentException("Artifact does not belong to current run/user");
            }
        }
    }
}
