package world.willfrog.agentlangchain.deployment;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.entity.DeploymentGenerationRecord;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentityProvider;

class DeploymentGenerationReaperTest {

    @Test
    void requiresContinuousAbsenceAndASecondCheckBeforeWritingABoundedFailureBatch() {
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        DeploymentIdentityProvider provider = mock(DeploymentIdentityProvider.class);
        DeploymentIdentity local = new DeploymentIdentity("beta-a", "gen-" + "a".repeat(64));
        DeploymentIdentity old = new DeploymentIdentity("beta-a", "gen-" + "b".repeat(64));
        when(provider.current()).thenReturn(local);
        when(runMapper.listNonTerminalDeploymentGenerations(
                local.deploymentId(), local.generationId(), null, null, 32))
                .thenReturn(List.of(record(old)));
        when(runMapper.listNonTerminalDeploymentGenerations(
                local.deploymentId(), local.generationId(),
                old.deploymentId(), old.generationId(), 32)).thenReturn(List.of());
        DeploymentGenerationLivenessProbe probe = mock(DeploymentGenerationLivenessProbe.class);
        when(probe.hasLiveInstance(old)).thenReturn(false, false, false);
        AtomicLong now = new AtomicLong();
        DeploymentGenerationReaper reaper = new DeploymentGenerationReaper(
                runMapper, provider, probe, Duration.ofSeconds(60), 32, now::get);

        reaper.sweep();
        verify(runMapper, never()).failOrphanedNonTerminalRunsForDeploymentGeneration(
                old.deploymentId(), old.generationId(),
                "deployment_generation_shutdown_deadline_exceeded", 32);

        now.set(Duration.ofSeconds(60).toNanos());
        reaper.sweep();

        verify(probe, times(3)).hasLiveInstance(old);
        verify(runMapper).failOrphanedNonTerminalRunsForDeploymentGeneration(
                old.deploymentId(), old.generationId(),
                "deployment_generation_shutdown_deadline_exceeded", 32);
    }

    @Test
    void aLiveInstanceClearsTheAbsenceWindow() {
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        DeploymentIdentityProvider provider = mock(DeploymentIdentityProvider.class);
        DeploymentIdentity local = new DeploymentIdentity("beta-a", "gen-" + "a".repeat(64));
        DeploymentIdentity old = new DeploymentIdentity("beta-a", "gen-" + "b".repeat(64));
        when(provider.current()).thenReturn(local);
        when(runMapper.listNonTerminalDeploymentGenerations(
                local.deploymentId(), local.generationId(), null, null, 32))
                .thenReturn(List.of(record(old)));
        when(runMapper.listNonTerminalDeploymentGenerations(
                local.deploymentId(), local.generationId(),
                old.deploymentId(), old.generationId(), 32)).thenReturn(List.of());
        DeploymentGenerationLivenessProbe probe = mock(DeploymentGenerationLivenessProbe.class);
        when(probe.hasLiveInstance(old)).thenReturn(false, true, false);
        AtomicLong now = new AtomicLong();
        DeploymentGenerationReaper reaper = new DeploymentGenerationReaper(
                runMapper, provider, probe, Duration.ofSeconds(60), 32, now::get);

        reaper.sweep();
        now.set(Duration.ofSeconds(60).toNanos());
        reaper.sweep();
        now.set(Duration.ofSeconds(120).toNanos());
        reaper.sweep();

        verify(runMapper, never()).failOrphanedNonTerminalRunsForDeploymentGeneration(
                old.deploymentId(), old.generationId(),
                "deployment_generation_shutdown_deadline_exceeded", 32);
    }

