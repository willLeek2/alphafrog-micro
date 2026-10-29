package world.willfrog.agent.tools.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.prompt.AgentPromptTextReader;
import world.willfrog.agent.tools.compaction.RereadToolHandler;
import world.willfrog.agent.tools.dataanalysis.SqlQueryTools;
import world.willfrog.agent.tools.docs.DocumentToolPromptPreparation;
import world.willfrog.agent.tools.docs.LoadToolGuideTool;
import world.willfrog.agent.tools.market.MarketDataPromptPolicy;
import world.willfrog.agent.tools.market.MarketDataTools;
import world.willfrog.agent.tools.prompt.ProducerPromptTexts;
import world.willfrog.agent.tools.python.PythonSandboxTools;
import world.willfrog.agent.tools.rag.RagTools;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptBodyType;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptVariableDeclaration;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptVersionBody;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptVersionSource;
import static org.mockito.Mockito.*;

/** 既有工具测试的版本读取边界替身；正文来自实际导入文件，校验与渲染使用真实实现。 */
public final class ToolPromptTestFixture {
    private ToolPromptTestFixture() { }

    public static <T> T configure(T tool) {
        AgentPromptTextReader reader = reader();
        if (tool instanceof MarketDataTools) {
            ReflectionTestUtils.setField(tool, "promptPolicy", new MarketDataPromptPolicy(reader));
        } else if (tool instanceof RereadToolHandler || tool instanceof RagTools || tool instanceof LoadToolGuideTool) {
            ReflectionTestUtils.setField(tool, "promptPreparation", new DocumentToolPromptPreparation(reader));
        } else if (tool instanceof PythonSandboxTools || tool instanceof SqlQueryTools) {
            ReflectionTestUtils.setField(tool, "promptTexts", new ProducerPromptTexts(reader));
            if (tool instanceof PythonSandboxTools) {
                ReflectionTestUtils.setField(tool, "terminalPromptTexts",
                        new world.willfrog.agent.platform.prompt.TerminalResultPromptTexts(reader));
            }
        } else throw new AssertionError("Unsupported fixture tool " + tool.getClass());
        return tool;
    }

    public static AgentPromptTextReader reader() {
        var root = mock(AgentPromptTextReader.RootPrompts.class, withSettings().mockMaker("mock-maker-inline"));
        return mock(AgentPromptTextReader.class, withSettings().mockMaker("mock-maker-inline").defaultAnswer(call -> {
            if (call.getMethod().getName().equals("current")) return root;
            if (call.getMethod().getName().equals("read")) {
                Map<String, String> requested = call.getArgument(1);
                Map<String, PromptVersionBody> result = new LinkedHashMap<>();
                requested.forEach((fragment, purpose) -> result.put(fragment, imported(fragment).requirePurpose(purpose)));
                return result;
            }
            return CALLS_REAL_METHODS.answer(call);
        }));
    }

    private static PromptVersionBody imported(String fragment) {
        for (String group : List.of("document-tools", "market-delegates", "market-results", "sandbox-producers",
                "dataset-producers", "terminal-results", "router-results")) {
            String path = "/prompt-import/" + group + "/" + fragment + ".json";
            try (var stream = ToolPromptTestFixture.class.getResourceAsStream(path)) {
                if (stream == null) continue;
                var body = new ObjectMapper().readTree(stream);
                List<PromptVariableDeclaration> variables = new ArrayList<>();
                body.path("variables").forEach(v -> variables.add(new PromptVariableDeclaration(
                        v.path("name").asText(), v.path("required").asBoolean(), v.path("allowEmpty").asBoolean(),
                        v.hasNonNull("defaultValue") ? v.path("defaultValue").asText() : null)));
                return new PromptVersionBody(new PromptVersionSource("test", "tools-fixture-scope", fragment, 1,
                        body.path("purpose").asText(), "fixture-selection", null, null, "fixture-key", null,
                        fragment, null), PromptBodyType.valueOf(body.path("bodyType").asText()),
                        body.path("body").asText(), variables);
            } catch (java.io.IOException error) { throw new AssertionError(path, error); }
        }
        throw new AssertionError("No explicit imported fixture version for " + fragment);
    }
}
