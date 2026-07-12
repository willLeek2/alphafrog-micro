package world.willfrog.agentlangchain.tooljob;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agentlangchain.orchestration.LangchainLinearRunPipelineImpl;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ToolJobResumeLauncherImplTest {

    @Test
    void sameTokenAndVersionIsAcceptedOnlyOnceWhileActive() {
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        LangchainLinearRunPipelineImpl pipeline = mock(LangchainLinearRunPipelineImpl.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ToolJobResumeService> provider = mock(ObjectProvider.class);
        ToolJobResumeService resumeService = mock(ToolJobResumeService.class);
        when(provider.getIfAvailable()).thenReturn(resumeService);
        when(resumeService.markConsumed("run-1")).thenReturn(true);
        AgentRun run = new AgentRun();
        run.setId("run-1");
        when(runMapper.findById("run-1")).thenReturn(run);
        AtomicReference<BooleanSupplier> consumed = new AtomicReference<>();
        AtomicReference<Runnable> completion = new AtomicReference<>();
        when(pipeline.launchResumedAsync(eq(run), any(), any(), any())).thenAnswer(invocation -> {
            consumed.set(invocation.getArgument(2));
            completion.set(invocation.getArgument(3));
            return true;
        });
        ToolJobResumeLauncherImpl launcher = new ToolJobResumeLauncherImpl(runMapper, pipeline, provider);
        ToolJobResumeContext context = context();

        assertThat(launcher.launch("run-1", context)).isTrue();
        assertThat(launcher.isActive("run-1", "token-1", 7)).isTrue();
        assertThat(launcher.launch("run-1", context)).isTrue();
        verify(pipeline, times(1)).launchResumedAsync(eq(run), same(context), any(), any());

        assertThat(consumed.get().getAsBoolean()).isTrue();
        verify(resumeService).markConsumed("run-1");
        completion.get().run();
        assertThat(launcher.isActive("run-1", "token-1", 7)).isFalse();
    }

    private static ToolJobResumeContext context() {
        ToolJobResumeContext context = new ToolJobResumeContext();
        context.setRunId("run-1");
        context.setTodoId("todo-2");
        context.setResumeToken("token-1");
        context.setResumeLeaseVersion(7);
        return context;
    }
}
