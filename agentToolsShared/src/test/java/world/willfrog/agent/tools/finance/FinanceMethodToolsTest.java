package world.willfrog.agent.tools.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.finance.FinanceMethodResolutionSink;
import world.willfrog.agent.platform.finance.FinanceMethodResolutionSinkException;
import world.willfrog.agent.platform.finance.FinanceMethodResolutionSnapshot;
import world.willfrog.agent.platform.finance.FinanceMethodResolverClient;

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

    private static final FinanceMethodResolverClient.RouteInfo ROUTE =
            new FinanceMethodResolverClient.RouteInfo("openrouter", "https://api.openrouter.ai", "gpt-4o-mini");
    private static final String PROMPT_VERSION = "sha256:prompt-under-test";

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

























}