    @Test
    void anUncertainRegistryReadResetsTheContinuousAbsenceWindow() {
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        DeploymentIdentityProvider provider = mock(DeploymentIdentityProvider.class);
        DeploymentIdentity local = new DeploymentIdentity("beta-a", "gen-" + "a".repeat(64));
        DeploymentIdentity old = new DeploymentIdentity("beta-a", "gen-" + "b".repeat(64));
        when(provider.current()).thenReturn(local);
        when(runMapper.listNonTerminalDeploymentGenerations(
                local.deploymentId(), local.generationId(), null, null, 32))
                .thenReturn(List.of(record(old)));
        when(runMapper.listNonTerminalDeploymentGenerations(
                local.deploymentId(), local.generationId(),
                old.deploymentId(), old.generationId(), 32)).thenReturn(List.of());
        DeploymentGenerationLivenessProbe probe = mock(DeploymentGenerationLivenessProbe.class);
        when(probe.hasLiveInstance(old))
                .thenReturn(false)
                .thenThrow(new IllegalStateException("registry unavailable"))
                .thenReturn(false, false);
        AtomicLong now = new AtomicLong();
        DeploymentGenerationReaper reaper = new DeploymentGenerationReaper(
                runMapper, provider, probe, Duration.ofSeconds(60), 32, now::get);

        reaper.sweep();
        now.set(Duration.ofSeconds(60).toNanos());
        reaper.sweep();
        now.set(Duration.ofSeconds(120).toNanos());
        reaper.sweep();

        verify(runMapper, never()).failOrphanedNonTerminalRunsForDeploymentGeneration(
                old.deploymentId(), old.generationId(),
                "deployment_generation_shutdown_deadline_exceeded", 32);
    }

    @Test
    void stableDeploymentIsRejectedEvenIfTheCandidateQueryReturnsIt() {
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        DeploymentIdentityProvider provider = mock(DeploymentIdentityProvider.class);
        DeploymentIdentity local = new DeploymentIdentity("beta-a", "gen-" + "a".repeat(64));
        DeploymentIdentity stable = new DeploymentIdentity("stable", "gen-" + "b".repeat(64));
        when(provider.current()).thenReturn(local);
        when(runMapper.listNonTerminalDeploymentGenerations(
                local.deploymentId(), local.generationId(), null, null, 32))
                .thenReturn(List.of(record(stable)));
        DeploymentGenerationLivenessProbe probe = mock(DeploymentGenerationLivenessProbe.class);
        DeploymentGenerationReaper reaper = new DeploymentGenerationReaper(
                runMapper, provider, probe, Duration.ZERO, 32, () -> 0L);

        reaper.sweep();

        verify(probe, never()).hasLiveInstance(stable);
        verify(runMapper, never()).failOrphanedNonTerminalRunsForDeploymentGeneration(
                stable.deploymentId(), stable.generationId(),
                "deployment_generation_shutdown_deadline_exceeded", 32);
    }

    @Test
    void cursorAdvancesPastLiveGenerationsAndWrapsForLaterCandidates() {
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        DeploymentIdentityProvider provider = mock(DeploymentIdentityProvider.class);
        DeploymentIdentity local = new DeploymentIdentity("beta-a", "gen-" + "a".repeat(64));
        DeploymentIdentity first = new DeploymentIdentity("beta-a", "gen-" + "b".repeat(64));
        DeploymentIdentity second = new DeploymentIdentity("beta-b", "gen-" + "c".repeat(64));
        when(provider.current()).thenReturn(local);
        when(runMapper.listNonTerminalDeploymentGenerations(
                local.deploymentId(), local.generationId(), null, null, 1))
                .thenReturn(List.of(record(first)));
        when(runMapper.listNonTerminalDeploymentGenerations(
                local.deploymentId(), local.generationId(),
                first.deploymentId(), first.generationId(), 1))
                .thenReturn(List.of(record(second)));
        DeploymentGenerationLivenessProbe probe = mock(DeploymentGenerationLivenessProbe.class);
        when(probe.hasLiveInstance(first)).thenReturn(true);
        when(probe.hasLiveInstance(second)).thenReturn(false);
        DeploymentGenerationReaper reaper = new DeploymentGenerationReaper(
                runMapper, provider, probe, Duration.ofSeconds(60), 1, () -> 0L);

        reaper.sweep();
        reaper.sweep();

        verify(probe).hasLiveInstance(first);
        verify(probe).hasLiveInstance(second);
    }

