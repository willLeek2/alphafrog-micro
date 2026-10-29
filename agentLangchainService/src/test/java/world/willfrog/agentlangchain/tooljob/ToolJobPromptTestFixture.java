package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.finance.FinanceToolResultFormatter;
import world.willfrog.agent.platform.prompt.TerminalResultPromptTexts;
import world.willfrog.agentlangchain.support.LangchainTestFixtures;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptText;
import static org.mockito.Mockito.*;

/** 终态测试在产生结果处绑定实际导入版本，不替旧持久结果推断来源。 */
final class ToolJobPromptTestFixture {
    private ToolJobPromptTestFixture() { }

    static TerminalResultPromptTexts policies() {
        return new TerminalResultPromptTexts(new LangchainTestFixtures.VersionedNodeInputs().reader());
    }

    static FinanceToolResultFormatter formatter() {
        return new FinanceToolResultFormatter(new ObjectMapper(), policies());
    }

    static AgentRun run(String id) {
        AgentRun run = new AgentRun();
        run.setId(id);
        run.setUserId("terminal-fixture-user");
        return run;
    }

    static AgentRunMapper runs() {
        AgentRunMapper runs = mock(AgentRunMapper.class);
        lenient().when(runs.findById(anyString())).thenAnswer(call -> run(call.getArgument(0)));
        return runs;
    }

    static ToolJobFinalizer configure(ToolJobFinalizer finalizer) {
        ReflectionTestUtils.setField(finalizer, "terminalPromptTexts", policies());
        if (ReflectionTestUtils.getField(finalizer, "agentRunMapper") == null) {
            ReflectionTestUtils.setField(finalizer, "agentRunMapper", runs());
        }
        return finalizer;
    }

    static <T> T configureTool(T tool) {
        ReflectionTestUtils.setField(tool, "terminalPromptTexts", policies());
        return tool;
    }

    static PromptText instructionResult(String actualJson, String fragment) {
        PromptText instruction = policies().instruction(fragment);
        if (!actualJson.contains(instruction.text())) {
            throw new AssertionError("Fixture output does not contain its declared instruction: " + fragment);
        }
        return new PromptText(actualJson, instruction.sources());
    }
}
