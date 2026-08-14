package world.willfrog.agentlangchain.tooljob;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agentlangchain.orchestration.LangchainLinearRunPipelineImpl;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

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
        when(resumeService.markHandoffAccepted(eq("run-1"), any())).thenReturn(true);
        AgentRun run = new AgentRun();
        run.setId("run-1");
        when(runMapper.findById("run-1")).thenReturn(run);
        AtomicReference<BooleanSupplier> consumed = new AtomicReference<>();
        AtomicReference<Consumer<Boolean>> completion = new AtomicReference<>();
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
        verify(resumeService).markHandoffAccepted("run-1", context);
        completion.get().accept(true);
        verify(resumeService).completeHandoff("run-1", "token-1", 7, "owner-1");
        assertThat(launcher.isActive("run-1", "token-1", 7)).isFalse();
    }

    @Test
    void nondurablePipelineOutcomeRemovesActiveClaimButKeepsAnchor() {
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        LangchainLinearRunPipelineImpl pipeline = mock(LangchainLinearRunPipelineImpl.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ToolJobResumeService> provider = mock(ObjectProvider.class);
        ToolJobResumeService resumeService = mock(ToolJobResumeService.class);
        when(provider.getIfAvailable()).thenReturn(resumeService);
        AgentRun run = new AgentRun();
        run.setId("run-1");
        when(runMapper.findById("run-1")).thenReturn(run);
        AtomicReference<Consumer<Boolean>> completion = new AtomicReference<>();
        when(pipeline.launchResumedAsync(eq(run), any(), any(), any())).thenAnswer(invocation -> {
            completion.set(invocation.getArgument(3));
            return true;
        });
        ToolJobResumeLauncherImpl launcher = new ToolJobResumeLauncherImpl(runMapper, pipeline, provider);

        assertThat(launcher.launch("run-1", context())).isTrue();
        completion.get().accept(false);

        verify(resumeService, never()).completeHandoff(any(), any(), anyLong(), any());
        assertThat(launcher.isActive("run-1", "token-1", 7)).isFalse();
    }

    @Test
    void heartbeatKeepsOnlyClaimsStillOwnedInDatabase() {
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        LangchainLinearRunPipelineImpl pipeline = mock(LangchainLinearRunPipelineImpl.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ToolJobResumeService> provider = mock(ObjectProvider.class);
        ToolJobResumeService resumeService = mock(ToolJobResumeService.class);
        when(provider.getIfAvailable()).thenReturn(resumeService);
        AgentRun run = new AgentRun();
        run.setId("run-1");
        when(runMapper.findById("run-1")).thenReturn(run);
        when(pipeline.launchResumedAsync(eq(run), any(), any(), any())).thenReturn(true);
        when(resumeService.heartbeat("run-1", "token-1", 7L, "owner-1"))
                .thenReturn(true, false);
        ToolJobResumeLauncherImpl launcher = new ToolJobResumeLauncherImpl(runMapper, pipeline, provider);
        ToolJobResumeLauncherHeartbeat heartbeat =
                new ToolJobResumeLauncherHeartbeat(launcher, provider);

        assertThat(launcher.launch("run-1", context())).isTrue();
        heartbeat.heartbeatActiveClaims();
        assertThat(launcher.isActive("run-1", "token-1", 7L)).isTrue();
        heartbeat.heartbeatActiveClaims();
        assertThat(launcher.isActive("run-1", "token-1", 7L)).isFalse();
    }

    @Test
    void isActiveMatchesExactIdentityAcrossTokenVersionAndRunId() {
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        LangchainLinearRunPipelineImpl pipeline = mock(LangchainLinearRunPipelineImpl.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ToolJobResumeService> provider = mock(ObjectProvider.class);
        ToolJobResumeService resumeService = mock(ToolJobResumeService.class);
        when(provider.getIfAvailable()).thenReturn(resumeService);
        AgentRun run = new AgentRun();
        run.setId("run-1");
        when(runMapper.findById("run-1")).thenReturn(run);
        when(pipeline.launchResumedAsync(eq(run), any(), any(), any())).thenReturn(true);

        ToolJobResumeLauncherImpl launcher = new ToolJobResumeLauncherImpl(runMapper, pipeline, provider);

        ToolJobResumeContext ctx = new ToolJobResumeContext();
        ctx.setRunId("run-1");
        ctx.setTodoId("todo-2");
        ctx.setResumeToken("token-a");
        ctx.setResumeLeaseVersion(5);
        ctx.setResumeLauncherOwnerId("owner-1");

        launcher.launch("run-1", ctx);

        // Exact identity match
        assertThat(launcher.isActive("run-1", "token-a", 5L)).isTrue();
        // Different token → NOT active
        assertThat(launcher.isActive("run-1", "token-b", 5L)).isFalse();
        // Same token, different version → NOT active
        assertThat(launcher.isActive("run-1", "token-a", 6L)).isFalse();
        // Different runId → NOT active
        assertThat(launcher.isActive("run-2", "token-a", 5L)).isFalse();
    }

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