    @Test
    void aNaturallyFinishedGenerationStartsANewAbsenceWindowIfItAppearsAgain() {
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        DeploymentIdentityProvider provider = mock(DeploymentIdentityProvider.class);
        DeploymentIdentity local = new DeploymentIdentity("beta-a", "gen-" + "a".repeat(64));
        DeploymentIdentity old = new DeploymentIdentity("beta-a", "gen-" + "b".repeat(64));
        when(provider.current()).thenReturn(local);
        when(runMapper.listNonTerminalDeploymentGenerations(
                local.deploymentId(), local.generationId(), null, null, 32))
                .thenReturn(List.of(record(old)), List.of(), List.of(record(old)));
        when(runMapper.listNonTerminalDeploymentGenerations(
                local.deploymentId(), local.generationId(),
                old.deploymentId(), old.generationId(), 32)).thenReturn(List.of());
        DeploymentGenerationLivenessProbe probe = mock(DeploymentGenerationLivenessProbe.class);
        when(probe.hasLiveInstance(old)).thenReturn(false);
        AtomicLong now = new AtomicLong();
        DeploymentGenerationReaper reaper = new DeploymentGenerationReaper(
                runMapper, provider, probe, Duration.ofSeconds(60), 32, now::get);

        reaper.sweep();
        now.set(Duration.ofSeconds(60).toNanos());
        reaper.sweep();
        now.set(Duration.ofSeconds(120).toNanos());
        reaper.sweep();

        verify(runMapper, never()).failOrphanedNonTerminalRunsForDeploymentGeneration(
                old.deploymentId(), old.generationId(),
                "deployment_generation_shutdown_deadline_exceeded", 32);
    }

    @Test
    void anExactFullBatchIsForgottenAfterTheDatabaseConfirmsNoRunsRemain() {
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        DeploymentIdentityProvider provider = mock(DeploymentIdentityProvider.class);
        DeploymentIdentity local = new DeploymentIdentity("beta-a", "gen-" + "a".repeat(64));
        DeploymentIdentity old = new DeploymentIdentity("beta-a", "gen-" + "b".repeat(64));
        when(provider.current()).thenReturn(local);
        when(runMapper.listNonTerminalDeploymentGenerations(
                local.deploymentId(), local.generationId(), null, null, 32))
                .thenReturn(List.of(record(old)));
        when(runMapper.listNonTerminalDeploymentGenerations(
                local.deploymentId(), local.generationId(),
                old.deploymentId(), old.generationId(), 32)).thenReturn(List.of());
        when(runMapper.failOrphanedNonTerminalRunsForDeploymentGeneration(
                old.deploymentId(), old.generationId(),
                "deployment_generation_shutdown_deadline_exceeded", 32)).thenReturn(32);
        when(runMapper.countNonTerminalRunsForDeploymentGeneration(
                old.deploymentId(), old.generationId())).thenReturn(0);
        DeploymentGenerationLivenessProbe probe = mock(DeploymentGenerationLivenessProbe.class);
        when(probe.hasLiveInstance(old)).thenReturn(false);
        AtomicLong now = new AtomicLong();
        DeploymentGenerationReaper reaper = new DeploymentGenerationReaper(
                runMapper, provider, probe, Duration.ofSeconds(60), 32, now::get);

        reaper.sweep();
        now.set(Duration.ofSeconds(60).toNanos());
        reaper.sweep();
        now.set(Duration.ofSeconds(120).toNanos());
        reaper.sweep();

        verify(runMapper, times(1)).failOrphanedNonTerminalRunsForDeploymentGeneration(
                old.deploymentId(), old.generationId(),
                "deployment_generation_shutdown_deadline_exceeded", 32);
    }

    private static DeploymentGenerationRecord record(DeploymentIdentity identity) {
        DeploymentGenerationRecord record = new DeploymentGenerationRecord();
        record.setDeploymentId(identity.deploymentId());
        record.setDeploymentGenerationId(identity.generationId());
        return record;
    }
}
