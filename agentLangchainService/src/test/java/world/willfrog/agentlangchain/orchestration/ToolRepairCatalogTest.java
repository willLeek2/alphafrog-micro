package world.willfrog.agentlangchain.orchestration;

import org.junit.jupiter.api.Test;
import world.willfrog.agentlangchain.tooljob.ToolJobResumeContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolRepairCatalogTest {

    @Test
    void pythonHandlerClaimsNonZeroExitAndRejectsInfrastructureFailure() {
        ToolRepairCatalog catalog = ToolRepairCatalog.pythonDefaults();

        assertThat(catalog.handlerForFailure(nonZeroExit()).map(ToolRepairHandler::toolName))
                .contains("executePython");
        assertThat(catalog.handlerForFailure(executionError())).isEmpty();
    }

    @Test
    void unknownToolHasNoHandler() {
        ToolRepairCatalog catalog = new ToolRepairCatalog(List.of());
        assertThat(catalog.handlerForTool("executePython")).isEmpty();
        assertThat(catalog.handlerForFailure(nonZeroExit())).isEmpty();
    }

    private static ToolJobResumeContext nonZeroExit() {
        ToolJobResumeContext context = new ToolJobResumeContext();
        context.setTerminalSuccess(false);
        context.setTerminalStatus("FAILED");
        context.setTerminalRetryable(false);
        context.setTerminalExitReason("NON_ZERO_EXIT");
        context.setPythonFailedRequestFingerprints(List.of("sha256:failed"));
        return context;
    }

    private static ToolJobResumeContext executionError() {
        ToolJobResumeContext context = nonZeroExit();
        context.setTerminalExitReason("EXECUTION_ERROR");
        return context;
    }
}
