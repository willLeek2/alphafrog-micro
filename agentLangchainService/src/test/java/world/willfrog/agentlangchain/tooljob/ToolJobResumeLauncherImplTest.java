package world.willfrog.agentlangchain.tooljob;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agentlangchain.execution.LangchainLinearRunPipelineImpl;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ToolJobResumeLauncherImplTest {





    private static ToolJobResumeContext context() {
        ToolJobResumeContext context = new ToolJobResumeContext();
        context.setRunId("run-1");
        context.setTodoId("todo-2");
        context.setResumeToken("token-1");
        context.setResumeLeaseVersion(7);
        context.setResumeLauncherOwnerId("owner-1");
        return context;
    }
}
